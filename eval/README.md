# Evaluation set (Phase 3)

**Every report in this dataset is invented.** No real person, patient, clinic, or lab. Names,
IDs, and phone numbers (`555` numbers only) are all made up for this test. Nothing here was
ever a real document and none of it should be treated as one. See `SECURITY.md` and
`docs/evaluation.md` for the full policy this project follows.

## What's here

```text
eval/
  README.md          this file
  cases/<case-id>/
    case.json         metadata: id, title, format, expected status/error code
    report.txt        the input text (for PDF cases, the source text a PDF is rendered from)
    expected.json     the hand-written answer key
  rules/
    forbidden-phrases.txt
  thresholds.json      pass/fail limits, created after the first real run
  results/             gitignored - one folder per run
  .cache/               gitignored - cached model responses, keyed by prompt + input
```

Full design: `docs/phase-3-lld.md`. Method, metrics, and the committed baseline:
`docs/evaluation.md`.

## Running it

Two kinds of checks, from `backend/`:

```bash
./mvnw verify              # dataset consistency + masking regression - free, no API key, always runs
./mvnw test -Peval          # the full run against the real model - needs GEMINI_API_KEY, costs real calls
```

`./mvnw test -Peval` sends each case through the real pipeline, with model responses cached on
disk so a re-run after a code change costs no API calls unless `eval.refresh-cache=true` is set.
Console output is case ids and counts only; the scored detail goes to
`eval/results/<timestamp>/report.md`.

## Adding or changing a case

1. Write `report.txt` first, then write `expected.json` by reading it - never by running the
   pipeline and copying its output back into the answer key.
2. Every `case.json` must have `"synthetic": true`. `EvalDatasetLoader` refuses to load a case
   without it.
3. The case's folder name must equal its `case.json` id.
4. Every `testName` and `rawValue` in `expected.json`, and every planted personal-data string,
   must appear in `report.txt` exactly as written. `DatasetConsistencyTest` enforces this on
   every `./mvnw verify`.
5. A planted personal-data string must not be a substring of any expected biomarker field, or
   the other way round - otherwise the masking-leak check could pass by accident.
6. **Never edit an answer key or `thresholds.json` to make a score go up.** If a key was wrong,
   fix it and say why in the commit message.
