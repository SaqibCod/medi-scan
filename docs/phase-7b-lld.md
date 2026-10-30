# Phase 7b: Landing, upload, report page, biomarker pages (low-level design)

Frontend only (`client/`). No backend changes. Branch: `feat/phase-7b-core-flow`, created from `dev`.

This phase makes the core feature work in the browser. A guest can:
- upload a text PDF, paste text, or pick a sample
- watch the report being processed
- read the results
- open a biomarker page
- delete the report

It builds on 7a: tokens, components, shell, API client, guest session, status, notices, and system screens.

**Overall order (unchanged):** 7a → **7b** → Phase 4 (OCR) → Phase 5 (chat) → 7c → 7d → Phase 8 (deploy). There is no early deploy.

**References**
- `docs/ui/screens/`: `landing-page--*`, `report-page--*` (not the chat parts), `content-pages--01` and `--03` (biomarker explainer), and `system-screens--07` and `--10` (upload errors).
- `docs/design-system/`: tokens, components, and voice rules.
- `docs/api-contract.md`: 3 (samples), 4.1 to 4.3 (reports), 8 (codes), 9 (types and compatibility rules).
- `docs/phase-7a-lld.md`: the foundation this phase uses.

---

## 1. Scope

**In scope**
1. **Landing page (`/`):** hero, upload panel, facts, how it works, samples, results preview, marquee, privacy section, footer.
2. **Creating a report:** from a PDF, pasted text, or a sample, with consent, client checks, and every upload error.
3. **Report page (`/reports/[id]`):** the processing, done, and failed states. Delete.
4. **Biomarker pages (`/biomarkers/[slug]`)** from markdown, a simple `/biomarkers` index, and the "On your report" box.
5. `sitemap.xml` and `robots.txt`.
6. Docs fixes carried over from 7a (section 12).

**Out of scope**
- Chat UI (Phase 5). The chat panel and "Ask about this report" are not shown at all.
- Photos and OCR (Phase 4). Image files show the "Photos are coming soon" notice and are never sent.
- Sign-in, My reports, trends, account (7c). Admin (7d).
- Any backend change, contract change, or new biomarker slug.

---

## 2. Decisions (already made, confirm at the approval point)

| # | Decision | Reason |
|---|---|---|
| B1 | **Every page stays dynamically rendered** under the nonce CSP, including biomarker pages. Markdown is read once per server instance and cached in memory. | Tokens live in the browser for the whole origin, so every page needs the strict CSP. 10 to 20 pages per request is cheap. |
| B2 | **Report route is `/reports/[id]`** (the plan said `report/[id]`). `/reports` becomes My reports in 7c. | One URL family. Update the plan. |
| B3 | **No fake progress.** The backend only reports `PENDING` or `PROCESSING`, so the processing page shows the five steps as an explanation, with a real status tag (`QUEUED` or `PROCESSING`) and an indeterminate progress line. No step lights up on a timer. | Honest UI. |
| B4 | **Copy that promises photos or OCR is switched by `NEXT_PUBLIC_FEATURE_OCR`** (default `false`). Every such string lives in one file, `lib/copy.ts`, with an OCR-on and an OCR-off version (section 4.4). Phase 4 flips the flag. | No promise the app can't keep, and a one-line switch later. |
| B5 | **Image files are refused in the browser** while OCR is off. Picking a PNG or JPEG (or using "Take a photo") shows the "Photos are coming soon" notice with "Paste text instead". Nothing is sent. | Matches the mockup, and saves a pointless 415. |
| B6 | **A sample is chosen, then sent.** "Try it" selects the sample. The upload panel switches to show it, and the user ticks consent and presses "Analyze report". | The contract needs `consent: true` for samples too. Matches the phone mockup ("Selected" plus the sticky Analyze button). |
| B7 | **No chat column yet.** Until Phase 5, the desktop results layout is a single centred column (max 880 px) instead of two columns. | No empty or fake panel. |
| B8 | **Highlight chips are derived.** Each summary highlight gets the flag chip of the flagged biomarker whose `testName` it contains (case-insensitive, longest name wins). If none match, no chip. | Highlights are plain strings in the contract. The backend only keeps highlights that name a flagged marker. |
| B9 | **The failed-state code line shows the report id,** not a request id: `UNREADABLE · report 6f1c2a9e` (first 8 characters). | A job failure has no request id. The report id is what helps debugging. |
| B10 | **"My reports" and every sign-in action stay hidden** until 7c (flags from 7a). | `/reports` doesn't exist yet. |
| B11 | **Biomarker content is drafted from reputable public sources, paraphrased, with sources listed. It is marked unreviewed until I review it** (section 7.4). Content never states numeric reference ranges. | Medical content needs a human check. Ranges differ between labs, and the design says to always use the one on the report. |

---

## 3. Routes and files

```text
app/
  page.tsx                          landing (replaces the 7a temporary page)
  reports/[id]/page.tsx             report page shell (client data)
  biomarkers/page.tsx               simple index
  biomarkers/[slug]/page.tsx        explainer
  sitemap.ts, robots.ts
components/
  landing/        Hero, UploadPanel, FactsRow, HowItWorks, SamplesSection, ResultsPreview, PrivacySection, StickyAnalyzeBar
  upload/         SourceTabs, FilePicker, PasteBox, SelectedSample, ConsentCheck, UploadErrorNotice
  report/         ReportHeader, GuestExpiryNote, ProcessingView, ResultsView, SummaryCard, CountTiles, ResultsTable, FailedView, DeleteReportDialog
  biomarker/      ExplainerHeader, OnYourReportBox, FactsGrid, OnThisPage, MarkdownBody, RelatedTests
lib/
  copy.ts                           every user-facing string with a phase switch (B4)
  upload-source.ts                  source state and client checks
  reports.ts                        createReport, getReport, deleteReport, the query keys and hooks
  report-format.ts                  dates, "Deleted in", value + unit, highlight → flag
  biomarker-content.ts              markdown loading, frontmatter validation, cache
content/biomarkers/
  index.json                        exists (synced with the backend copy, do not add slugs)
  <slug>.md                         one per slug in index.json
```

Keep the names unless there's a real reason. Say so at approval point 2 if the repo differs.

---

## 4. Landing page (`/`)

Match `landing-page--01` to `--04` (desktop and phone, both themes). Sections in order:

1. **Hero:**
   - Eyebrow "AI lab report reader".
   - Headline "Your lab report, / in plain language." (second half `faint`), and the lead paragraph.
   - "Upload a report" (solid) scrolls to and focuses the upload panel. "Try a sample report" (outline) scrolls to Samples.
   - The Helix on the right (desktop) or behind the headline (phone).
   - "Scroll for more" helper on desktop.
   - The section index ("Intro, How it works, Samples, Results, Privacy") from the mockup: anchor links.
2. **Upload panel** (`id="upload"`, glass `panel` card, "01 · Start here · Analyze a report"). Section 5.
3. **Facts row:** "Built to make lab results readable, and to forget them when you're done." Four `StatFigure`s. The labels follow B4 (for example "Max upload, as a PDF" while OCR is off).
4. **How it works** (`id="how"`): the `StepTimeline` with six steps (Upload, Read, Mask, Extract, Flag, Explain).
   - Desktop: horizontal, with chevron buttons and the active step's body above.
   - Phone: vertical.
   - Step bodies come from the mockups. Use the report-page wording for Read to Explain, with the B4 switch.
5. **Samples** (`id="samples"`): "No report handy? / Try a sample." `SampleCard`s from `GET /api/samples`.
   - Desktop: three across. Phone: a swipe row with pager dots.
   - Loading: three skeleton cards.
   - **Error:** an inline notice and a retry. The rest of the page still works. While the server wakes, the 7a banner explains.
   - Card numbers are `01`, `02`, … in API order. The markers tag is "{markerCount} markers".
6. **Results preview** (`id="results"`): "What you get" with A, B, C, and the static example card (Lipid panel).
   - **The band carries its own ground, not a `Card`.** It is one of the two gradients the design system allows (`docs/design-system/README.md`, "Colour"): a faint radial glow of `accent` and `helix-cyan` over the page ground. The mockup holds it in a `--preview-bg` token, which 7a never carried into `tokens.json` — added there, so it is generated like every other colour. Apply it with the `background` shorthand: the token's last layer is a solid colour, which `background-image` rejects, dropping the whole declaration silently.
   - A/B/C are hairline-ruled rows, and the chat bubbles sit in their own card beside the results card.
   - Take the example values from the sample in the mockup, and label the card "Example".
   - It is a picture of the output, not live data. The "Why is my LDL flagged?" chat bubbles are kept as an illustration of Phase 5, inside the example card only.
7. **Marquee:** "Plain language / Private by default / Every value flagged / No account needed" (`aria-hidden`). Each phrase must appear as real text elsewhere on the page.
8. **Privacy** (`id="privacy"`): the four cards from the mockup, and a link to `/privacy`.
9. **Footer.** 7a shipped a one-line version; the mockup's is the real one and 7b builds it: the "portfolio project … always talk to a doctor" paragraph, the nav, and the giant faded `MEDI·SCAN` wordmark clipped to a `foot-top` → `bg` fade. That is the second of the two allowed gradients, and `--foot-top` existed as a token with no other user. The wordmark is `aria-hidden`: it is the brand as scenery, and announcing it again at the end of every page is noise.

**Phone extras:**
- **Sticky bar:** a full-width accent "Analyze report" bar (`shadow-cta`) at the bottom.
- **When it shows:** once the upload panel has scrolled out of view. Tapping it scrolls back to the panel.
- **Selected source:** if a source is selected and consent is ticked, it submits directly.
- **Toasts:** they stay above it.

**Metadata:** title "Medi-Scan · Your lab report in plain language", a description, and Open Graph tags (text only, no images needed).

### 4.4 Copy switched by `NEXT_PUBLIC_FEATURE_OCR` (B4)

| Place | OCR off (now) | OCR on (Phase 4) |
|---|---|---|
| Drop zone hint | "PDF — up to 10 MB · photos coming soon" | "PDF · PNG · JPEG — up to 10 MB" |
| Fact label | "Max upload, as a PDF" | "Max upload, as PDF or a photo" |
| Step 01 Upload (landing) | "Drop a text PDF, paste the text, or pick a sample." | mockup text (mentions photo) |
| Step Read (landing and processing) | "Pull the text out of your file." | "Pull the text out of your file, with OCR for photos and scans." |
| Failed `UNREADABLE` body | "No usable text came out of this file. Scanned pages and photos need OCR, which is coming soon. A PDF from your lab or pasted text works best." | mockup text ("…even after OCR. Try a clearer photo…") |
| Phone "Take a photo" tile | shown. Using it shows the "Photos are coming soon" notice | opens the camera |

---

## 5. Upload panel and creating a report

### 5.1 Source state (`lib/upload-source.ts`)

```ts
type Source =
  | { kind: 'none' }
  | { kind: 'file'; file: File }
  | { kind: 'text'; text: string }
  | { kind: 'sample'; sample: Sample };
```

- **Tabs:** "Upload file" and "Paste text" (`Tabs`, `block` on phones). A selected sample replaces the tab content with `SelectedSample`: "Sample: Lipid panel · 5 markers", plus a "Change" link that clears it.
- **Switching** tabs or sources clears the previous source and any upload error. Consent stays ticked.
- **Consent:** the checkbox with the mockup label, which links to `/privacy`. "Analyze report" (accent) stays disabled until a valid source exists and consent is ticked. A short hint under the button says what's missing.
- **Paste box:** mono `data` style, 20 to 50,000 characters, a live counter (for example "1,240 / 50,000"), and a placeholder that shows the expected shape. Don't trim inside the text. Only whole-text whitespace counts toward the length checks.

### 5.2 Client checks (before sending)

| Check | Result |
|---|---|
| File over 10 MB (10 × 1024 × 1024 bytes) | "Too large" notice with the real size. Not sent. |
| PNG or JPEG while OCR is off (by MIME type or extension) | "Photo (not yet)" notice (B5). Not sent. |
| Anything other than PDF, PNG or JPEG | "Wrong type" notice. Not sent. |
| Empty file (0 bytes) | "Wrong type" notice. Not sent. |
| Text shorter than 20 or longer than 50,000 characters | "Text length" notice (short or long variant). Not sent. |

The server still checks everything. Client checks are for speed only. The file signature check stays server-side (`FILE_SIGNATURE_MISMATCH`).

**Drag and drop:** dropping several files uses the first and shows "Only one file at a time." as an `info` notice.

### 5.3 Sending

- `createReport(source)` in `lib/reports.ts`:
  - **File:** `multipart/form-data` with `file` and `consent=true`.
  - **Text and sample:** JSON with `consent: true`.
  - All with `auth: 'guest'` (7c changes it to `'auto'`).
- **While sending:** the button shows "Sending…", and the panel is disabled. No double submit.
- **On `202`:** push to `/reports/{id}`, and seed the query cache with the `CreateReportResponse` as a `PENDING` report, so the page draws at once.
- **`SESSION_INVALID`:** the 7a guest session logic creates a new session and retries once.
- **The file stays in memory** only as the `File` object. It's never read into storage, never logged.

### 5.4 Upload errors (inline, under the drop zone)

`UploadErrorNotice` uses the 7a `InlineNotice`. It shows the code line in `meta` style ("413 FILE_TOO_LARGE"). The copy is exactly from the mockup:

| Case | Title | Body | Action |
|---|---|---|---|
| `FILE_TOO_LARGE` | "This file is too large." | "It is {size} MB and the limit is 10 MB. Try exporting only the pages with your results." | — |
| Photo, OCR off (B5) | "Photos are coming soon." | "For now Medi-Scan reads PDFs that contain text, pasted text, and samples. Paste the text from your report instead." | "Paste text instead" (switches tab) |
| `UNSUPPORTED_FILE_TYPE` | "We can't read this kind of file." | "Upload a PDF, PNG or JPEG, or paste the text of your report." (OCR off: "Upload a PDF, or paste the text of your report.") | — |
| `FILE_SIGNATURE_MISMATCH` | "This file isn't really a PDF." | "Its contents do not match its name. It may have been renamed or damaged. Try saving it again from the original app." | — |
| Text too short | "That text is too short to read." | "Paste at least 20 characters, including the test names, values and ranges." | — |
| Text too long | "That text is too long." | "Paste up to 50,000 characters. Try only the part with your results." | — |
| `RATE_LIMITED` | "You've reached this hour's upload limit." | "You can upload 10 reports an hour. Try again in {countdown}." | — (button stays disabled until the countdown ends) |
| `BUSY` | "Lots of reports are being read right now." | "Your report was not sent. Try again in a minute." | "Try again" |
| `CAPACITY` | The 7a daily-limit banner shows, plus an inline notice: "The demo has hit today's AI limit." / "New reports open again at midnight UTC. Your saved reports still open." | — |
| `SAMPLE_NOT_FOUND` | "That sample isn't available." | "Pick another sample, or upload your own report." (and refetch samples) | — |
| `VALIDATION_ERROR` (other) | "Something in the request wasn't right." | The field names from `errors`, mapped to plain words. | — |
| Network or timeout | The 7a waking state, plus "Your report was not sent. Try again when the server is back." | "Try again" |
| 5xx | "Something broke on our side." | "Try again in a moment. Request ID {id}." with "Copy ID" | "Try again" |

- **Countdown:** `{countdown}` comes from `Retry-After` and is formatted as "17 min 51 s". It ticks every second.
- **Accessibility:** notices are announced with `role="alert"`, and focus moves to the notice.

---

## 6. Report page (`/reports/[id]`)

### 6.1 Loading and polling (`lib/reports.ts`)

- **Query:** `useReport(id)` is a TanStack Query on `['reports', id]`, calling `GET /api/reports/{id}` with `auth: 'guest'`.
- **Invalid id:** if `id` isn't a UUID, show the 404 page. Don't call the API.
- **Polling:**
  - Every 1.5 seconds while `status` is `PENDING` or `PROCESSING`, or an unknown value (contract 9.2).
  - Stops at `DONE` or `FAILED`.
  - Pauses while the tab is hidden, and refetches once when it is shown again.
- **Time limit:** after 2 minutes of polling, show "This is taking longer than usual." with "Keep waiting" (2 more minutes) and "Cancel and delete". Never poll forever.
- **Errors:**
  - `REPORT_NOT_FOUND` shows 7a's "Report not available" screen.
  - `SESSION_INVALID` shows "Guest session ended".
  - Network errors show the 7a waking state, and polling continues with the query's backoff. After the 7a down threshold, show the 503 screen.
  - Any other error shows the 500 screen with the request id.
- **No caching of results** outside memory: no `persistQueryClient`, no storage.
- **Page title:** "Your report · Medi-Scan" in every state. No test names or values in titles or URLs.

### 6.2 Processing view (`report-page--02`, `--05`, `--08`)

- Eyebrow "Report · {sourceType} · uploaded {time}".
- Headline "Reading your report. / This page updates itself."
- Status tag `QUEUED` (`PENDING`) or `PROCESSING`.
- The five steps (Read, Mask, Extract, Flag, Explain) as a numbered list with their bodies (B3, B4). An indeterminate progress line runs under the tag. It stops under reduced motion and becomes a static "Working…".
- "Cancel and delete" (outline) opens the delete dialog (6.5).
- Note: "Keep this tab open. If it takes longer than 2 minutes, we'll tell you here."
- **Screen readers:** a polite live region announces only the state changes (queued → processing → done or failed), not every poll.

### 6.3 Done view (`report-page--01`, `--04`, `--06`, `--10`, without chat)

**Header**
- Eyebrow "Your results". Headline "Your report, / explained."
- Tags: the source type (`PDF`, `TEXT` as "Pasted text", `SAMPLE` as "Sample"), "Collected {28 Sep 2026}" or "Collection date not found", "Uploaded {2 Oct 2026, 09:31}", and "Deleted in {23 h}".
- "Delete report" with the `trash` icon.

**Dates**
- `collectedOn` is a plain date. Format it without converting time zones (no shifting a day).
- `createdAt` is shown in the browser's time zone.
- "Deleted in": under 1 hour shows minutes, under 48 hours shows hours, otherwise days.

**Guest note**
- "Guest report: it's deleted automatically in {23 h 12 min}." for guests.
- The "Sign in to keep it" action is hidden until 7c (B10).
- The countdown updates every minute.
- When it reaches zero, the next fetch returns 404, which shows "Report not available".

**Summary card**
- Eyebrow "Summary", then the summary text as plain text: React text nodes, with no markdown and no HTML.
- The highlights below it, each with its derived chip (B8).
- If there are no highlights, the highlights block is not shown.

**Count tiles**
- All, High, Normal, Low, Unclear, with numbers from `result.counts`. Unclear is `unknown`.
- They work as a filter: one selected at a time, a pressed state, and the matching rows shown.
- The table caption reads "{label} · {n} of {total}".
- **Phones:** the compact tile row from the mockup.

**Results table**
- Columns: Test, Result, Range, Flag (`BiomarkerRow`; `compact` on phones), in API order.
- **Test name:** links to `/biomarkers/{slug}?report={id}&b={biomarkerId}` when `biomarkerSlug` is set and a page exists. Otherwise it is plain text.
- **Result:** `rawValue` in mono, and the `unit` in `ink-3` when present. Qualitative values show as text.
- **Range:** `referenceRangeText` or "—".
- **Flag:** `FlagChip`. Unknown values show as UNKNOWN (contract 9.2).
- **Note under the table:** "Ranges come from your report. Labs use different ranges, so always compare with the one printed on yours."

**Disclaimer** under everything: "Medi-Scan explains what your report says and is not medical advice. Talk to your doctor about what these results mean for you."

**No range bars.** The mockup has none. Update the plan line that mentions them (section 12).

### 6.4 Failed view (`report-page--03`, `--09`)

- Eyebrow from the code, headline, and body, per code, from `lib/copy.ts`. Use the mockup for `UNREADABLE` (with the B4 switch), and the contract 8.2 suggested messages for the others:

  | Code | Eyebrow | Headline |
  |---|---|---|
  | `UNREADABLE` | "Couldn't read this file" | "We couldn't read text from this file." |
  | `DOCUMENT_TOO_LONG` | "Document too long" | "This document is too long to read." |
  | `NO_RESULTS_FOUND` | "No results found" | "We didn't find lab results in this document." |
  | `EXTRACTION_FAILED` | "Something went wrong" | "Something went wrong reading the results." |
  | `CAPACITY` | "Daily limit reached" | "The demo has hit today's limit." |
  | `LLM_UNAVAILABLE` | "AI service unavailable" | "The AI service is unavailable right now." |
  | `INTERRUPTED` | "Processing interrupted" | "Processing was interrupted." |
  | unknown | "Something went wrong" | "We couldn't finish reading this report." |

- **Actions:** "Try another file" (goes to `/#upload`) and "Try a sample report" (goes to `/#samples`). Add "Delete this report" at the end.
- **Code line:** `{CODE} · report {first 8 of id}` (B9).

### 6.5 Delete

- `DeleteReportDialog`: a dialog restyled with tokens (**Base UI's `AlertDialog`** — there is no shadcn in this project; 7a F3 used `@base-ui/react`. `AlertDialog` rather than `Dialog` because deleting cannot be undone, so it must not close on an outside click). Title "Delete this report?", body "The file, its values and summary are removed from our server. This can't be undone.", buttons "Delete report" and "Cancel". Focus is trapped, Esc cancels, and focus returns to the trigger.
- **On confirm:** `DELETE /api/reports/{id}`.
  - **`204` or `404`:** both count as done. Remove `['reports', id]` from the cache, stop polling, show the toast "Report deleted. / The file, its values and summary are gone from our server.", and go to `/`.
  - **Other errors:** an inline error in the dialog with "Try again".

---

## 7. Biomarker pages

### 7.1 Content files (`content/biomarkers/<slug>.md`)

**Frontmatter (validated at load, with a clear error naming the file):**

```yaml
slug: ldl-cholesterol            # must equal file name and be in index.json
title: LDL Cholesterol
panel: Lipid panel               # eyebrow "Biomarker · Lipid panel"
aliases: [LDL-C, Low-density lipoprotein, '"Bad" cholesterol']
measures: Cholesterol carried by LDL particles in your blood
usualUnits: mg/dL, or mmol/L in many countries
usuallyPartOf: A lipid panel, with total cholesterol, HDL and triglycerides
related: [total-cholesterol, hdl-cholesterol, triglycerides]   # slugs; unknown slugs fail the build
sources:
  - { title: "…", url: "https://…" }
reviewedOn: null                 # YYYY-MM-DD once I review it
reviewer: null                   # my name once I review it
```

**Body:** H2 sections, in this order (the RAG index in Phase 5 splits on these headings, so keep them stable):
- "What it measures"
- "What high {short name} means" (or "What a positive result means" for qualitative tests)
- "What low {short name} means" (or "What a negative result means" for qualitative tests)
- "What can affect the result"
- "Questions to ask your doctor" (a bullet list)

Five slots with fixed roles: what the test is, the result in one direction, the result in the other, what disturbs it, what to ask. Slots 2 **and** 3 take the qualitative wording — a test reported as positive or negative has no high or low to describe (thyroid peroxidase antibodies is the one in this set). The structure the RAG index splits on is unchanged either way.

"Related tests" is generated from `related`, not written in the body.

**Content rules (B11):**
- Plain language at about a 6th to 8th grade level, written to "you". Follow the design-system voice.
- **No numeric reference ranges or cut-offs** (for example "under 100 mg/dL"). The page says to use the range on the report. A content test fails on number-plus-unit patterns in the body.
- No diagnosis, no treatment or dosing advice, no "you have …". Describe what the test measures and what high or low values are commonly linked to, then point to the doctor.
- **Sources:** reputable public ones, such as MedlinePlus (U.S. National Library of Medicine) and NHS pages. Write in your own words. Don't copy sentences. List the URLs in `sources`.
- One page for **each slug in `index.json`**, no more and no fewer. A test enforces it. Don't add slugs: the backend has a synced copy of the index, and this phase makes no backend change.

### 7.2 Loading (`lib/biomarker-content.ts`)

- Read and parse every file once per server instance (module-level cache). Frontmatter is split with a small regex and parsed with **`yaml`** (zero dependencies, and already in the tree via Vite; `gray-matter` would pull four, including a js-yaml line from 2019). Validation is **hand-written**, matching `lib/env.ts` and `lib/guest-session.ts` — no zod. A bad file throws with its own name in the message.
- Render the markdown with `react-markdown`, with raw HTML disabled (`skipHtml`), no `rehype-raw`, and links limited to `https:` and site paths. Headings get stable ids for "On this page".
- `generateMetadata`: the title "{title} · Medi-Scan" and a description from `measures`.
- An unknown slug shows the 404 page.

### 7.3 Page layout (`content-pages--01` desktop, `--03` phone)

1. **Breadcrumb:** "Biomarkers / {title}". "Biomarkers" links to `/biomarkers`.
2. **Header:** eyebrow "Biomarker · {panel}". Headline "{title} / in plain language." "Also written as" chips from `aliases`.
3. **`OnYourReportBox`:** client-only, shown only when the URL has `report` and `b`, and that report loads in this tab.
   - **Content:** "On your report · {collected date}", the value and unit, the flag chip, and "Your lab's range is {referenceRangeText}. Ranges differ between labs, so always use the one on your report."
   - **Actions:** "Back to report". "See trend" is hidden until 7c.
   - **Fetching:** it uses the same `['reports', id]` query (usually already cached). If it fails, or the biomarker isn't in the report, the box is not shown. No error, no message.
   - **Privacy:** values are never put in the URL, only the ids.
4. **Facts grid:** Measures, Usual units, Usually part of.
5. **"On this page":** sticky on desktop, built from the H2s.
6. **Body:** then "Related tests" as links.
7. **Footer note:**
   - Always: "This page explains what the test measures. It is not medical advice and can't tell you what your result means for you. Talk to your doctor about your results."
   - Then the sources as links, and the review line:
     - **Reviewed:** "Last reviewed {date} by {reviewer}".
     - **Not reviewed:** "Not yet reviewed by a person."

`/biomarkers` index: eyebrow "Biomarkers", headline "Lab tests / in plain language.", and a list grouped by `panel`, with title and `measures`. Use the same tokens. It has no mockup, so keep it simple.

### 7.4 Review before deploy

The final report lists every page with `reviewedOn: null`. I review them before Phase 8, and the deploy checklist will block on unreviewed pages. This phase doesn't fill in my name.

---

## 8. Sitemap and robots

- `sitemap.ts`: `/`, `/privacy`, `/biomarkers`, and each biomarker page. The base URL is `NEXT_PUBLIC_SITE_URL`. If it's unset, return an empty sitemap and log one warning at build.
- `robots.ts`: allow everything except `/reports/` and `/dev/`. Include the sitemap URL when the site URL is set.
- Report pages also get `noindex` metadata.

---

## 9. Privacy in the browser

- **Report content** (values, names, summary) never goes into URLs, titles, logs, `console`, storage, or error messages.
- **Report ids** may appear in URLs. They are useless without the owner's credential (a 404 for anyone else).
- **The TanStack cache is memory only.** Leaving or closing the tab drops it.
- **The file input is reset** after a successful send. The `File` object is not kept after navigation.
- **No third-party requests.** The 7a CSP stays as it is. This phase adds no external domain.

---

## 10. Tests

**Unit (Vitest, Testing Library, MSW):**
1. **`upload-source`:** every client check, including the exact 10 MB boundary, MIME versus extension, a 0-byte file, 19, 20, 50,000 and 50,001 characters, switching sources clearing errors, and consent gating.
2. **`createReport`:** multipart for files (no manual `Content-Type`), JSON for text and sample, `consent` always true, and one retry after `SESSION_INVALID`.
3. **Upload error mapping:** every row in 5.4 renders its copy and code line. The `RATE_LIMITED` countdown formats and ticks (fake timers).
4. **`useReport` polling** (fake timers):
   - polls every 1.5 s
   - stops at `DONE` or `FAILED`
   - an unknown status keeps polling
   - the 2-minute limit, then "Keep waiting"
   - pauses while hidden
   - 404 and `SESSION_INVALID` lead to their screens
5. **`report-format`:**
   - `collectedOn` never shifts a day (test in several `TZ` values)
   - the "Deleted in" thresholds
   - value with and without a unit
   - highlight → flag (B8), including no match, two names where one contains the other, and case
6. **Count tiles and filter:** counts come from `result.counts`, the filtered rows are right, and the caption is right.
7. **Delete:** confirm, then 204 or 404, then the toast, cache removal, and navigation. An error stays in the dialog.
8. **Biomarker content:**
   - every slug in `index.json` has a valid file, and the reverse
   - every `related` slug exists
   - the section headings are present and in order
   - no number-plus-unit pattern in the body
   - no raw HTML
   - every source is an `https` URL
9. **Copy switch:** with `NEXT_PUBLIC_FEATURE_OCR=false`, no rendered text mentions OCR or photos except the "coming soon" notices. With `true`, the mockup strings appear.

**End-to-end (Playwright, production build, backend mocked with route interception):**
1. **Sample flow:** select the lipid sample, tick consent, press Analyze. The processing page appears and moves to done with results that match the mock. Count tiles filter the table.
2. **PDF flow:** upload a small generated PDF, then processing, then done.
3. **Paste flow:** text under 20 characters is blocked, valid text is sent.
4. **Every upload error from 5.4** renders, and the Analyze button state is right.
5. **Images:** picking a PNG shows "Photos are coming soon", sends no request, and "Paste text instead" switches the tab.
6. **Failed report:** each code shows its copy and the code line.
7. **Errors:** an unknown report id shows "Report not available". `SESSION_INVALID` while polling shows "Guest session ended".
8. **Delete** from the done view and from the processing view.
9. **Biomarker page:**
   - reached from a results row, it shows the "On your report" box with the right value
   - opened directly without params, there's no box
   - an unknown slug shows 404
10. **Phone (390 px):** the sticky Analyze bar appears after scrolling past the panel and submits. The swipe row for samples and the compact rows work.
11. **Accessibility and security:**
    - axe on `/`, a done report, a failed report, and a biomarker page, in both themes, with no serious or critical issues
    - no CSP violations
    - no request to anything but the API origin and the site itself
12. **Storage after the full flow:** `localStorage` holds only `ms-theme`. `sessionStorage` holds only `ms.guest`. No report data in either.
13. **Screenshots:** landing, processing, done, failed, and biomarker pages at 1440 and 390 px in both themes, for the visual review.

---

## 11. Visual review against the mockups

Same method as 7a. Compare with `landing-page--*`, `report-page--01` to `--06` and `--08` to `--10` (ignoring the chat column and chat screens), and `content-pages--01` and `--03`. List the differences as **fixed**, **intentional** (for example B3, B4, B7, B10), or **open**.

---

## 12. Docs to update (first, before code)

1. **`docs/plan.md`:**
   - **Section 14:** remove the "Deploy a first version" line that 7a added after 7b. Show the order 7a → 7b → Phase 4 → Phase 5 → 7c → 7d → Phase 8, and keep deployment as Phase 8.
   - **Biomarker pages:** change "statically generated" to "server-rendered from markdown, content read once and cached" (B1).
   - **Phase 7 list:** change "Results view with range bars" to "Results view (table with flags, per the mockups)".
   - **Repository structure:** `app/reports/[id]` (B2) and `app/biomarkers/`.
2. **Root `CLAUDE.md`, "Frontend":**
   - The "Biomarker pages" rule says server-rendered (B1), not statically generated.
   - Add: "Biomarker content never states numeric reference ranges, and stays marked unreviewed until a person reviews it."
   - Add: "Copy that depends on a later phase goes through `lib/copy.ts` and its feature flag."
3. **`docs/ui/README.md`:** note that the chat parts of the report screens belong to Phase 5, and that B7 applies until then.
4. **`SECURITY.md`:** a short "In the browser" note from section 9.
5. **`docs/api-contract.md`:** no change. If anything in it doesn't match the backend, stop and tell me.
6. Commit the corrected screenshots I sent (`docs/ui/screens/`, same file names) on their own, before the docs commit, if they're not in yet.

Edit what is in the repo now. The repo copies may differ from this list.

---

## 13. Done checklist

1. `lint`, `typecheck`, `test`, `build`, and `e2e` pass in `client/`.
2. **The real flow works against the local backend** with the fake LLM provider:
   - all three samples, one generated text PDF, and one pasted text reach `DONE`
   - an image-only PDF ends `FAILED` with `UNREADABLE`
3. **With `GEMINI_API_KEY` set:** one sample through the real model reaches `DONE`. Skip it if the key isn't set.
4. **Privacy:** the storage check and the no-third-party-requests check pass. No report data in titles, URLs or the console.
5. **Visual review:** the list exists, and the "open" group is short and explained.
6. **No backend changes.** `backend/CLAUDE.md` is untouched and still ignored.

---

## 14. Open items for the final report

- Libraries added (`react-markdown`, a frontmatter parser, zod if new), with versions.
- The biomarker pages written, their sources, and the list waiting for my review.
- Any copy that had to change from the mockups, and why.
- Anything the 7a foundation needed changed (for example, the status check reacting to a failed call immediately).
- Anything to decide before Phase 4 (OCR).