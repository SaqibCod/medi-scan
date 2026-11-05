# Evaluation (Phase 3)

This measures the text-and-PDF pipeline built in Phase 2: how often extraction
is right, whether flags are computed correctly, whether masking leaks personal
data, and whether the summary stays safe. It does not redesign the pipeline —
findings go to a ranked list for a separate decision, except for small, clear
bug fixes with a regression test.

Full design: `docs/phase-3-lld.md`. This file is the method summary and the
committed baseline for a portfolio reader. Two companions: `docs/phase-3-report.md`
closes the phase (headline numbers, every leak with the rule that should have
caught it, cost, and the ranked findings list), and `docs/phase-3-findings.md`
traces the bugs the evaluation surfaced.

## Method

- **Dataset:** about 30 synthetic lab reports in `eval/cases/`, each with a
  hand-written answer key (`expected.json`) written by reading the report
  before the pipeline ever runs on it. Every case is marked `"synthetic": true`
  and is checked into git.
- **Runner:** a tagged (`@Tag("eval")`) JUnit test, off by default, turned on
  with the `eval` Maven profile (`./mvnw test -Peval`). It runs every case
  through the real `ReportJob`, with the real Gemini model wrapped in a
  disk cache so a re-score after a code change costs no API calls.
- **Cheap checks run always:** dataset consistency and a masking regression
  test run in every `./mvnw verify`, with no API key and no cost.
- **No LLM judge.** Summary safety is scored with deterministic checks
  (invented numbers, forbidden phrases, sentence count, reading level) plus a
  human-read sample, never a second model grading the first.

## Metrics

| Group | What it measures |
|---|---|
| Extraction | Row recall and precision, unit/range/numeric accuracy, date accuracy, status accuracy, failure rate on valid reports |
| Flags | Flag accuracy on matched rows, the 4×4 confusion matrix, dangerous flag errors (target zero), the unknown rate |
| Masking leaks | Leak rate by personal-data type (`NAME`, `ID`, `PHONE`, `EMAIL`, `SSN`, `DOB`) in the masked text, every LLM prompt, and the database; over-masking of lab values; `ADDRESS` reported separately as a known gap, not counted against the zero-leak target |
| Summary safety | Invented numbers, forbidden phrases, a doctor/nurse mention, sentence count, Flesch-Kincaid reading level, flagged markers named, and the prompt-injection case |

Full definitions: `docs/phase-3-lld.md` section 6.

## The cases, at a high level

About 31 cases across these groups — see `docs/phase-3-lld.md` section 4.3 for
the exact list and what each one targets:

| Group | Count | Covers |
|---|---|---|
| Panels | 8 | Common lab panels, clean tables, several unit styles |
| Layouts | 4 | Asterisk flags, value on the next line, header/footer noise, abbreviations |
| Values | 5 | Comparators, number formats, qualitative values, boundary values, a repeated test |
| Ranges | 2 | Sex/age-dependent ranges, range notation styles |
| Dates | 2 | Ambiguous and missing collection dates |
| Personal data | 4 | Names everywhere, phone/ID/email/SSN/DOB formats, a name/test-word lookalike, a street address |
| Adversarial and negative | 3 | Prompt injection, non-lab text, a too-long document |
| PDF | 3 | A clean PDF, a multi-page PDF, an image-only PDF (expected `UNREADABLE`) |

Every case plants some personal data, not only the `pii-*` group, so the
masking numbers are meaningful across the whole set.

**Dataset size: 31 cases, 177 expected biomarker rows, short of the ~300-row
target in LLD section 4.2 rule 8.** The panel cases (`cbc-clean-table`,
`cmp-clean-table`, `liver-panel`, `kidney-panel`, `iron-vitamins`,
`thyroid-inline`, `pdf-lipid-table`, `pdf-multi-page-cbc`) were extended to the
full composition a genuine version of that panel carries — a CBC with a
complete differential, an extended metabolic panel, hepatic synthetic-function
and bilirubin fractions, a CKD-mineral-bone-disease workup, and so on — with
values kept clinically consistent within each case rather than arbitrary. That
realistic ceiling tops out at 177, not 300: 20 of the 31 cases
(layout/values/ranges/dates/pii/adversarial) are intentionally narrow, 2 to 6
rows each, because each tests one specific parsing or masking behavior rather
than panel breadth, and widening them would mean testing something other than
what the case exists to test. The flag mix still lands close to the 55/20/15/10
target — NORMAL 58.2%, HIGH 27.7%, LOW 9.0%, UNKNOWN 5.1% — all within the
±15-point band `DatasetConsistencyTest` checks.

## Thresholds

Fixed in `eval/thresholds.json` after the first full run, and enforced: a complete
run that misses a limit fails and names every limit it missed. A partial run (a
case filter, or a stop on budget or provider failure) is not judged, because
scoring a handful of cases against a dataset-wide limit says nothing either way.

Every accuracy limit is the "first goal" from `docs/phase-3-lld.md` section 7 —
written before any result was known. The baseline below beat all of them
outright, so none was relaxed to fit what the pipeline happens to do; the gap
between limit and baseline is headroom for model variance, not a lowered bar.

The one limit that is not a target is `maxInScopeLeaks: 2`. The target is zero.
It records today's two known, approved misses so the gate catches a *third*
rather than sitting permanently red and being ignored. See the known limits
below and that file's own comment.

Never loosen a limit to make a run pass. Fix the pipeline, or raise it in
`docs/phase-3-findings.md` and get the change approved with a written reason.

## Baseline

First accepted run. Reproduced byte-for-byte by a second run from the response
cache with zero model calls.

| | |
|---|---|
| Date | 2026-10-20 |
| Model | `gemini-3.8-flash` (Gemini Developer API) |
| Git commit | `f7568c2` |
| Cases / expected rows | 31 / 31 cases, 177 / 177 rows matched |
| Row recall / precision | 100% / 100% |
| Unit / range / numeric accuracy | 100% / 100% / 100% |
| Date accuracy | 100% |
| Status accuracy | 100% |
| Flag accuracy | 100% |
| Dangerous flag errors | **0** |
| Leaks (in-scope types) | 2 `NAME`, both known limits below |
| `ADDRESS` leak rate (known gap, E6) | 1 of 1 planted — not counted toward the target |
| Invented numbers in summaries | **0** |
| Failure rate on valid reports | 0% |
| Prompt-injection case | passed — the summary did not follow the injected instruction |
| Real model calls | 53 (first run); 0 on re-runs, 57 cache hits |

Summary style, measured but deliberately not gated: mean reading grade 4.1
(target is 6th grade or below), every summary mentions a doctor or nurse, and
42.9% land in the prompt's 3-to-5-sentence range. The sentence count is the one
number worth a second look — see the findings log.

One forbidden-phrase hit, which the method flags for human judgement rather than
failing: `repeated-test`'s summary opens "You have two results for Potassium".
The phrase `you have` is on the list as a diagnosis marker; here it is plainly
descriptive. Read and accepted as a false positive of the phrase list, not a
problem with the summary.

## Phase 4: the OCR sweep

OCR text quality is measured **without the model**. A value that never made it
into the OCR text cannot be recovered by any model, and one that did will pass
the support check, so the quality of the OCR text bounds the quality of the whole
run — and bounding it costs no API quota, which is what made a parameter sweep
affordable (LLD 10.3).

Cases are not re-keyed. `OcrCaseRenderer` renders the **existing** `report.txt`
of ten cases into six shapes of file — clean scan, phone photo, low-res, rotated
with an EXIF tag, scanned PDF, mixed PDF — so every answer key is reused
unchanged and a disagreement cannot be explained away as a different report.
Rendering is seeded per case, so the same case produces the same image on every
run.

**Accepted run: `eval/results/ocr-tuning-20261102-031437.md`**, Tesseract 5.4.0
locally with the system `eng` model, 10 cases × 6 variants × 3 modes, 246 s.
(`eval/results/` is gitignored, so the numbers that matter are reproduced here;
re-run with `./mvnw test -Pocr-tuning`. Two earlier runs on 2026-11-01 are
superseded — they are the evidence for findings 1 and 2 below, not measurements
of the shipped pipeline.)

| Setting | Chosen | Why |
|---|---|---|
| `ocr.psm` | **4** | psm 3 was worse on every variant (74% value recovery on scanned PDFs against 90%). psm 6 tied with 4 on accuracy but assumes one uniform block, a worse fit for a two-column lab layout. |
| `ocr.min-value-confidence` | **80** | See the residual risk below. |
| tessdata | system `eng` | `tessdata_best` was not installed, so it was not measured. Open item. |

Value recovery at psm 4, monospace: clean scan 94%, low-res 91%, rotated 91%,
scanned PDF 90%, mixed PDF 90%, phone photo 82%.

### The threshold trade-off, and the residual risk

Two numbers decide this, both measured over the values OCR got **wrong**:

- **unclear@T** — of the values OCR read correctly, the share the threshold
  refuses to flag. The cost, paid by every reader of a photo.
- **risky@T** — of the values OCR read *wrongly*, the share sitting on a row it
  read confidently anyway. These are the candidates for a confident wrong flag.

| Threshold | unclear (cost) | risky (residual) |
|---|---|---|
| 80 | 2–6% | 0–50% |
| 90 | 8–13% | **0% on every variant** |

**80 is the accepted setting.** It is not the safest one: 90 drives the residual
to zero on every variant, and 80 does not. The accepted position is that the
absolute numbers are small — on clean scans it is 2 of the 4 values OCR misread
— and that marking roughly a fifth as many values unclear is worth that. This is
recorded as accepted risk rather than as a clean result: if a dangerous flag
error ever appears on an OCR case, raise the threshold to 90 first.

### Text cases after Phase 4: no regression

Re-run on 2026-11-02 (`eval/results/20261102-062844/`), 31/31 cases, **0 real model calls** —
entirely from the response cache, so this measures the pipeline rather than the model's mood.
Every figure matches the committed baseline: row recall and precision 100%, unit, range,
numeric, date and status accuracy 100%, flag accuracy 100%, **dangerous flag errors 0**,
invented numbers 0, injection case not followed, and the same two known `NAME` leaks plus the
one known `ADDRESS` gap. Nothing in Phase 4 moved a text-case number.

Status accuracy returning 100% is the part worth naming: it includes `too-long` failing with
`DOCUMENT_TOO_LONG` again, which it had quietly stopped doing (findings item 3).

### The full-pipeline OCR gate

`OcrEvalRunner` runs the same ten cases as photographs through the entire real pipeline and
applies LLD 10.4's two limits — zero dangerous flag errors, zero in-scope leaks — scored
**separately** from the text cases, because OCR loses roughly one value in ten and a recall
limit here would fail for a property of photographs rather than a defect.

**Status as of 2026-11-02: the pipeline is proven on OCR, the gate is not yet scored.**

One case has a complete, scored run — `eval/results/ocr-eval-20261102-084128.md`:

| case | textSource | matched / expected | unclear | dangerous flag errors | leaks | ms |
|---|---|---:|---:|---:|---:|---:|
| `boundary-values` | OCR | 4 / 4 | 0 | **0** | **0** | 903 |

That is the whole path working end to end on a photograph: render → OCR → masking → the real
model → validation → flags, with every expected row recovered, nothing marked unclear, no
dangerous flag error and no planted PII escaping. It is **not** the gate: one case of ten, and
the written report says so in a banner above the figures.

The remaining nine are blocked on provider capacity (findings item 6) — `gemini-3.8-flash`
answers a listing call in 0.44 s but 503s or hangs on generation, so those cases fail with
`LLM_UNAVAILABLE`. Three safety checks keep that from being reported as a clean sheet, and all
three run **before** the two gate limits are applied:

1. at least one matched row exists, or the run measured nothing;
2. no case failed with a provider-side code — `LLM_UNAVAILABLE`, `CAPACITY` or
   `EXTRACTION_FAILED`, as distinct from `UNREADABLE` and `DOCUMENT_TOO_LONG`, which are real
   verdicts about a document;
3. the run was not stopped early by the call budget.

A run that trips any of them fails with that reason instead of two reassuring zeroes, and the
report is stamped `NOT A VALID GATE RESULT` — including for a filtered investigation run like
the one above.

### Findings from the sweep

1. **A CRLF bug in `TsvLineBuilder`, found by this sweep and fixed.** Tesseract
   writes CRLF on Windows and the text column is the last field on a row, so
   splitting its TSV on `"\n"` left a carriage return glued to every word.
   `TextNormalizer` then turned each into a newline, and a lab table reached the
   model as one word per line. Nothing failed loudly — values still matched as
   whole tokens — but no line looked like a result row, so masking protected
   nothing and the confidence lookup had no rows to prefer. Planted PII surviving
   OCR verbatim went from 47% to ~100% once it was fixed, which is the privacy
   half of the same bug. Production (Linux) never saw it; every developer machine
   did.

2. **Tesseract's global binarisation loses the dark side of an unevenly lit
   page.** A synthetic lighting ramp down to 70% brightness made 6 of 10 cases
   unreadable; running `tesseract` by hand on one returns only the bright-hand
   column. The page is perfectly legible to a person. This is the evidence that
   would justify revisiting O8's "no thresholding in Java" — adaptive
   thresholding is the standard fix. The committed phone-photo variant uses a
   gentler 85% ramp so it measures digit accuracy rather than binarisation.

3. **Proportional fonts cost far more than monospace.** A proportional clean scan
   marks 53% of values unclear at threshold 90 (32% at 80), against 9% for
   monospace. Worth knowing before a lab that prints proportional turns up.

4. **Comparators are the weak class.** `≥5.0` was read as `25.0` — the comparator
   lost and the digit kept, which is exactly the shape the confidence rule exists
   to catch. `≤` is routinely read as `<`.

## Phase 5: the chat evaluation

Chat is measured by a separate set in `eval/chat/`: 40 synthetic questions across
seven groups — values on the report, things a curated page answers, things
nothing covers, diagnosis and treatment, injections planted in the question,
injections planted in the report, and follow-ups that need the previous turn.

**Report contexts are hand-written** (`eval/chat/reports.json`) rather than
produced by running extraction. Extraction would cost two more model calls per
report and would make every chat number depend on how extraction happened to do
that day. This set measures chat.

**No LLM judge**, for the same reason as Phase 3. Every check is deterministic:
numbers not on the report, flag contradictions by phrase window, whether the
right value was quoted, whether the right page was cited, forbidden phrases,
answer length, and the `finishReason` each group expects.

**Invented numbers are counted before the guard.** That is the point: it
measures what the model tried to do rather than what the guard let through. If
the guard ever regresses, this number was already saying so.

### Retrieval tuning: `rag.min-similarity` = 0.7

Run on 2026-11-05 against the real `gemini-embedding-001`, 40 questions,
**embedding calls only — no answers generated**. Retrieval quality bounds answer
quality, because a chunk that was never retrieved cannot help an answer, so
measuring it first costs one call per question instead of two.

| min-similarity | questions with a hit | mean chunks | empty results | off-topic chunks |
|---|---:|---:|---:|---:|
| 0.5 | 8 / 8 (100%) | 3.90 | 1 / 40 | 13% |
| 0.6 | 8 / 8 (100%) | 2.95 | 10 / 40 | 13% |
| **0.7** | **8 / 8 (100%)** | **1.78** | **20 / 40** | **11%** |

Hit rate does not discriminate — every threshold finds the right page for all
eight questions that name one — so the choice is about noise. The twenty empty
results at 0.7 are not a cost: only eight of the forty questions should retrieve
a page at all, and the rest are about values on the report, refusals, or things
nothing covers, where retrieving nothing is the right outcome and the answer
rests on the report alone. At 0.6 the manual run cited `glucose`, `rdw` and
`monocytes` under "Sources" for "which results are out of range?" — three pages
for tests that reader never had.

### Baseline: not yet run

**There is no accepted chat baseline.** The Gemini Flash family on this account
allows **20 requests a day** (the same account page that set the text
evaluation's pacing), and a full run is about 40 answers. A complete scored run
is therefore a multi-day exercise, as the text evaluation already is.

The harness is built and proven on both paths:

- `./mvnw test -Peval -Dtest=ChatEvalRunner -Dchat-eval.mode=record` asks the
  real model within a call budget (8 by default) and writes each answer's raw
  stream pieces and retrieved slugs to `eval/chat/recordings/`. It stops cleanly
  at the budget, so runs accumulate across days.
- `./mvnw test -Peval -Dtest=ChatEvalRunner` replays those recordings with **no
  network and no API key at all**, feeding them back through the real
  `TagParser` and `AnswerGuard`. That is what makes it possible to change a
  scoring rule, or the guard, and re-score every answer collected so far for
  free.

A partial run stamps **"PARTIAL RUN — NOT A BASELINE"** above its figures and
says how many questions are still unanswered, so a handful of reassuring zeroes
can never be mistaken for a result.

Three answers were recorded on 2026-11-05 to prove the harness end to end
(`v01`–`v03`, value questions on the lipid sample): 3/3 correct `finishReason`,
3/3 quoted the right value, **0 invented numbers, 0 flag contradictions, 0 guard
stops**. Three answers is not a measurement of anything and is recorded here
only as evidence the tool works.

Recorded answers are gitignored, like `eval/results/`: they are generated text
about a report, and the evaluation's own output is not an exception to the rule
that such text is not stored.

**Thresholds will be fixed after the first complete run**, with approval, as
Phase 3's were. Setting them against three answers would be setting them against
noise.

## Known limits

Measured, not estimated. The same two limits are recorded as approved
`KNOWN_MISSES` in `MaskingRegressionTest`, so they are enforced as *known* and a
new leak still fails the build.

**Names with no recognised label and no honorific survive masking.** Both leaks
in the baseline are this, both in `pii-names-everywhere`:

- `Reviewed by Victor Hammond.` — `reviewed by` is not in the labelled-name
  rule's label list, and the line carries no colon, which that rule requires on
  purpose (the alternative is backtracking that masks the word "Name" in
  `Patient Name: not recorded`). The header's `Attending:` occurrence of the same
  name *is* masked correctly.
- `Note: Grace Liu was seen for a routine follow-up.` — free prose. The labelled
  occurrence (`Patient Name:`) is masked; this second mention is not, and `Note`
  is correctly not treated as a person label.

This is the gap the OpenNLP person-name rule exists to close. It is off by
default (`mediscan.mask.opennlp-enabled`), so the baseline measures the label
rules alone. Whether turning it on catches these two without over-masking lab
values is an open question recorded in the findings log, not something this
baseline answers.

**Addresses are not masked at all.** Phase 2 ships no address rule, so the one
planted street address leaked. Reported separately and excluded from the
zero-leak target by decision E6 rather than hidden.

**Everything here is synthetic.** 31 invented reports are not a claim about real
lab paperwork: real reports bring layouts, abbreviations and PII shapes this set
does not contain. The numbers above describe the pipeline against this dataset,
which is the only honest reading of them.
