# Phase 4 findings log

Issues found while building and evaluating the OCR phase. Same shape as
`docs/phase-3-findings.md`: what was found, what was proven, and what was done about it.

---

## 1. Tesseract's TSV was being split on `"\n"`, so every word ended in a carriage return

**Status:** fixed inline (`TsvLineBuilder`), regression test added.

Tesseract writes CRLF line endings on Windows. The text column is the **last** field on a TSV
row, so splitting the output on `"\n"` alone left a `\r` glued to every single word.
`TextNormalizer` then converts a carriage return into a newline, and the result was that a lab
table reached the model as **one word per line**, with the column gaps surviving as leading
spaces.

Nothing failed loudly, which is why it survived the unit tests:

- Values still matched as whole tokens, so the support check passed and extraction looked fine.
- What broke was everything line-shaped downstream. `ResultRowDetector` saw no result rows, so
  masking protected nothing and `ConfidenceIndex`'s "prefer a result row" lookup had no rows to
  prefer.
- Planted PII surviving OCR **verbatim** went from 47% to ~100% once it was fixed — the garbled
  text had been unmatchable by the masking rules, which is the privacy half of the same bug.

The existing `TsvLineBuilderTest` fixtures all used `"\n"`, so they were green throughout.
Production is Linux and never saw it; every developer machine did. Found by the OCR evaluation
sweep, which is what it is for.

---

## 2. The lighting gradient defeats Tesseract's global binarisation

**Status:** open. Evidence recorded; the fix (adaptive thresholding) is a scope decision.

A synthetic page with a linear brightness ramp down to 70% made 6 of 10 tuning cases unreadable.
Running `tesseract` by hand on one of those renders returns **only the right-hand column** — the
bright end of the gradient — and loses the entire darker half of the page. The page is
perfectly legible to a person.

This is Tesseract choosing one global threshold for the whole image. It is also the single most
likely reason a real phone photo fails, and it is the evidence that would justify revisiting
decision **O8** ("preprocessing is minimal… add more steps only if the evaluation shows they
help"). The evaluation now shows they would help.

The committed `phone-photo` variant uses a gentler 85% ramp so that it measures digit accuracy
rather than binarisation; the harsher measurement is recorded here rather than lost.

---

## 3. The `too-long` case no longer fails as its answer key expects

**Status:** open, pre-existing, **blocks a complete text-case eval run**. Not a Phase 4
regression — `PlainTextExtractor`, `TextNormalizer` and `EvalRunner` are all untouched by this
phase.

`eval/cases/too-long` expects `FAILED` / `DOCUMENT_TOO_LONG`. It does not fail. The harness
builds the oversized input with `EvalRunner.repeatPastLimit`, which appends a 208-character
block until the string exceeds `max-text-chars` (50,000) — so it stops at roughly 50,000 to
50,208 characters. `TextNormalizer` then collapses runs of blank lines and strips trailing
whitespace, which removes about 770 characters, and the text arrives at the length check at
**49,438** — under the limit.

So the case proceeds to the model with a 49 KB prompt. On 2026-11-02 that request then failed
at the provider, which is what stopped the run — but see finding 6: **every** real call failed
that day, including small ones, so the size of this particular prompt is not implicated. The
defect recorded here is only that the case does not fail the way its key says it should.

**Fixed 2026-11-02.** `repeatPastLimit` now targets 10% past the limit instead of overshooting
by a single block — far more than normalisation can remove, and still trivial to build. Verified
by running the case on its own (`-Deval.only-case-ids=too-long`): it reaches `FAILED` with
`DOCUMENT_TOO_LONG`, makes no model call, and `EvalRunner` passes.

---

## 4. The retention sweep deleted the evaluation's own data mid-run

**Status:** fixed (`ReportFixtures` now writes timestamps from the injected clock).

`EvalRunner.ProviderConfig` pins the clock to 2027-01-01 so that collection dates stay
plausible, while `ReportFixtures` wrote `expires_at` from `Instant.now()` — the real clock. Every
session a run created was therefore already a year "expired" from the application's point of
view, and the scheduled sweep cascaded the reports away underneath the runner.

It only bit once a run got slow. The sweep fires 60 seconds after startup (`initialDelay` is a
literal, so no interval setting can stop the first one), and until a case took longer than that
the whole dataset was served from cache in seconds. When finding 3 made one case spend five
minutes retrying, the run died in `EvalRunner.readOutcome` with `NoSuchElementException` — the
report row was simply gone.

The fix is to take the time from the injected clock, which every other part of the application
already does. In every ordinary profile the two clocks are the same instant and nothing changes.

---

## 5. The OCR gate needs more model calls than one sitting reliably allows

**Status:** open, environmental. Superseded in part by finding 6 — capacity, not quota, is what
actually stopped the run of 2026-11-02.

`OcrEvalRunner` needs two model calls per case — about 20 for the ten OCR cases — against a free
tier that allows roughly 20 requests a day for the Gemini Flash family, shared with whatever the
text-case run has already spent (including failed attempts, which still count). `eval.max-llm-calls`
is set to 16 for the same reason.

The runner handles this honestly rather than pretending. Three checks run **before** the two
gate limits are applied: at least one matched row exists; no case failed with a provider-side
code; and the run was not stopped early by the budget. Any of them failing fails the test with
that reason, and stamps the written report `NOT A VALID GATE RESULT` — a filtered
`eval.only-case-ids` run included, because "0 dangerous flag errors" over one hand-picked case
otherwise reads exactly like the real thing.

That matters because both limits are satisfied *trivially* by a run where every case failed. A
green tick over an untested pipeline is worse than no tick at all.

Completing the gate is a multi-day exercise on this tier, or a paid key for one run.

---

## 6. `gemini-3.8-flash` is returning 503 "high demand" — provider capacity, not our network

**Status:** open, provider-side and transient by Google's own description. **This is what
blocked the gate**, not the quota and not connectivity.

Across two full evaluation runs every call that was not served from the response cache failed
as `TransientLlmException` wrapping `GenAiIOException` after 85–94 seconds — a small OCR prompt
exactly like a 49 KB one, which already ruled out payload size.

Diagnosed properly on 2026-11-02 by going around the Java client with `curl`:

| probe | result |
|---|---|
| `GET /v1beta/models` with the key | **HTTP 200 in 0.44 s** (DNS 0.06 s, TLS 0.10 s) |
| `POST models/gemini-3.8-flash:generateContent` ×3 | **503** in 2 s, then two 70 s hangs |
| 503 body | *"This model is currently experiencing high demand. Spikes in demand are usually temporary."* |
| `POST models/gemini-2.5-flash:generateContent` | 404: *"no longer available to new users… use models/gemini-3.8-flash"* |

So: the network is healthy, DNS resolves, TLS completes, and the key authenticates — a listing
call returns in under half a second. What fails is generation on `gemini-3.8-flash`
specifically, either as a fast 503 or as a hang past the client's 60 s timeout.

**The configuration is right.** `gemini-3.8-flash` does not appear in this account's
`/v1beta/models` listing, which looks alarming until you try the older model: Google's own 404
tells you to use `gemini-3.8-flash`. The listing is simply not a complete picture of what the
account may call.

An earlier version of this entry blamed local connectivity or a proxy. That was wrong, and it
would have sent someone to debug a healthy network. The `curl` probes above are the evidence.

There is nothing to fix in this repository. Retry when the model has capacity.
