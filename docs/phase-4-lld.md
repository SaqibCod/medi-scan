# Phase 4: OCR for photos and scanned PDFs (low-level design)

Backend first, then a small frontend switch-on. Branch: `feat/phase-4-ocr`, created from `dev`.

This phase lets a user upload:
- a **photo** of a lab report (PNG or JPEG)
- a **scanned PDF** (pages with no text layer)

The text is read with Tesseract OCR, then goes through the same pipeline as before: masking, extraction, validation, flags, and summary. Nothing after extraction changes, except one safety rule for values OCR wasn't sure about (section 7).

**Overall order (unchanged):** 7a → 7b → **Phase 4** → Phase 5 (chat) → 7c → 7d → Phase 8 (deploy).

**References**
- `docs/phase-2-lld.md`: sections 5 (configuration), 6 (upload layer), 7 (job), and 9 (extraction). This phase extends them.
- `docs/phase-3-lld.md` and `docs/evaluation.md`: the evaluation set, which gets OCR cases.
- `docs/phase-7b-lld.md`: B4 and B5 (the OCR copy switch and photo refusal), which this phase turns on.
- `docs/api-contract.md`: 4.1, 4.2, 8.2, and 9.
- `backend/CLAUDE.md`: memory rules (2 GB box) and privacy in code.

---

## 1. Why OCR is risky here, and the rules that follow

OCR can misread a value: `5.4` → `54`, `0.8` → `O.8`, `1.2` → `l.2`, or a dropped decimal point. A misread number can get a confident, **wrong** flag. That is the worst failure this app can have. So:

1. **Low-confidence values are never flagged** (section 7). If OCR wasn't sure about the characters of a value, the row is kept but its flag becomes `UNKNOWN`, and it's left out of trends.
2. **The user is told** when results came from OCR, and asked to check each value against the paper report (section 9).
3. **The evaluation set measures it** before the phase is accepted, with a zero target for dangerous flag errors (section 10).
4. **Images never go to the LLM.** Only OCR text, after masking (same as the plan).

---

## 2. Decisions (already made, confirm at the approval point)

| # | Decision | Reason |
|---|---|---|
| O1 | **Run the `tesseract` command-line program as a child process**, instead of Tess4J (the plan said Tess4J). | A crash in native code loaded through JNA kills the whole JVM. A child process can be killed on timeout, its memory is returned when it exits, and it can't take the server down. Update the plan and `CLAUDE.md`. |
| O2 | **One OCR process at a time** (a semaphore with 1 permit), as the plan says. | 2 GB box. |
| O3 | **Tesseract output format is TSV** (`tesseract <img> stdout --psm N tsv`). Text lines are rebuilt from it in Java, and word confidences come from it in the same run. | One run gives both text and confidence. |
| O4 | **Low-confidence rule:** a biomarker whose `rawValue` comes from OCR words with confidence below `ocr.min-value-confidence` (start at 80) gets flag `UNKNOWN` and `numericValue: null`. | No confident flag on a doubtful number (section 1). |
| O5 | **New optional result field `textSource`:** `TEXT_LAYER`, `OCR`, or `MIXED`. Stored on the report. | The client must know when to show the "check your values" note. A scanned PDF has `sourceType: PDF`, so `sourceType` alone can't tell. Adding an optional field is a compatible change (contract 9.2). |
| O6 | **Limits:** at most `ocr.max-pages` (10) OCR pages per report. More low-text pages → `DOCUMENT_TOO_LONG`. Per-page timeout `ocr.page-timeout` (45 s). Whole-report OCR budget `ocr.total-timeout` (150 s). A timeout → `EXTRACTION_FAILED`. | Protects the box and the 2-minute polling window. No new failure codes. |
| O7 | **Image safety:** read the pixel size from the header before decoding. Over `ocr.max-pixels` (40 megapixels) → `UNREADABLE`. Never decode an image whose size is unknown. | Decompression bombs (a small file that expands to gigabytes). |
| O8 | **Preprocessing is minimal:** apply the EXIF rotation, convert to grayscale, and scale the long edge into `[1500, 4000]` px. Then write a temp PNG for Tesseract. No deskew, thresholding or denoise in Java. | Tesseract does its own binarisation. Add more steps only if the evaluation shows they help. |
| O9 | **English only** (`eng`). | Matches the app. |
| O10 | **The page-segmentation mode (`--psm`) and the tessdata model (standard or `best`) are chosen by the evaluation run,** not guessed. Start with `--psm 4` and the Debian package model. | Lab tables vary. Measure, don't guess. |
| O11 | **Frontend:** `NEXT_PUBLIC_FEATURE_OCR=true` turns on the 7b copy and photo upload. Add the OCR note on results. | The switch was built in 7b for this. |

---

## 3. Docs and contract changes (first, before code)

1. **`docs/api-contract.md`** (new minor version and a changelog entry):
   - **4.1:** remove "until Phase 4, PNG and JPEG return 415". Images are accepted. Add: "Photos are read with OCR. Very large images (over 40 megapixels) end as `FAILED` with `UNREADABLE`."
   - **4.2 and 9.1:** add `result.textSource: "TEXT_LAYER" | "OCR" | "MIXED"`, with the meanings "all text from the PDF text layer or pasted text", "all text from OCR", and "some pages from each". Add a note: values read by OCR with low confidence have flag `UNKNOWN` and `numericValue: null`.
   - **8.2:** `UNREADABLE` stays as written. Note that `DOCUMENT_TOO_LONG` also covers "more than 10 scanned pages".
   - Update `client/lib/api-types.ts` in the same commit (`textSource` as `"TEXT_LAYER" | "OCR" | "MIXED"`, with unknown values treated as `OCR` by the client, the safe side).
2. **`docs/plan.md`:**
   - stack: Tesseract CLI (O1), not Tess4J
   - section 4: the low-confidence rule (O4)
   - section 14: Phase 4 as current
   - `.env.example` / section 10: the new `OCR_*` variables (section 11)
3. **`docs/dataflow.md`:** the extraction section. The OCR path, the semaphore, the per-page fallback, the confidence rule, and `textSource`.
4. **Root `CLAUDE.md`:** the `extract` row says "Tesseract CLI". Add the rule "Never log OCR output, Tesseract stdout or stderr, or file names. Log only exit codes, timings, page counts and confidence summaries."
5. **`backend/CLAUDE.md`** (gitignored, edit on disk only): the same wording in the package map and memory section.
6. **`SECURITY.md`:** the OCR process (no shell, arguments as a list, timeouts, output cap), the image size limit, and the low-confidence rule.
7. **`docs/evaluation.md`:** the new OCR cases and metrics (after the run, the new baseline).

Edit what is in the repo now. The repo copies may differ.

---

## 4. Database

New migration with the next free number (check the repo; for example `V5__report_text_source.sql`):

```sql
-- How the report's text was obtained. Null for reports created before Phase 4.
ALTER TABLE report ADD COLUMN text_source text
    CHECK (text_source IN ('TEXT_LAYER','OCR','MIXED'));
```

- Set in the final transaction (`ReportResultSaver`) together with `DONE`. Failed reports leave it null.
- The API maps a null `text_source` on an old `DONE` report to `TEXT_LAYER` (every pre-Phase-4 report was text-layer or pasted text).

---

## 5. Classes (package `extract`, plus small changes elsewhere)

| Class | Responsibility |
|---|---|
| `OcrEngine` (interface) | `OcrPage recognize(Path image, Duration timeout)`. |
| `TesseractCliEngine` | Runs the process (section 6.2), parses TSV into `OcrPage`. |
| `FakeOcrEngine` (test sources) | Scripted pages, confidences, timeouts and failures. Records the calls. |
| `OcrPage` (record) | `String text`, `List<OcrWord> words`, `double meanConfidence`. `toString()` hides text and words. |
| `OcrWord` (record) | `String text`, `int confidence`, `int line`, plus position. `toString()` hides `text`. |
| `TsvLineBuilder` | Rebuilds lines from TSV rows (section 6.3). Pure, table-tested. |
| `ImagePreparer` | Header-only size check, EXIF rotation, grayscale, scaling, write temp PNG (section 6.1). |
| `OcrGate` | The semaphore (O2) and the total-time budget per report (O6). |
| `ImageOcrExtractor` | `TextExtractor` for PNG and JPEG. Prepares, then OCRs, then returns `ExtractedText` with `textSource=OCR` and a `ConfidenceIndex`. |
| `PdfTextExtractor` (changed) | Low-text pages are rendered and OCR'd (section 6.4). Returns `TEXT_LAYER`, `OCR` or `MIXED`. |
| `ConfidenceIndex` | For OCR text: maps character ranges of the extracted text to word confidence. Used by the validator (section 7). `toString()` prints only sizes. |
| `OcrStartupCheck` | At startup: `tesseract --version` and `--list-langs` include `eng`. Logs the version. |
| `ExtractedText` (changed) | Adds `TextSource textSource` and `ConfidenceIndex confidence` (empty for text-layer). |
| `JobSource.ImageFile(Path, ImageKind)` | New job source for images. |
| `BiomarkerValidator` (changed) | Applies the low-confidence rule (section 7). |
| `ReportResultSaver`, `ReportResponseMapper` (changed) | Store and return `textSource`. |

**Masking and offsets:** `ConfidenceIndex` offsets refer to the **raw** extracted text. Masking replaces spans, so offsets move. `Masker` must return an offset map (raw → masked) for the protected (result-row) spans, which masking never changes. Alternatively, the validator can look values up by **token text**: for each `rawValue`, find its whole-token occurrences in the masked text, map them back to raw positions through the masker's span list, and take the lowest confidence. Pick the simpler correct approach after reading the Phase 2 masker code, and show it at approval point 2. The rule: a `rawValue` confidence is the **minimum** over every OCR word that overlaps **any** occurrence of that value on a result row.

---

## 6. Extraction details

### 6.1 Images (`ImagePreparer`)

1. **Size check:** use `ImageIO.getImageReaders` with an `ImageInputStream` on the temp file, read width and height from the header **without decoding**. No reader, or an error → `UNREADABLE`. `width × height > ocr.max-pixels` → `UNREADABLE`.
2. **EXIF orientation (JPEG):** read the orientation tag. Check Context7 for whether the JDK can read it, or whether a small library such as `metadata-extractor` (Apache 2.0) is needed. Ask me before adding it.
3. **Decode, then process:**
   - decode, apply the rotation, and convert to 8-bit grayscale
   - scale so the long edge is at least 1500 px and at most 4000 px (bicubic)
   - write a PNG to the temp dir with a random name
4. **Free memory:** release the decoded image as soon as the PNG is written (`flush()`, drop references).
5. **Delete** the original and the prepared PNG in `finally`.

**Memory note:** a 40 MP grayscale image is about 40 MB, and an RGB decode is about 120 MB. With one OCR job at a time (O2) and a 1.2 GB heap that's safe. Add a test that decodes a 40 MP image under a heap limit (see 12.2).

### 6.2 Running Tesseract (`TesseractCliEngine`)

- **Command:** `ProcessBuilder` with an argument list, never a shell string: `tesseract <png> stdout -l eng --psm <psm> --dpi 300 tsv`.
- **Environment:**
  - `OMP_THREAD_LIMIT=1` (one thread, so the JVM keeps a CPU on the 2-vCPU box). Measure with 2 and report.
  - `TESSDATA_PREFIX` only if `ocr.tessdata-dir` is set.
- **Output:** read stdout on a separate thread, capped at `ocr.max-output-bytes` (2 MB). Over the cap, kill the process and fail the page. Read stderr into a small buffer that is **never logged**. Only its length and the exit code are logged.
- **Timeout:** `waitFor(timeout)`. On timeout, `destroyForcibly()`, wait briefly, and fail with a timeout exception.
- **Exit codes:** a non-zero exit fails the page.
- **Logging:** only `ocr page done: ms, words, meanConfidence, exit`. Never the text, never a path, never stderr.

### 6.3 Rebuilding lines from TSV (`TsvLineBuilder`)

- **Rows:** keep level-5 (word) rows with non-empty text and `conf >= 0`.
- **Grouping:** group by `(block, par, line)` in TSV order. Within a line, sort by `left`.
- **Joining words:** join with one space, or with **three** spaces when the horizontal gap is more than `2 × median character width` of the line.
  - **Why:** this keeps table columns apart, so `ResultRowDetector` still sees value, unit and range as separate tokens.
- **Text:** lines joined with `\n`, paragraphs with a blank line.
- **Confidence:** record each word's character range in the built text, for `ConfidenceIndex`.
- **Tests:** table-driven, with hand-made TSV fixtures. Cover a one-line table row, a wide gap, two paragraphs, empty words, `conf = -1` rows, and Unicode (`µ`, `≤`).

### 6.4 Scanned and mixed PDFs (`PdfTextExtractor` change)

- **Text layer first:** extract the text layer per page as in Phase 2.
- **Low-text pages** (`< extract.min-chars-per-page` characters):
  - **Limit:** if their count is over `ocr.max-pages`, fail with `DOCUMENT_TOO_LONG`.
  - **Render:** each one with PDFBox `renderImageWithDPI(page, 300, ImageType.GRAY)`. Check the 3.x API in Context7.
  - **OCR:** write a temp PNG, then OCR it under the gate.
  - **Clean up:** release the image and delete the PNG.
- **Order:** keep pages in their original order. Each OCR'd page replaces that page's (empty) text.
- **`textSource`:**
  - `TEXT_LAYER` if no page was OCR'd
  - `OCR` if every page with text was OCR'd
  - `MIXED` otherwise
- **Total length rules:** `min-total-chars` and `max-text-chars` apply after OCR, as before.
- **Very low confidence:** an OCR page with `meanConfidence < ocr.min-page-confidence` (30) counts as empty. This avoids feeding garbage to the model.

### 6.5 Gate and budget (`OcrGate`)

- **The semaphore** (1 permit) is held only around one page's OCR call. Rendering and preparing happen outside it, but are bounded by the job pool (2 workers).
- **Waiting for the permit** counts toward the report's `ocr.total-timeout`. When the budget runs out, the job fails with `EXTRACTION_FAILED`, and the remaining pages are skipped.
- **Stats:** the wait time and the OCR time are recorded as numbers only (`daily_processing` already records the total per source type).

### 6.6 Upload layer change

- **Validation step 6** ("Extractor available") now finds `ImageOcrExtractor` for PNG and JPEG when `ocr.enabled=true`.
- **When `ocr.enabled=false`:** images still get `415 UNSUPPORTED_FILE_TYPE`, as in Phase 2.
- **`sourceType`:** `IMAGE` for images (already in the contract).

---

## 7. The low-confidence rule (`BiomarkerValidator`)

The rule runs after the existing support check (the `rawValue` must appear in the masked text) and value parsing. For a report whose `textSource` is `OCR` or `MIXED`:

1. Find every whole-token occurrence of `rawValue` on a result row (same matching as the support check). Take the **minimum** confidence of the OCR words that overlap those occurrences. Occurrences in text-layer pages count as 100.
2. If that minimum is below `ocr.min-value-confidence`, then:
   - set `flag = UNKNOWN` and `numericValue = null`
   - keep `rawValue`, `unit` and the range (the user can still see what was read)
   - count the row as `lowConfidenceValues` (a number in the logs and in `daily_stats`, section 8)
3. Apply the same rule to the **reference range**. If the numbers in `referenceRangeText` have confidence below the threshold, the range is treated as not parseable: `refLow` and `refHigh` become null and the flag becomes `UNKNOWN`.
4. **Collection date:** if the `collectedOn` string comes from low-confidence words, use `null`.

Text-layer and pasted reports are unchanged. Unit tests use a fake `ConfidenceIndex`.

---

## 8. Stats

- New counter in `daily_stats`, in the same migration as section 4:

  ```sql
  ALTER TABLE daily_stats ADD COLUMN ocr_low_confidence_values integer NOT NULL DEFAULT 0;
  ```

  It is a count only. The admin endpoint doesn't expose it yet (that would change the contract). Phase 7d can add it.
- `daily_processing` starts getting `IMAGE` rows, so the admin stats show `IMAGE` without code changes (6b D3).

---

## 9. Frontend changes (small)

1. **`NEXT_PUBLIC_FEATURE_OCR=true`** in `.env.example` and local env. The 7b copy switches to the OCR-on versions (7b LLD 4.4). The photo tile and image picking now send the file.
2. **Client checks:** PNG and JPEG are accepted. The 10 MB check stays. There's no pixel check in the browser (the server decides).
3. **OCR note on results:** when `result.textSource` is `OCR` or `MIXED` (or unknown), show an `info` `InlineNotice` above the results table:
   - **Title:** "Read from a photo or scan."
   - **Body:** "OCR can misread numbers. Check each value against your report. Values we weren't sure about are marked unclear."
   - **`MIXED`:** the title becomes "Some pages were read from a scan."
4. **Unclear rows:** rows with flag `UNKNOWN` already show the neutral dash. No change.
5. **Types:** `api-types.ts` gets `textSource` (section 3.1).
6. **Tests:**
   - unit tests for the note (`TEXT_LAYER`: no note; `OCR`, `MIXED` and unknown: the right text)
   - an e2e test uploading a PNG with the backend mocked
   - the 7b copy-switch test with the flag on

---

## 10. Evaluation (extends Phase 3)

### 10.1 New cases (generated at test time, not committed as binaries)

`OcrCaseRenderer` (test sources) builds images and scanned PDFs from **existing** case texts, so the answer keys are reused unchanged:

| Variant | How |
|---|---|
| `clean-scan` | Render the text at 300 DPI, black on white, monospace and proportional fonts. |
| `phone-photo` | A slight rotation (±3°), uneven lighting gradient, mild blur, JPEG quality 70. |
| `low-res` | A 150 DPI equivalent, then scaled up. |
| `rotated-exif` | Saved rotated 90° with an EXIF orientation tag (tests O8). |
| `scanned-pdf` | The `clean-scan` images wrapped as a PDF with no text layer. |
| `mixed-pdf` | Page 1 with a text layer, page 2 scanned. |

- **Which cases:** apply the variants to about 10 existing cases (at least lipid, CBC, CMP, thyroid, comparators, number-formats, boundary-values, flags-as-asterisks, and the two `pii-*` cases). That gives roughly 40 to 60 OCR runs.
- **Answer keys:** same as the text case. `textSource` is expected to be `OCR` (or `MIXED`).
- **Fonts and seeds:** use fonts that ship with the JDK or are committed under an open licence. Fix the random seed so every image is reproducible.

### 10.2 Metrics added

- **All Phase 3 metrics**, reported separately for OCR cases and text cases.
- **Dangerous flag errors on OCR cases: target 0.** This is the gate for the phase.
- **Unclear rate on OCR cases:** the share of rows turned `UNKNOWN` by the low-confidence rule. That's the cost of safety. Report it, but it doesn't gate.
- **Value accuracy on matched rows** (the `rawValue` exactly equals the key) for OCR cases.
- **Leaks on OCR cases:** OCR can garble a name so that masking misses it. Report by type, against the same zero target as text cases.
- **OCR time** per page and per report (p50 and max), and the share of reports over 120 s.

### 10.3 Tuning runs

Run OCR-only scoring (no LLM calls needed for OCR text quality, so the free quota is safe):
- with `--psm` 3, 4 and 6
- with the default tessdata and `tessdata_best` (if `best` is used, it's installed in the image, about 15 MB)
- with `ocr.min-value-confidence` at 70, 80 and 90

Use the extraction cache from Phase 3 where the OCR text is identical. Show me the table. I choose the settings, then you run the full eval with the LLM once.

### 10.4 Done gate

- **Dangerous flag errors on OCR cases = 0.**
- **In-scope leaks on OCR cases = 0**, or each one listed with a proposed fix for me to decide.
- **Text-case metrics** no worse than the Phase 3 baseline.
- **Average OCR time** per report under 60 s for one-page images on the local machine. Report what the ARM box would likely do (measure on the arm64 image under `docker run --cpus=2 --memory=2g` if possible).

---

## 11. Configuration

| Property | Env var | Default | Purpose |
|---|---|---|---|
| `ocr.enabled` | `OCR_ENABLED` | `true` (`prod`), `true` (`local` if tesseract found, else `false` with a WARN) | Turns image upload and the scanned-page fallback on |
| `ocr.command` | `OCR_COMMAND` | `tesseract` | Program path |
| `ocr.tessdata-dir` | `OCR_TESSDATA_DIR` | blank | Set to use `tessdata_best` |
| `ocr.psm` | `OCR_PSM` | 4 (until the evaluation picks) | Page segmentation mode |
| `ocr.threads` | none | 1 | `OMP_THREAD_LIMIT` |
| `ocr.page-timeout` | none | 45 s | Per page |
| `ocr.total-timeout` | none | 150 s | Per report, including the wait for the permit |
| `ocr.max-pages` | none | 10 | Scanned pages per report |
| `ocr.max-pixels` | none | 40,000,000 | Image size cap |
| `ocr.max-output-bytes` | none | 2 MB | Stdout cap |
| `ocr.min-page-confidence` | none | 30 | Below this, the page counts as empty |
| `ocr.min-value-confidence` | none | 80 (until the evaluation picks) | The low-confidence rule |

- **`prod` profile:** startup fails if `ocr.enabled=true` and the startup check fails.
- **`local` profile:** a missing Tesseract means a WARN and OCR off.

---

## 12. Tests

### 12.1 Unit (no Tesseract needed, `FakeOcrEngine`)

1. **`TsvLineBuilder`:** the table from 6.3.
2. **`ImagePreparer`:**
   - header-only size check (a crafted PNG header claiming 50,000 × 50,000 is rejected **without decoding**)
   - EXIF rotation for orientations 1, 3, 6 and 8
   - scaling limits
   - grayscale output
   - temp files deleted on success and on failure
3. **`TesseractCliEngine`** with a fake executable (a small script in test resources that sleeps, prints too much, exits 1, or prints valid TSV):
   - the timeout kills the process
   - the output cap
   - non-zero exit
   - arguments passed as a list (assert no shell)
   - stderr never in logs
4. **`OcrGate`:**
   - one permit
   - the total budget counts waiting time (fake clock)
   - the permit is always released (on exceptions too)
5. **`PdfTextExtractor`:**
   - text-only, scanned-only and mixed PDFs give the right `textSource`
   - more than `max-pages` low-text pages gives `DOCUMENT_TOO_LONG`
   - a low-confidence page counts as empty
6. **Low-confidence rule:**
   - a value under the threshold becomes `UNKNOWN` with `numericValue` null
   - the minimum over several occurrences
   - a low-confidence range
   - a low-confidence date
   - text-layer reports untouched
7. **Masking with OCR text:** the offset or lookup approach (section 5) gives the right confidence after masking shifts the text.
8. **Mapper:** `textSource` is returned, and null maps to `TEXT_LAYER` for old reports.

### 12.2 Integration (real Tesseract, tagged `ocr`)

- **Where they run:** they need `tesseract` with `eng`. They run in `./mvnw verify` when Tesseract is found, and are skipped with a clear message otherwise.
- **Docker profile:** add a profile or script that runs them inside the backend image, so they can always be run.
- **Tests:**
  1. A rendered lipid-panel PNG goes through the full pipeline (fake LLM) and reaches `DONE`, with `textSource=OCR`.
  2. A scanned PDF reaches `DONE` with `OCR`. A mixed PDF reaches `DONE` with `MIXED`.
  3. A blank image ends `FAILED` with `UNREADABLE`.
  4. A 41 MP PNG ends `UNREADABLE`, and the heap never decodes it (assert by running with a small `-Xmx` in a forked test, or by checking that the preparer rejects it before `read`).
  5. **Canary:** an image containing the canary name and ID. The canary appears nowhere in logs, the database, or the LLM prompt (if the masker catches it). If OCR garbles it so masking misses it, the test reports this and fails. That's a real finding.
  6. **Two image reports submitted together:** OCR runs one at a time (record start and end times in `FakeOcrEngine`, or use real Tesseract with timing).
  7. Temp dir is empty after every test.

### 12.3 Frontend

The tests in section 9.

---

## 13. Docker image

- **Base image:** keep the Phase 1 Debian-based JDK 21 image.
- **Install:** `apt-get install --no-install-recommends tesseract-ocr tesseract-ocr-eng`, then clean the apt lists. Check the Tesseract version Debian ships (5.x expected) and report it.
- **`tessdata_best`:** if the evaluation chooses it, download only `eng.traineddata` from the official tessdata_best repository at a **pinned commit**, verify a SHA-256 in the Dockerfile, and set `OCR_TESSDATA_DIR`.
- **Build:** `docker buildx build --platform linux/arm64` must still succeed. Report the image size before and after.
- **Startup:** the container logs the Tesseract version at startup.

---

## 14. Done checklist

1. **Docs and contract** updated first, and `api-types.ts` matches.
2. **Tests:** `./mvnw verify` passes. The `ocr` integration tests pass with real Tesseract, locally or in the Docker profile.
3. **Client:** `lint`, `typecheck`, `test`, `build` and `e2e` pass.
4. **Evaluation:** the tuning table and the full run are done. The gate in 10.4 is met, or every miss is listed for me.
5. **Manual run in the browser** (local backend, fake LLM, and the real model if the key is set):
   - a phone photo of a printed synthetic report (print one of the samples)
   - a scanned PDF
   - a mixed PDF
   
   All reach `DONE` with the OCR note. Values that are wrong or unclear are listed.
6. **Log check:** no OCR text, no file names, no stderr in the logs.
7. **ARM image** builds. Size and Tesseract version reported.

---

## 15. Approved deviations from this design

Recorded as the 6a and 6b LLDs record theirs: what the design said, what the code does, and why. The code cites these numbers in its comments, so the numbers are load-bearing.

| # | Design as first written | What the implementation does | Why | Section |
|---|---|---|---|---|
| D7 | A `rawValue`'s confidence is the minimum over the OCR words overlapping any occurrence of it **on a result row** (section 5, last paragraph). A value found on no result row has no confidence. | Result rows are searched first and win. If the value appears on no result row, **every** line is searched as a fallback, and that confidence is used. Only a value found on no OCR line at all returns nothing — and the caller treats *that* as doubtful, not trusted, counting it as `unlocatedValues`. | Two gaps in the original rule, both failing unsafely. A value printed on a line `ResultRowDetector` does not classify as a result row (a one-off result in prose, a footnote repeating a figure) would have had *no* confidence, and "no confidence" had no defined behaviour; the natural reading — leave the flag alone — is exactly backwards, because an unlocated value is one we know least about. A measured confidence from a non-result line is strictly better informed than no information, and a value that cannot be located anywhere means the support check and the confidence lookup have diverged, which is a bug in our code rather than a property of the report. Counting it makes that visible instead of silently switching the rule off for the affected rows. | 5, 7 |

**Section 5's open choice, resolved.** The design left the masking-offset problem open ("pick the simpler correct approach after reading the Phase 2 masker code, and show it at approval point 2"). The choice is the **line-based lookup**, not an offset map threaded through the masker: `ResultRowDetector` protects whole lines and `Masker` refuses any span overlapping a protected one, so a result row is byte-identical before and after masking. Searching for a value inside a line therefore needs no translation between the raw and masked texts at all. `ConfidenceIndexTest.survivesMasking` runs the real masker over real OCR output to check that claim rather than assuming it.

**A gap in this record.** D1–D6 were agreed in a working session whose transcript was lost before they were written down, so their numbers are reserved here rather than reconstructed — D7 keeps its number so the citation in `ConfidenceIndex` stays accurate. The deviations the committed code visibly contains, any of which may be among them, are: the subsampled decode and the `ocr.max-decoded-bytes` / `min-long-edge` / `max-long-edge` properties that section 6.1 and section 11 do not list; EXIF orientation read with the JDK alone, so this phase adds no dependency (section 6.1 step 2 asked before adding one), with the mirrored orientations 2, 4, 5 and 7 treated as upright; `ValueTokenMatcher` extracted so the support check and the confidence lookup cannot disagree about what an occurrence is; and the mapping from an OCR failure reason to a report error code (a non-zero exit is `UNREADABLE`, while a timeout, an output-cap kill or an exhausted budget is `EXTRACTION_FAILED`). These need renumbering once their original numbers are confirmed.

---

## 16. Open items for the final report

Answered as of 2026-11-02. The numbers are in `docs/evaluation.md` ("Phase 4: the OCR sweep") and `eval/results/ocr-tuning-*.md`; everything found along the way that is not fixed inline is in `docs/phase-4-findings.md`.

- **The chosen `--psm`, model, and confidence threshold, with the tuning table.** `--psm 4`, the system `eng` model, threshold **80**. The table is in `eval/results/`; psm 3 was worse on every variant, psm 6 tied with 4 on accuracy but assumes a uniform block. The threshold is the one open risk: 90 drives the dangerous-candidate share to zero on every variant and 80 does not. 80 was chosen anyway, for a fifth of the unclear-rate cost, and that is recorded as accepted risk rather than a clean result.
- **`tessdata_best` was not measured.** It is not installed, and LLD 10.3 lists it as a variable to sweep. Still open: install it and re-run the sweep if the unclear rate or the comparator misreads turn out to matter in practice.
- **The OCR evaluation numbers.** The OCR-only sweep is complete. The full-pipeline OCR gate (`OcrEvalRunner`) is written and runs, but the Gemini free tier allows ~20 requests a day against the ~20 this needs, so it has not yet had a complete clean run — see the report for the status of the run that was made.
- **OCR time per page and report.** About 0.7–1.3 s per page locally (Tesseract 5.4.0, 300 DPI Letter page, one thread). **Not yet measured in the 2-CPU / 2 GB container** — still open, and the figure that matters for the 150 s per-report budget.
- **Whether EXIF needed a new library.** No. The orientation tag is read from the JPEG's APP1 segment through the JDK's own metadata tree, so this phase adds no dependency. `OcrCaseRendererTest` round-trips the payload through the same parser production uses.
- **The image (section 13), measured 2026-11-02.** `docker buildx build --platform linux/arm64` succeeds. The image ships **Tesseract 5.3.4** with leptonica 1.82.0 (Ubuntu 24.04 "noble"), `eng` present, and `TESSDATA_PREFIX=/usr/share/tesseract-ocr/5/tessdata` matches where the package actually puts the model — both confirmed by running `tesseract --version` and `--list-langs` inside the built arm64 image. Dropping `libtesseract-dev`, which was only ever there for the Tess4J binding O1 replaced, removes **24 packages** (a whole dev toolchain: `libcurl4-openssl-dev`, `libarchive-dev`, `nettle-dev`, `libleptonica-dev`, …) and **31.7 MB**: measured like-for-like as two minimal arm64 images off the same base, 164.6 MB with the package against 132.9 MB without.
- **Anything that should change before Phase 5 (chat).** Two things. First, OCR loses roughly one value in ten even on a clean scan, and chat answers are grounded in the stored report — a chat answer about a value that OCR dropped will be answering about a report the reader can see has that value. Second, Tesseract's global binarisation loses the darker half of an unevenly lit page (see the evaluation doc); until that is addressed, a photographed report can reach chat with half its rows missing and nothing in the data saying so.