package dev.saq.mediscan.llm;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;

/**
 * A scripted {@link LlmProvider} for tests and local development.
 *
 * <p>No test calls a real model ({@code backend/CLAUDE.md}, "Testing"). This stands in
 * completely rather than being a mock with gaps: it is the same interface the real provider
 * implements, reached through the same gateway, so retries, the daily cap and usage logging
 * are all exercised for real.
 *
 * <p>It does three things a mock would not:
 *
 * <ul>
 * <li><strong>Queues responses per purpose</strong>, so a test can script "invalid, then
 * valid" and assert the retry actually happened.</li>
 * <li><strong>Records every request it received</strong>, which is how the canary test proves
 * no unmasked text ever reaches a prompt.</li>
 * <li><strong>Can block</strong>, which is how the queue-full and mid-job-delete tests hold a
 * worker in a known state without sleeping.</li>
 * </ul>
 *
 * <p>Only active when {@code mediscan.llm.provider=fake}, which
 * {@code LlmProviderEnvironmentPostProcessor} also uses to switch Spring AI's chat
 * autoconfiguration off.
 */
@Component
@ConditionalOnProperty(name = "mediscan.llm.provider", havingValue = "fake")
public class FakeLlmProvider implements LlmProvider {

	/** What the provider should do for one call. */
	public sealed interface Script {

		/** Return {@code value}, parsed already. */
		record Respond(Object value) implements Script {
		}

		/** Throw, as if the response could not be parsed. */
		record InvalidOutput() implements Script {
		}

		/** Throw a retryable failure. */
		record Transient() implements Script {
		}

		/** Throw a non-retryable failure. */
		record Permanent() implements Script {
		}

		/** Block until {@code release} is counted down, then return {@code value}. */
		record Block(CountDownLatch release, Object value) implements Script {
		}
	}

	/**
	 * What a streamed call should do (phase 5).
	 *
	 * <p>Separate from {@link Script} because a stream can fail <em>part way</em>, which a
	 * single-shot call cannot, and that case is the one the contract has an opinion about: the
	 * client must get an {@code error} event and no {@code done}.
	 */
	public sealed interface StreamScript {

		/** Emit each piece in order, then finish normally. */
		record Emit(List<String> pieces) implements StreamScript {
		}

		/** Emit each piece with a pause between, so pings and cancellation can be observed. */
		record EmitSlowly(List<String> pieces, Duration gap) implements StreamScript {
		}

		/** Emit some pieces, then fail. The mid-stream case. */
		record FailAfter(List<String> pieces, RuntimeException failure) implements StreamScript {
		}

		/** Fail before emitting anything, as a provider that is down would. */
		record FailImmediately(RuntimeException failure) implements StreamScript {
		}
	}

	private final Map<LlmPurpose, Deque<Script>> scripts = new EnumMap<>(LlmPurpose.class);

	/** Every request received, in order. Synchronised: two workers call concurrently. */
	private final List<LlmRequest> received = new ArrayList<>();

	private volatile int callCount;

	/**
	 * Whether an unscripted call synthesizes an answer instead of failing.
	 *
	 * <p>True only under the {@code local} profile. A test that reaches an unscripted call has
	 * under-specified itself, and inventing an answer there would hide the gap - so the two
	 * profiles get opposite behaviour on purpose.
	 */
	private final boolean synthesizeWhenUnscripted;

	public FakeLlmProvider(Environment environment) {
		for (LlmPurpose purpose : LlmPurpose.values()) {
			scripts.put(purpose, new ArrayDeque<>());
		}
		this.synthesizeWhenUnscripted = List.of(environment.getActiveProfiles()).contains("local");
	}

	// --- scripting ----------------------------------------------------------

	/** Queues a response for {@code purpose}. */
	public synchronized FakeLlmProvider queue(LlmPurpose purpose, Script script) {
		scripts.get(purpose).addLast(script);
		return this;
	}

	public FakeLlmProvider respondWith(LlmPurpose purpose, Object value) {
		return queue(purpose, new Script.Respond(value));
	}

	/**
	 * Makes every call for {@code purpose} return {@code value}, however many there are.
	 *
	 * <p>For tests that care about the pipeline rather than the call count.
	 */
	public synchronized FakeLlmProvider alwaysRespondWith(LlmPurpose purpose, Object value) {
		Deque<Script> queue = scripts.get(purpose);
		queue.clear();
		queue.addLast(new Script.Respond(value));
		standing.put(purpose, new Script.Respond(value));
		return this;
	}

	private final Map<LlmPurpose, Script> standing = new EnumMap<>(LlmPurpose.class);

	/** Clears all scripts, recorded requests and counters, streaming included. */
	public synchronized void reset() {
		scripts.values().forEach(Deque::clear);
		standing.clear();
		synchronized (received) {
			received.clear();
		}
		callCount = 0;
		// Streaming state too. Leaving it out let one test's queued stream be consumed by the
		// next, which fails as a confusing assertion about text the second test never wrote.
		streamScripts.clear();
		cancels.set(0);
		streamStarts.set(0);
	}

	// --- assertions ---------------------------------------------------------

	/** Every request received, oldest first. */
	public List<LlmRequest> received() {
		synchronized (received) {
			return List.copyOf(received);
		}
	}

	/** Requests received for one purpose. */
	public List<LlmRequest> received(LlmPurpose purpose) {
		return received().stream().filter(request -> request.purpose() == purpose).toList();
	}

	/** Total calls, including ones that threw. This is what retry tests assert on. */
	public int callCount() {
		return callCount;
	}

	// --- provider -----------------------------------------------------------

	@Override
	public String id() {
		return "fake";
	}

	@Override
	public String model() {
		return "fake-model";
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> LlmResult<T> structured(LlmRequest request, Class<T> outputType) {
		synchronized (received) {
			received.add(request);
		}
		callCount++;

		Script script = next(request.purpose());
		if (script == null) {
			if (synthesizeWhenUnscripted) {
				// Local development: behave like a plausible model so the app is usable by
				// hand without a key. Under the test profile this throws instead - a test
				// reaching an unscripted call has under-specified itself, and quietly
				// inventing an answer would hide that.
				return result((T) UnscriptedResponses.forPurpose(request, outputType));
			}
			throw new IllegalStateException(
					"FakeLlmProvider has no script queued for " + request.purpose()
							+ "; the test under-specified its expectations");
		}

		return switch (script) {
			case Script.Respond(Object value) -> result((T) value);
			case Script.InvalidOutput() -> throw new LlmException.InvalidLlmOutputException(
					"scripted invalid output");
			case Script.Transient() -> throw new LlmException.TransientLlmException(
					"scripted transient failure", null);
			case Script.Permanent() -> throw new LlmException.PermanentLlmException(
					"scripted permanent failure", null);
			case Script.Block(CountDownLatch release, Object value) -> {
				await(release);
				yield result((T) value);
			}
		};
	}

	// --- streaming (phase 5) -------------------------------------------------

	/** Queues one streamed answer. Consumed in order, like the single-shot scripts. */
	public synchronized FakeLlmProvider queueStream(StreamScript script) {
		streamScripts.addLast(script);
		return this;
	}

	/** The common case: one answer, emitted in the given pieces. */
	public FakeLlmProvider streamPieces(String... pieces) {
		return queueStream(new StreamScript.Emit(List.of(pieces)));
	}

	/** Streams in flight that have been cancelled, which is what disconnect tests assert on. */
	public int cancelCount() {
		return cancels.get();
	}

	/** Streams that were subscribed to at all. */
	public int streamCount() {
		return streamStarts.get();
	}

	@Override
	public Flux<LlmStreamChunk> stream(LlmRequest request) {
		// Everything inside the defer, including taking the script and recording the request.
		// The real provider sends nothing until subscription, so a fake that consumed its
		// script when the Flux was merely built would let a stream nobody read swallow the
		// next test's answer - and would record a request that was never made.
		return Flux.defer(() -> {
			synchronized (received) {
				received.add(request);
			}

			StreamScript script = nextStream();
			if (script == null) {
				return Flux.error(new IllegalStateException(
						"FakeLlmProvider has no stream queued; the test under-specified itself"));
			}

			streamStarts.incrementAndGet();
			return switch (script) {
				case StreamScript.Emit(List<String> pieces) -> Flux.fromIterable(chunks(pieces));
				case StreamScript.EmitSlowly(List<String> pieces, Duration gap) ->
						Flux.fromIterable(chunks(pieces))
								.delayElements(gap);
				case StreamScript.FailAfter(List<String> pieces, RuntimeException failure) ->
						Flux.fromIterable(textChunks(pieces))
								.concatWith(Flux.error(failure));
				case StreamScript.FailImmediately(RuntimeException failure) ->
						Flux.error(failure);
			};
		})
		// doOnCancel, not doFinally: the disconnect and guard-stop tests need to tell a
		// cancelled stream from one that simply ended.
		.doOnCancel(cancels::incrementAndGet);
	}

	/** Text pieces plus a final chunk carrying usage and a finish reason, as Gemini does. */
	private static List<LlmStreamChunk> chunks(List<String> pieces) {
		List<LlmStreamChunk> chunks = new ArrayList<>(textChunks(pieces));
		chunks.add(new LlmStreamChunk("", 1200, 180, "STOP"));
		return chunks;
	}

	private static List<LlmStreamChunk> textChunks(List<String> pieces) {
		return pieces.stream().map(LlmStreamChunk::text).toList();
	}

	private synchronized StreamScript nextStream() {
		return streamScripts.pollFirst();
	}

	private final Deque<StreamScript> streamScripts = new ArrayDeque<>();

	private final java.util.concurrent.atomic.AtomicInteger cancels =
			new java.util.concurrent.atomic.AtomicInteger();

	private final java.util.concurrent.atomic.AtomicInteger streamStarts =
			new java.util.concurrent.atomic.AtomicInteger();

	private synchronized Script next(LlmPurpose purpose) {
		Script queued = scripts.get(purpose).pollFirst();
		if (queued != null) {
			// A standing response is re-queued so it never runs out.
			Script standingScript = standing.get(purpose);
			if (standingScript != null) {
				scripts.get(purpose).addLast(standingScript);
			}
			return queued;
		}
		return standing.get(purpose);
	}

	private static <T> LlmResult<T> result(T value) {
		// Fixed, plausible token counts, so stats and usage assertions have something to
		// check without every test having to supply numbers.
		return new LlmResult<>(value, 1200, 180, 12);
	}

	private static void await(CountDownLatch release) {
		try {
			if (!release.await(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS)) {
				throw new IllegalStateException("FakeLlmProvider block was never released");
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while blocked", ex);
		}
	}
}
