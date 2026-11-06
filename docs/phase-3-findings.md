# Phase 3 findings log

Running log of issues found during Phase 3 evaluation that are not fixed inline. See
`docs/phase-3-lld.md` section 11 ("rules for acting on the results") and section 13 ("open
items for the final report").

---

## 1. Every real Gemini call has been going to `gemini-2.5-flash`, whatever `GEMINI_CHAT_MODEL` says

**Status:** root cause proven 2026-10-20. Fix proposed, not yet applied (needs sign-off:
touches `GeminiProvider` and the model configuration).

**Supersedes** the earlier entry in this file that described this as an intermittent,
harness-wide flakiness affecting `number-formats` / `pdf-lipid-table` / `abbreviations-and-case`.
That was wrong. The failure is **100% deterministic**, and it is a configuration bug in this
repo, not model instability, not rate limits, not the eval harness's retry settings.

### What is actually wrong

`GeminiProvider.structured()` never sets a model on the per-request options. It relies on the
`ChatModel` bean's default options, which `application.yml` configures via:

```yaml
spring.ai.google.genai.chat.options.model: ${GEMINI_CHAT_MODEL:...}
```

That property path is **deprecated** in Spring AI 2.0.1. From the module's own
`spring-configuration-metadata.json`:

```json
{ "name": "spring.ai.google.genai.chat.options.model",
  "deprecated": true,
  "deprecation": { "replacement": "spring.ai.google.genai.chat.model" } }
```

The deprecated path still *binds* as a property - `environment.getProperty(...)` returns the
right value, which is why every config check looked correct - but the autoconfiguration reads
the **new** path when building the chat model's default options. The new path was never set, so
it resolved to null, and `GoogleGenAiChatModel` fell back to its own library default:
`GoogleGenAiChatModel.ChatModel.GEMINI_2_5_FLASH` → `gemini-2.5-flash`.

Meanwhile `mediscan.llm.model` is a **second, disconnected value** that feeds every
`LlmUsageLogger` line and every `CachingLlmProvider` cache key. It only *claims* which model
ran. So the logs said `model=gemini-3.8-flash` while the actual HTTP request went to
`gemini-2.5-flash`.

### Proof

A diagnostic run through the real `GeminiProvider` under the real eval profile
(`@ActiveProfiles({"test","eval"})`), printing both the resolved config and the full exception
chain that `classify()` collapses:

```
mediscan.llm.model                        = gemini-3.8-flash
spring.ai.google.genai.chat.options.model = gemini-3.8-flash     (deprecated path - ignored)
spring.ai.google.genai.chat.model         = null                 (the path actually read)
provider.model() (what the log prints)    = gemini-3.8-flash

=== THREW after 280ms ===
[0] LlmException$TransientLlmException: provider call failed: IllegalArgumentException
[1] java.lang.IllegalArgumentException: ThinkingLevel.LOW is not supported for model
    'gemini-2.5-flash'. This model does not support thinkingLevel; use thinkingBudget instead.
      at GoogleGenAiChatModel.validateThinkingLevelForModel(GoogleGenAiChatModel.java:926)
      at GoogleGenAiChatModel.createGeminiRequest(GoogleGenAiChatModel.java:709)
```

Also confirmed: `gemini-3.8-flash` does not exist in Spring AI 2.0.1's `ChatModel` enum at all
(it stops at `GEMINI_3_6_FLASH`), and `gemini-2.5-flash` is the literal the library falls back
to.

### Why this took so long to find (four compounding blind spots)

1. **`classify()` cannot recognise Google SDK exceptions.** It matches Spring's
   `HttpStatusCodeException` (a RestTemplate type) and a few `java.net` types. The Google GenAI
   SDK throws `com.google.genai.errors.ClientException` / `ServerException` /
   `GenAiIOException`, and this validation failure throws a plain `IllegalArgumentException`.
   All of them fall through to the generic fallback and are labelled
   `TransientLlmException` → retried 3× → reported as `LLM_UNAVAILABLE`. A permanent,
   deterministic config error was presented as a transient provider outage. The retired-model
   `404` earlier in the phase was mislabelled the same way.
2. **The one useful detail was logged nowhere.** `LlmUsageLogger.safeDetailOf` includes the
   exception message only for `InvalidLlmOutputException`. Here the message was literally
   `"provider call failed: IllegalArgumentException"` - it named the cause class - but
   `TransientLlmException` messages are dropped, so the eval log only ever showed
   `error=TransientLlmException`.
3. **The timing was misleading.** `CachingLlmProvider` sleeps `eval.delay-between-calls-ms`
   (13000ms) in a `finally` block on success *and* failure, and `LlmGateway` times the whole
   `provider.structured()` call including that sleep. So `ms=13007` was a **7ms** failure plus
   a 13-second sleep, and read as a 13-second network problem.
4. **Every "direct call succeeds" scratch test was invalid.** Each one passed
   `.model("gemini-3.8-flash")` explicitly in the options builder, overriding the broken
   default - so those calls really did hit 3.8 and really did work. That is the entire reason
   direct calls succeeded 6/6 while EvalRunner failed 6/6. It was never about the harness.

Why cases that passed earlier now fail: changing `GEMINI_CHAT_MODEL` from 2.5 to 3.8 changed
`mediscan.llm.model`, which is part of the cache key, invalidating the whole disk cache. The
earlier green results were cache hits from the `thinkingBudget(0)` era (valid on 2.5). Every
case now needs a real call, and every real call hits the invalid `thinkingLevel` + 2.5
combination.

### Consequence for the Phase 3 baseline

The 16-case partial run was produced on `gemini-2.5-flash` while its report header says
`gemini-3.8-flash`. Any baseline committed from it would mis-attribute the model. It should be
re-run after the fix, not reused.

### Proposed fix

1. **`GeminiProvider.structured()`: set `.model(this.model)` on the per-request options.** One
   source of truth (`mediscan.llm.model`), and log lines plus cache keys are then guaranteed to
   match the model actually called. This alone removes the root cause and makes this class of
   bug impossible.
2. Move `application.yml` to the non-deprecated `spring.ai.google.genai.chat.model` path so the
   bean default agrees rather than silently diverging.
3. **Fix `classify()`** to recognise Google SDK exceptions: `ClientException` (4xx) → permanent
   except `429`; `ServerException` → transient; `GenAiIOException` → transient;
   `IllegalArgumentException` → permanent (a config bug must never be retried).
4. **Let `LlmUsageLogger` log the safe message for every `LlmException` kind.** By
   `LlmException`'s own contract the messages are fixed descriptions and class names, never
   response content, so this is privacy-safe - and it would have made this a five-minute
   diagnosis.
5. Re-check `thinkingLevel` vs `thinkingBudget` against whichever model string is actually
   settled on: Spring AI 2.0.1's capability map does not know `gemini-3.8-flash`, so validation
   behaviour for that string needs confirming before trusting it.

### Eval-profile config currently left in place

- `application-eval.yml`: `mediscan.llm.timeout: 60s`, `spring.ai.retry.max-attempts: 3`.
  Neither is load-bearing for this bug; both were added while chasing the wrong cause and
  should be reconsidered (especially `max-attempts`, whose correlation with failures was
  coincidental - the real variable was whether a scratch test overrode `.model(...)`).
- Production `application.yml` unchanged at `timeout: 30s`.

---

## 2. `mediscan.llm.timeout` was dead configuration (fixed)

Declared, documented and validated in `MediScanProperties`, bound from `application.yml`, and
read by nothing. Spring AI's Google GenAI module exposes no timeout property, so no timeout was
enforced on any call: one hung request blocked a job-pool worker indefinitely (observed
2026-10-18, over an hour, no response). With two workers (`backend/CLAUDE.md`, "Job pool"), two
such hangs stall every user.

Fixed by `GeminiClientConfig`, which supplies the `com.google.genai.Client` bean with
`HttpOptions.timeout(...)` from `mediscan.llm.timeout` (verified against the SDK bytecode:
milliseconds, applied as OkHttp's `callTimeout`). Spring AI's own `Client` bean is
`@ConditionalOnMissingBean`, so defining one replaces it cleanly. Confirmed working: a call that
previously hung unboundedly now cancels at the configured limit with
`InterruptedIOException: timeout`.
