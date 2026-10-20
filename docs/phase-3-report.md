# Phase 3 final report

Closes the evaluation phase. Answers every open item in `docs/phase-3-lld.md` section 13, and
carries the findings list that section 11 requires. Method and metric definitions live in
`docs/evaluation.md`; the traced bugs live in `docs/phase-3-findings.md`.

## Headline numbers

First accepted baseline: **2026-10-20**, `gemini-3.8-flash`, commit `f7568c2`, 31/31 cases.
Reproduced byte-for-byte by a second run from the response cache with **zero** model calls, so
these figures are a property of the recorded responses rather than of one lucky afternoon.

| | |
|---|---|
| Rows matched | 177 / 177 |
| Row recall / precision | 100% / 100% |
| Unit / range / numeric accuracy | 100% / 100% / 100% |
| Date / status accuracy | 100% / 100% |
| Flag accuracy | 100% |
| Dangerous flag errors | **0** |
| Invented numbers in summaries | **0** |
| Failure rate on valid reports | 0% |
| Prompt-injection case | passed - the summary did not follow the injected instruction |
| Leaks, in-scope types | 2 `NAME` (both known limits) |
| Leaks, `ADDRESS` (E6 gap) | 1 of 1 planted |

Every hard target in LLD section 7 is met. The two `NAME` leaks are the one place the pipeline
falls short of its own stated goal, and they are the first thing below.

A caveat that should travel with every number here: **31 invented reports are not a claim about
real lab paperwork.** Real reports bring layouts, abbreviations and identifier shapes this set
does not contain. These results describe the pipeline against this dataset.

## Every leak, and the rule that should have caught it

| Leaked string | Where | Rule responsible | Why it missed |
|---|---|---|---|
| `Victor Hammond` | `pii-names-everywhere` footer: `Reviewed by Victor Hammond.` | `labelledName` | Two reasons at once: `reviewed by` is not in the label list, and the line has no colon, which that rule requires deliberately. The header's `Attending:` occurrence of the same name **is** masked correctly. |
| `Grace Liu` | `pii-names-everywhere` body: `Note: Grace Liu was seen for a routine follow-up.` | none | Free prose. The labelled occurrence (`Patient Name:`) is masked; this one has no person label to key on, and `Note` is correctly not treated as one. No regex rule reaches this - it is what a statistical name finder is for. |
| `719 Larkspur Court, Millbrook, ST 00000` | `pii-address-facility` | none - no address rule exists | Phase 2 ships no address masking (decision E6). Reported separately, excluded from the zero-leak target, not hidden. |

No lab value, unit or reference range was damaged by masking in any case, so this coverage is
not being bought with over-masking.

## Answer-key corrections

**None.** No `expected.json` was edited at any point, and no threshold was set to accommodate a
score. The accuracy limits in `eval/thresholds.json` are the "first goals" from LLD section 7,
written before any result existed; the baseline beat all of them, so the gap between limit and
result is headroom for model variance, not a lowered bar. The one non-target limit
(`maxInScopeLeaks: 2`) is labelled as recorded debt in both that file and `docs/evaluation.md`.

One dataset observation that is *not* a correction: `header-footer-noise` carries a collection
date of `2026-11-02`, ahead of the real present day, so production's future-date check correctly
rejects it. The eval pins "now" to a documented 2027-01-01 reference precisely so the dataset is
time-independent - which only became true once that override actually took effect (commit
`408ee05`; it had been registered but never injected).

## Does the OpenNLP name model earn its place?

**Unproven - this phase did not settle it.** The model is off by default, so the entire baseline
measures the label rules alone. What the phase did achieve was turning a vague question into a
cheap, well-framed experiment:

- The label rules alone produce **two name leaks**, and both are exactly the class a statistical
  finder targets: one free-prose mention, one behind an unknown label.
- The other side of the trade is already instrumented. Over-masking is checked in
  `MaskingRegressionTest` (every lab value and range must survive byte for byte) and counted by
  the eval itself. So "leaks caught versus damage done" is directly measurable.

**Recommended experiment**, requiring no new code: run the set once with
`mediscan.mask.opennlp-enabled=true`, compare leaks caught against lab values damaged, and keep
whichever configuration wins. If it catches both and damages nothing, `maxInScopeLeaks` drops to
0 and the known-miss list empties. Until that runs, the honest statement is the one now in
`SECURITY.md`: the published figures describe the label rules.

## Model calls spent, and the free-tier limits found

**Free-tier limits (measured, not guessed):** **5 requests/minute** and **20 requests/day** for
the Gemini Flash family. Confirmed two ways - the rate-limit page in Google AI Studio, and a
live 429 whose body quoted `limit: 20, model: gemini-2.5-flash`. An earlier configuration had
guessed 6000ms pacing (10 RPM), double the real ceiling; it is now 13000ms.

**The consequence is structural:** a full run needs ~57 calls against a 20/day wall, so the
dataset *cannot* be completed in a single day on the free tier. That is why the runner caches
every response, counts real calls, and stops cleanly on budget rather than hammering the API.
Billing was enabled mid-phase to finish the baseline.

**Calls spent:** the baseline run used **53 real calls and 4 cache hits**. Phase-wide
consumption was considerably higher and is not precisely recoverable - many partial runs and
one-off diagnostics went on the plumbing bugs in the findings log, not on scoring.

**Cost:** at Flash pricing (about $0.30/1M input and $2.50/1M output tokens) one report costs
roughly **$0.0017**, so a full 31-case run is about **$0.05**. Negligible in absolute terms but
**not $0**, which is a deliberate and explicit departure from the constraint in `docs/plan.md`
section 1. Production stays on the free tier; only the eval spends money, and only when run on
purpose.

## Did Docker stay up?

**No.** The local Postgres container stopped repeatedly through the phase - roughly ten
occurrences, each needing a manual `docker compose up -d`. Two runs failed outright on it:
Testcontainers reporting `Could not find a valid Docker environment`, and a Spring context dying
on `Connection to localhost:5433 refused`. Neither corrupted a result, because both failed
before any scoring happened. It was nonetheless the single largest source of friction in running
this phase, and worth knowing before scheduling anything unattended.

## What the phase actually found

Worth stating plainly, because the headline accuracy numbers hide it: **most of this phase's
value was in four bugs the eval surfaced, none of which a unit test would have caught.** All are
traced in `docs/phase-3-findings.md` and fixed.

1. **Every real Gemini call was going to the wrong model.** `GeminiProvider` left the model to
   the autoconfigured bean's defaults, configured through a property path Spring AI 2.0
   deprecated - the keys still bound, so every check looked right, but the model silently fell
   back to the library default while the usage log and cache key reported the configured one.
   Two sources of truth, one decorative. The model is now set per request.
2. **Google's SDK errors were all misclassified as transient.** `classify()` matched Spring's
   `HttpStatusCodeException`, but the SDK throws its own `ApiException`/`GenAiIOException`, so a
   deterministic client-side rejection and a retired-model 404 were both retried as if the
   provider were merely flaky - then reported as `LLM_UNAVAILABLE`.
3. **`mediscan.llm.timeout` was dead configuration.** Declared, documented, validated, bound -
   and read by nothing. One hung call blocked a job-pool worker for over an hour. With two
   workers, two such hangs stall every user.
4. **The eval's own fixed clock was never injected.** Registered, but losing a by-parameter-name
   tiebreak to the production bean, so every date ran against the real system clock.

Two further correctness fixes came out of model behaviour rather than our own code: Gemini
corrupting `<=`/`>=` and en dashes when asked to reproduce them verbatim in JSON, which now gets
normalised before the summary call and discarded rather than stored when it reaches extraction.

## Done-checklist status (LLD section 12)

| # | Item | Status |
|---|---|---|
| 1 | `./mvnw verify` passes, including dataset and masking regression tests | Passed at 1040 tests on the last full run; **not re-run after the final commit** |
| 2 | `./mvnw test -Peval` runs the full set and writes a results folder | Done - 31/31 |
| 3 | A second cached run gives identical scores with zero model calls | Done - verified byte-identical |
| 4 | Baseline in `docs/evaluation.md`, measured masking limits in `SECURITY.md` | Done |
| 5 | `eval/thresholds.json` exists and is approved | File exists and is **enforced** (verified both failing and passing); approval is the maintainer's to give |
| 6 | No real data, key, cache or results in git | Done - `eval/results/` and `eval/.cache/` gitignored, no key committed |

## Findings list, ranked by risk

Privacy first, as LLD section 11 requires. Items 1 and 2 are the only ones that touch patient
data.

| # | Finding | Risk | Proposed fix | Rough cost |
|---|---|---|---|---|
| 1 | Addresses are not masked at all | **High** - a street address reaches the model and the database | Add an address rule, or enable and validate a broader PII pass | Moderate; address formats vary and over-masking a lab line is a real risk |
| 2 | Names in free text, or behind an unknown label, survive masking | **High** - two confirmed leaks | Run the OpenNLP experiment above. Separately, consider adding `reviewed by` to the label list - but note the colon requirement is deliberate, so relaxing it needs its own evidence | Small to try; the colon change needs care |
| 3 | `MaskingRegressionTest`'s `Victor Hammond` known-miss reason is factually wrong: it claims `Attending:` is not a known label, but it is, and that occurrence masks correctly | Low - documentation only, but it misdirects the next reader of a privacy record | Correct the reason to name the footer and the colon requirement. Left unedited on purpose: it is an approved record and the call is the maintainer's | One line |
| 4 | Production `mediscan.llm.timeout` is 30s, but a real PDF extract measured 21.4s | Medium - a thin margin means intermittent `LLM_UNAVAILABLE` on slow calls | Raise production to about 60s once a wider latency spread is observed; the eval profile is already 60s | One line, plus judgement |
| 5 | Only 42.9% of summaries land in the prompt's 3-to-5-sentence range | Low - style, not safety; reading grade is 4.1, well inside target | Prompt wording. Deliberately ungated and unchanged: a prompt edit needs approval and a re-baseline | Small change, non-trivial re-verification |
| 6 | `you have` fires as a forbidden phrase on `repeated-test`'s plainly descriptive "You have two results for Potassium" | Low - one false positive, surfaced for human judgement by design | Narrow the phrase, or allow a descriptive context | Small |
| 7 | The run header records neither total tokens nor wall-clock time, both of which LLD section 8 asks for | Low - observability | Add both to `RunInfo` | Small |
