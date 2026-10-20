package dev.saq.mediscan.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.saq.mediscan.llm.LlmException;
import dev.saq.mediscan.llm.LlmProvider;
import dev.saq.mediscan.llm.LlmRequest;
import dev.saq.mediscan.llm.LlmResult;

/**
 * Wraps a provider and keeps every {@link LlmRequest} it sent, per case, for the masking leak
 * check ({@code docs/phase-3-lld.md} section 6.4) - and watches for a persistent provider
 * outage, since {@link dev.saq.mediscan.llm.LlmGateway} retries and swallows the raw exception
 * type before it ever reaches the job (approval point 2, item 4: that signal has to be observed
 * here, at the per-attempt level, not by catching an exception type in the runner).
 *
 * <p>Cases run one at a time (LLD 5.1), so a single "current case" field is enough - there is
 * never a second case's attempts to confuse it with.
 */
public final class RecordingLlmProvider implements LlmProvider {

	/** What happened to one attempt. */
	public enum Outcome {
		SUCCESS, INVALID, TRANSIENT, PERMANENT
	}

	public record Attempt(LlmRequest request, Outcome outcome) {
	}

	private final LlmProvider delegate;
	private final Map<String, List<Attempt>> attemptsByCase = new LinkedHashMap<>();
	private String currentCaseId;

	public RecordingLlmProvider(LlmProvider delegate) {
		this.delegate = delegate;
	}

	/** Starts a fresh attempt list for {@code caseId}. */
	public void beginCase(String caseId) {
		this.currentCaseId = caseId;
		attemptsByCase.putIfAbsent(caseId, new ArrayList<>());
	}

	@Override
	public String id() {
		return delegate.id();
	}

	@Override
	public String model() {
		return delegate.model();
	}

	@Override
	public <T> LlmResult<T> structured(LlmRequest request, Class<T> outputType) {
		try {
			LlmResult<T> result = delegate.structured(request, outputType);
			record(request, Outcome.SUCCESS);
			return result;
		}
		catch (LlmException.InvalidLlmOutputException ex) {
			record(request, Outcome.INVALID);
			throw ex;
		}
		catch (LlmException.TransientLlmException ex) {
			record(request, Outcome.TRANSIENT);
			throw ex;
		}
		catch (LlmException.PermanentLlmException ex) {
			record(request, Outcome.PERMANENT);
			throw ex;
		}
	}

	/** Every request sent while processing {@code caseId}, for the leak check. */
	public List<LlmRequest> requestsFor(String caseId) {
		return attemptsByCase.getOrDefault(caseId, List.of()).stream().map(Attempt::request).toList();
	}

	/**
	 * Whether {@code caseId}'s last attempt was a provider failure (the gateway gave up on a
	 * {@code TransientLlmException} after retrying, or hit a {@code PermanentLlmException})
	 * rather than an invalid output or a success. Either means the provider itself is the
	 * problem, and the run should stop rather than spend its remaining budget rediscovering the
	 * same outage case after case.
	 */
	public boolean endedInProviderFailure(String caseId) {
		List<Attempt> attempts = attemptsByCase.getOrDefault(caseId, List.of());
		if (attempts.isEmpty()) {
			return false;
		}
		Outcome last = attempts.get(attempts.size() - 1).outcome();
		return last == Outcome.TRANSIENT || last == Outcome.PERMANENT;
	}

	private void record(LlmRequest request, Outcome outcome) {
		attemptsByCase.computeIfAbsent(currentCaseId, key -> new ArrayList<>())
				.add(new Attempt(request, outcome));
	}
}
