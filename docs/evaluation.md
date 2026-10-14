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

First goals, fixed as `eval/thresholds.json` only after the first real run and
with explicit approval (never loosened to pass). See
`docs/phase-3-lld.md` section 7 for the starting numbers.

## Baseline

_Not yet run. Filled in after the first accepted run._

| | |
|---|---|
| Date | — |
| Model | — |
| Git commit | — |
| Cases / expected rows | — |
| Row recall / precision | — |
| Flag accuracy | — |
| Dangerous flag errors | — |
| Leaks (in-scope types) | — |
| `ADDRESS` leak rate (known gap) | — |
| Invented numbers in summaries | — |
| Failure rate on valid reports | — |

## Known limits

_Filled in after the first run, alongside the `SECURITY.md` masking update._
