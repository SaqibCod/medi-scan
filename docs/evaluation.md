# Evaluation (Phase 3)

This measures the text-and-PDF pipeline built in Phase 2: how often extraction
is right, whether flags are computed correctly, whether masking leaks personal
data, and whether the summary stays safe. It does not redesign the pipeline —
findings go to a ranked list for a separate decision, except for small, clear
bug fixes with a regression test.

Full design: `docs/phase-3-lld.md`. This file is the method summary and the
committed baseline for a portfolio reader.

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
