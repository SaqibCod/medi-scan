package dev.saq.mediscan.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayDeque;
import java.util.Deque;

import org.junit.jupiter.api.Test;

import dev.saq.mediscan.llm.LlmException;
import dev.saq.mediscan.llm.LlmProvider;
import dev.saq.mediscan.llm.LlmPurpose;
import dev.saq.mediscan.llm.LlmRequest;
import dev.saq.mediscan.llm.LlmResult;

/** Hand-made scripted delegate, per {@code docs/phase-3-lld.md} section 9, item 3. */
class RecordingLlmProviderTest {

	@Test
	void recordsARequestAgainstTheCaseItWasSentDuring() {
		var recording = new RecordingLlmProvider(scripted(ok("value")));

		recording.beginCase("case-a");
		recording.structured(request("a-content"), String.class);

		assertThat(recording.requestsFor("case-a")).hasSize(1);
		assertThat(recording.requestsFor("case-a").get(0).userContent()).isEqualTo("a-content");
		assertThat(recording.requestsFor("case-b")).isEmpty();
	}

	@Test
	void keepsSeparateRequestListsForDifferentCases() {
		var recording = new RecordingLlmProvider(scripted(ok("x"), ok("y")));

		recording.beginCase("case-a");
		recording.structured(request("a"), String.class);
		recording.beginCase("case-b");
		recording.structured(request("b"), String.class);

		assertThat(recording.requestsFor("case-a")).hasSize(1);
		assertThat(recording.requestsFor("case-b")).hasSize(1);
	}

	@Test
	void aSuccessfulCaseDidNotEndInProviderFailure() {
		var recording = new RecordingLlmProvider(scripted(ok("value")));

		recording.beginCase("case-a");
		recording.structured(request("a"), String.class);

		assertThat(recording.endedInProviderFailure("case-a")).isFalse();
	}

	@Test
	void transientFailureAfterAllRetriesIsAProviderFailure() {
		// Simulates the gateway's retry loop calling structured() three times, all
		// TransientLlmException, before giving up.
		var recording = new RecordingLlmProvider(scripted(transient_(), transient_(), transient_()));

		recording.beginCase("case-a");
		for (int i = 0; i < 3; i++) {
			assertThatThrownBy(() -> recording.structured(request("a"), String.class))
					.isInstanceOf(LlmException.TransientLlmException.class);
		}

		assertThat(recording.endedInProviderFailure("case-a")).isTrue();
	}

	@Test
	void aTransientFailureFollowedByASuccessIsNotAProviderFailure() {
		var recording = new RecordingLlmProvider(scripted(transient_(), ok("value")));

		recording.beginCase("case-a");
		assertThatThrownBy(() -> recording.structured(request("a"), String.class))
				.isInstanceOf(LlmException.TransientLlmException.class);
		recording.structured(request("a"), String.class);

		assertThat(recording.endedInProviderFailure("case-a")).isFalse();
	}

	@Test
	void aPermanentFailureIsAlsoAProviderFailure() {
		var recording = new RecordingLlmProvider(scripted(permanent()));

		recording.beginCase("case-a");
		assertThatThrownBy(() -> recording.structured(request("a"), String.class))
				.isInstanceOf(LlmException.PermanentLlmException.class);

		assertThat(recording.endedInProviderFailure("case-a")).isTrue();
	}

	@Test
	void anInvalidOutputIsNotTreatedAsAProviderFailure() {
		// Invalid output is the model's problem, not the provider's - the run should not stop
		// over it the way it would over a real outage.
		var recording = new RecordingLlmProvider(scripted(invalid()));

		recording.beginCase("case-a");
		assertThatThrownBy(() -> recording.structured(request("a"), String.class))
				.isInstanceOf(LlmException.InvalidLlmOutputException.class);

		assertThat(recording.endedInProviderFailure("case-a")).isFalse();
	}

	@Test
	void aCaseNeverStartedHasNoFailureAndNoRequests() {
		var recording = new RecordingLlmProvider(scripted(ok("value")));

		assertThat(recording.endedInProviderFailure("never-begun")).isFalse();
		assertThat(recording.requestsFor("never-begun")).isEmpty();
	}

	private static LlmRequest request(String userContent) {
		return new LlmRequest(LlmPurpose.EXTRACT, "system", userContent, 0.0, 100);
	}

	@SafeVarargs
	private static LlmProvider scripted(ScriptedResponse... responses) {
		Deque<ScriptedResponse> queue = new ArrayDeque<>(java.util.List.of(responses));
		return new LlmProvider() {
			@Override
			public String id() {
				return "fake";
			}

			@Override
			public String model() {
				return "fake-model";
			}

			@Override
			public <T> LlmResult<T> structured(LlmRequest request, Class<T> outputType) {
				return queue.poll().respond();
			}
		};
	}

	private interface ScriptedResponse {
		<T> LlmResult<T> respond();
	}

	private static ScriptedResponse ok(String value) {
		return new ScriptedResponse() {
			@Override
			@SuppressWarnings("unchecked")
			public <T> LlmResult<T> respond() {
				return new LlmResult<>((T) value, 1, 1, 1L);
			}
		};
	}

	private static ScriptedResponse transient_() {
		return new ScriptedResponse() {
			@Override
			public <T> LlmResult<T> respond() {
				throw new LlmException.TransientLlmException("simulated 429", null);
			}
		};
	}

	private static ScriptedResponse permanent() {
		return new ScriptedResponse() {
			@Override
			public <T> LlmResult<T> respond() {
				throw new LlmException.PermanentLlmException("simulated bad key", null);
			}
		};
	}

	private static ScriptedResponse invalid() {
		return new ScriptedResponse() {
			@Override
			public <T> LlmResult<T> respond() {
				throw new LlmException.InvalidLlmOutputException("simulated bad json");
			}
		};
	}
}
