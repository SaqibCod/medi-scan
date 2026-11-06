package dev.saq.mediscan.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import dev.saq.mediscan.support.PostgresTestBase;
import reactor.core.publisher.Flux;

/**
 * The streaming half of the gateway ({@code LLD} 9, phase 5).
 *
 * <p>Three promises, each of which costs money or correctness when broken.
 *
 * <p><strong>The cap is taken once, on subscription.</strong> Not when the {@code Flux} is
 * built - a request refused for some other reason after the stream was prepared would
 * otherwise have spent budget for nothing - and not once per chunk.
 *
 * <p><strong>A failed stream is not retried.</strong> By the time a stream fails, some of the
 * answer is usually on the reader's screen; starting again would duplicate or contradict it.
 * The contract says so too: a mid-stream failure is an {@code error} event, not a second
 * attempt.
 *
 * <p><strong>Token counts are cumulative, so only the last chunk is read.</strong> Summing
 * every chunk would multiply a short answer's recorded cost by its chunk count.
 */
class LlmGatewayStreamTest extends PostgresTestBase {

	@Autowired
	private LlmGateway gateway;

	@Autowired
	private FakeLlmProvider provider;

	@Autowired
	private DailyCapGuard capGuard;

	@Autowired
	private JdbcClient jdbc;

	@BeforeEach
	void reset() {
		provider.reset();
		jdbc.sql("delete from llm_usage").update();
	}

	@Test
	@DisplayName("the pieces arrive in order and the stream completes")
	void streamsInOrder() {
		provider.streamPieces("[[ANSWER]] Your LDL ", "is 162 mg/dL.");

		List<String> text = stream().map(LlmStreamChunk::text).collectList().block();

		assertThat(text).containsExactly("[[ANSWER]] Your LDL ", "is 162 mg/dL.", "");
	}

	@Test
	@DisplayName("building the stream costs nothing until it is subscribed to")
	void coldUntilSubscribed() {
		provider.streamPieces("one");

		Flux<LlmStreamChunk> unsubscribed = stream();

		assertThat(capGuard.usedToday())
				.as("a Flux nobody has subscribed to must not have spent budget")
				.isZero();
		assertThat(provider.streamCount()).isZero();

		unsubscribed.blockLast();
		assertThat(capGuard.usedToday()).isOne();
	}

	@Test
	@DisplayName("one answer takes exactly one call from the cap")
	void takesOneCapEntryPerAnswer() {
		provider.streamPieces("a", "b", "c", "d");

		stream().blockLast();

		// Per answer, not per chunk.
		assertThat(capGuard.usedToday()).isOne();
	}

	@Test
	@DisplayName("a mid-stream failure reaches the subscriber and is not retried")
	void midStreamFailureIsNotRetried() {
		provider.queueStream(new FakeLlmProvider.StreamScript.FailAfter(
				List.of("partial answer "),
				new LlmException.TransientLlmException("provider died", null)));

		// collectList() fails the whole sequence, which is the behaviour that matters: the
		// subscriber sees the error rather than a quietly truncated answer.
		assertThatThrownBy(() -> stream().collectList().block())
				.isInstanceOf(LlmException.TransientLlmException.class);

		// One attempt. A retry would replay an answer the reader has already half seen.
		assertThat(provider.streamCount()).isOne();
		assertThat(capGuard.usedToday()).isOne();
	}

	@Test
	@DisplayName("the cap being reached fails the stream before the provider is called")
	void capacityStopsTheStream() {
		fillTheCap();
		provider.streamPieces("never sent");

		assertThatThrownBy(() -> stream().collectList().block())
				.isInstanceOf(LlmException.CapacityLlmException.class);

		assertThat(provider.streamCount())
				.as("no provider call once the budget is gone")
				.isZero();
	}

	@Test
	@DisplayName("cancelling the stream stops the provider")
	void cancellationReachesTheProvider() {
		provider.queueStream(new FakeLlmProvider.StreamScript.EmitSlowly(
				List.of("one", "two", "three", "four"), Duration.ofMillis(50)));

		// Taking one chunk and stopping is what the number guard does when it rejects a
		// sentence, and what a reader closing the tab does.
		stream().take(1).blockLast();

		assertThat(provider.cancelCount())
				.as("a stream nobody is reading still costs tokens, so it must be cancelled")
				.isPositive();
	}

	@Test
	@DisplayName("the chat model, not the pipeline model, is used for chat")
	void chatUsesItsOwnModel() {
		// FakeLlmProvider reports one model for everything, so this asserts the plumbing: the
		// gateway must ask the provider for the CHAT model rather than the default one.
		assertThat(provider.model(LlmPurpose.CHAT)).isEqualTo(provider.model());

		provider.streamPieces("x");
		stream().blockLast();

		assertThat(provider.received(LlmPurpose.CHAT)).hasSize(1);
	}

	@Test
	@DisplayName("the request carries the chat purpose and never prints its content")
	void requestHidesItsContent() {
		provider.streamPieces("x");
		stream().blockLast();

		LlmRequest request = provider.received(LlmPurpose.CHAT).getFirst();

		assertThat(request.purpose()).isEqualTo(LlmPurpose.CHAT);
		// CLAUDE.md rule 19: the user content is a question about someone's report.
		assertThat(request.toString()).doesNotContain("Why is my LDL flagged");
	}

	private Flux<LlmStreamChunk> stream() {
		return gateway.streamChat("system rules", "<question>\nWhy is my LDL flagged?\n</question>",
				0.2, 400);
	}

	/** Spends the whole daily budget, so the next acquire fails. */
	private void fillTheCap() {
		while (capGuard.tryAcquire()) {
			// Deliberately empty: tryAcquire is the loop body.
		}
	}
}
