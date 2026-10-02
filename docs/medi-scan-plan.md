# Medi-Scan: Project Plan (v2)

Medi-Scan is a web app that explains medical lab reports in plain language. A user uploads a report (PDF, scanned image, or pasted text) or picks a built-in sample report. The app returns the results as structured data with a simple summary, and the user can then ask follow-up questions that are answered from their own report and from curated biomarker reference pages.

> **Disclaimer (shown throughout the UI):** This tool is for educational purposes only and is not a substitute for professional medical advice, diagnosis, or treatment.

**Changes since v1**
- **LLM:** a free-tier LLM is the default (Gemini), and OpenAI is an optional provider, so the app costs $0 to run.
- **Hosting:** the backend runs on AWS EC2 (t4g.small) behind Caddy, with Oracle Cloud Always Free as the plan for after the AWS credits run out.
- **Scanned PDFs:** PDFs with no text layer are now detected and sent through OCR.
- **Data model:** handles non-numeric values, stores range limits as numbers, and drops `CRITICAL`.
- **Two-step analysis:** values are extracted first, flags are recomputed in code, and only then is the summary written.
- **Chat:** the report is sent to the model in full; RAG is used over the curated biomarker pages instead.
- **Background processing:** uploads return right away with a status the client polls.
- **Sessions:** a token in a request header instead of a cookie, with ownership checks on every report.
- **Rate limits:** the daily LLM cap is stored in Postgres, and client IPs are read correctly behind the proxy.
- **Masking:** tests now check that lab values survive masking, not only that personal data is removed.
- **Sample reports:** one-click sample reports so nobody needs to upload real data.
- **New pieces:** an extraction accuracy test set, prompt-injection defenses, and per-request token logging.

---

## 1. Goals and Scope

**Goals**
- Accept PDF (text-based or scanned), JPEG/PNG, and raw text reports (max 10 MB), plus one-click sample reports.
- Remove personal information before any text reaches an AI model.
- Turn report text into checked, structured results: test, value, unit, reference range, status flag.
- Explain findings at a 6th-grade reading level and highlight out-of-range values.
- Answer follow-up questions grounded in the uploaded report and curated biomarker pages.
- Provide fast, SEO-friendly biomarker pages.
- **Run at $0.** No paid API or hosting is required.
- Measure extraction accuracy against a fixed test set.

**Out of scope**
- Real patient data or any compliance claim (HIPAA etc.). The app is a demo meant for synthetic or sample reports.
- User accounts, roles, OAuth/JWT.
- Medical diagnosis or treatment advice.
- Converting units between systems. The app uses the report's own units and ranges.

---

## 2. Tech Stack

| Layer | Choice |
|---|---|
| Frontend | Next.js (App Router), TypeScript, Tailwind CSS, shadcn/ui, TanStack Query, Recharts, Lucide |
| Backend | Java 21, Spring Boot, Spring AI, Spring Data JPA, Actuator |
| Document parsing | Apache PDFBox (PDF text and page rendering), Tess4J / Tesseract (OCR) |
| PII masking | Regex rules + Apache OpenNLP name finder |
| LLM | Gemini free tier by default; OpenAI as an optional provider. Both sit behind a provider interface and are chosen in config. |
| Embeddings | One fixed embedding model that never changes, separate from the chat provider |
| Database | PostgreSQL 16 + pgvector (Docker locally, Neon free tier in production) |
| Rate limiting | Bucket4j (per IP) + a daily counter in Postgres (global) |
| Caching | Caffeine |
| Reverse proxy / TLS | Caddy with automatic Let's Encrypt certificates |
| Hosting | Vercel (frontend), AWS EC2 t4g.small (backend), Neon (database) |

**Cost notes**
- **Gemini free tier:** inputs may be used by the provider to improve its models. That is acceptable here only because the app is built for synthetic data and masks text before sending it.
- **OpenAI:** prepaid and metered, so it stays switched off unless credit is loaded. If it is switched on, set a hard spending cap.
- **Embedding model:** the embedding model sets the size of the pgvector column. Pick it once. Changing it later means re-embedding everything and migrating the column.
- **Version checks:** confirm free-tier limits and the exact Spring AI dependency names and versions before pinning them.

---

## 3. Architecture

```
Browser (Next.js on Vercel)
    |  HTTPS + X-Session-Token header
    |  upload -> poll status -> results -> chat (SSE)
    v
Caddy (TLS, Let's Encrypt)  --  EC2 t4g.small (Docker)
    v
Spring Boot API
    |- Validation: file type, magic bytes, size
    |- Async job (status: PENDING -> PROCESSING -> DONE / FAILED)
    |    |- Text extraction: PDFBox -> OCR fallback for scanned pages / Tess4J for images / raw text
    |    |- PII masking: regex + OpenNLP, then check that lab values are intact
    |    |- Step 1: structured extraction -> typed Java objects
    |    |- Validation: values must appear in source text; flags recomputed in code
    |    |- Step 2: summary + highlights generated from the validated data
    |- Chat: full report context + RAG over curated biomarker pages -> SSE
    v
PostgreSQL + pgvector (Neon)
```

**Key rules**
- Only masked text is ever sent to the LLM or stored.
- Images are never sent to the LLM. They go through OCR first, then masking.
- Raw uploads are deleted as soon as text has been extracted.
- API keys exist only on the backend.
- Report text is always treated as data, never as instructions (see 4.6).

---

## 4. Features and How They Work

### 4.1 Upload and validation
- The client checks type and size and shows a preview before sending.
- The server validates again: allowed types, file signature (not just extension), 10 MB cap.
- **Sample reports:** "Try a sample report" buttons load bundled synthetic reports (for example a lipid panel, CBC, and thyroid panel) so the demo works without uploading anything.
- **Background processing:** `POST /api/reports` returns a report id immediately with status `PENDING`. Processing runs in the background, and the client polls `GET /api/reports/{id}` until the status is `DONE` or `FAILED`.

### 4.2 Text extraction
- **Text-based PDFs:** PDFBox text extraction.
- **Scanned PDFs:** if a page yields almost no text (below a character threshold), render it to an image with PDFBox at about 300 DPI and run OCR on it.
- **Images:** Tess4J OCR, with an image size cap and one OCR job at a time to protect memory.
- **Raw text:** used as is.

### 4.3 PII masking
1. Regex rules for SSNs, phone numbers, emails, ID/MRN patterns, and dates of birth (only dates next to labels such as "DOB" or "Date of Birth").
2. OpenNLP name finder for person names.
3. Matches are replaced with typed placeholders such as `[NAME]` and `[PHONE]`.
4. **Value integrity check:** any match that falls inside a results table row, or that overlaps a numeric value or a reference range, is skipped and logged as a masking conflict.

Masking is best-effort, not a guarantee. The test suite checks both directions: personal data is removed, and every lab value and reference range survives unchanged. The tests use fake reports with names in awkward positions (headers, footers, signature lines, "Ordered by" fields).

### 4.4 Structured extraction (two steps)

**Step 1: extraction.** Spring AI's `BeanOutputConverter` maps the masked text into typed objects:

```java
enum Flag { LOW, NORMAL, HIGH, UNKNOWN }

record Biomarker(
    String testName,
    String rawValue,          // exactly as printed: "5.4", "<0.5", "Negative", "Trace"
    Double numericValue,      // null when the value is not numeric
    String unit,
    String referenceRangeText,// exactly as printed: "3.5-5.0", "<200", "Negative"
    Double refLow,            // null if the range has no lower bound
    Double refHigh,           // null if the range has no upper bound
    Flag flag                 // always recomputed in code
) {}

record ExtractionResult(List<Biomarker> biomarkers) {}
```

**Validation in code**
- **Value check:** every `rawValue` must appear in the masked source text. Rows that don't are dropped and logged, which stops the model from inventing values.
- **Number parsing:** `numericValue`, `refLow`, and `refHigh` are re-parsed from the raw strings in code and are not trusted from the model.
- **Flag recomputation:**
  - `LOW` or `HIGH` when the number falls outside the parsed bounds.
  - `NORMAL` when it falls inside them.
  - `UNKNOWN` when the value is qualitative or the range can't be parsed (for example, ranges that depend on sex or age).
- **No critical flag:** `CRITICAL` is deliberately left out. Critical values use separate cutoffs that can't be worked out from a reference range.

**Step 2: summary.** A second LLM call receives only the validated biomarker list, not the raw text:

```java
record ReportSummary(String patientSummary, List<String> highlights) {}
```

- **Reading level:** the summary is written at a 6th-grade level.
- **Highlights:** only markers flagged `LOW` or `HIGH` in code.
- **No contradictions:** because the summary is written from the checked data, it can't disagree with the flags.

### 4.5 Chat
- **Report context:** a lab report is short, so the full masked text plus the validated biomarker list goes straight into the prompt. Nothing is chunked, so no table rows get split.
- **RAG over biomarker pages:** the curated pages from 4.7 are chunked, embedded, and stored in pgvector. General questions ("What does TSH do?") retrieve from this reviewed content.
- **Grounding:** answers use only the report and the retrieved pages. If the answer isn't there, the app says so.
- **Medical advice:** diagnosis and treatment questions are declined with a pointer to a clinician.
- **Streaming:** answers stream to the browser over Server-Sent Events.

### 4.6 Prompt-injection defense
- Report text is wrapped in clear delimiters (for example `<report>...</report>`), and the system prompt says that content inside them is data only.
- The model never has tools or actions it can trigger; it only returns text.
- Output is validated against the schema, and anything off-schema is rejected.
- The test set includes a synthetic report with injected instructions ("Ignore previous instructions...").

### 4.7 SEO biomarker pages
- Curated static pages such as `/biomarkers/tsh` and `/biomarkers/hba1c`, generated at build time.
- OpenGraph metadata and JSON-LD structured data.
- Content is written and reviewed by me, with sources, and is also the knowledge base for chat RAG.
- The purpose is to show static generation and metadata work, not to win search traffic. Medical search results favour well-established sites.

### 4.8 UX
- Skeleton loaders and React Suspense.
- A processing status view while the background job runs.
- A disclaimer banner and a consent checkbox before upload.
- Results show each marker on a range bar (Recharts), with qualitative values shown as text.

---

## 5. Rate Limiting and Cost Control

- **Per IP:** 10 uploads per hour plus a chat message limit (Bucket4j). The real client IP is read from `X-Forwarded-For`, set by Caddy, with `server.forward-headers-strategy=framework`.
- **Global:** a daily LLM call cap stored in a Postgres table (`llm_usage(day, calls)`), so it survives restarts and redeploys.
- **Caching:** Caffeine caches biomarker page retrieval and repeated identical questions.
- **Over-limit responses:** when a limit trips, return HTTP 429 with a friendly "demo is at capacity" message.
- **Usage logging:** every LLM call logs provider, model, input and output tokens, and latency (never content).

---

## 6. Security and Privacy

- **API keys:** none in the frontend. All LLM calls go through the backend.
- **Logs:** no raw or unmasked content.
- **Retention:** raw uploads are deleted right after extraction. Sessions, reports, and derived data auto-delete after 24 hours via a scheduled job, and reads also refuse anything past `expires_at`.
- **HTTPS:** HTTPS only, terminated by Caddy. CORS is restricted to the Vercel origin.
- **Session token:** the backend issues a random token when a session starts. The client stores it in `sessionStorage` and sends it as an `X-Session-Token` header. No cookies are used, so Safari's third-party cookie blocking isn't an issue.
- **Ownership checks:** report ids are random UUIDs, and every report endpoint checks that the report belongs to the caller's session. Anything else returns 404.
- **SECURITY.md:** documents the masking limits, the synthetic-data policy, and the free-tier LLM data terms.

---

## 7. Data Model

- `session(id, token_hash, created_at, expires_at)`
- `report(id UUID, session_id, source_type, status, error_message, created_at, expires_at)`
- `report_text(report_id, masked_text)`
- `biomarker(id, report_id, test_name, raw_value, numeric_value, unit, reference_range_text, ref_low, ref_high, flag)`
- `report_summary(report_id, patient_summary, highlights_json)`
- `llm_usage(day, calls)`
- `vector_store` (managed by Spring AI; holds only biomarker reference page chunks)

No names, birth dates, or contact details are stored.

---

## 8. API

All `/api/reports` endpoints require the `X-Session-Token` header.

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/sessions` | Start an anonymous session; returns a token |
| POST | `/api/reports` | Upload a file or text, or pick a sample; returns id + `PENDING` |
| GET | `/api/reports/{id}` | Status, and when `DONE`, the analysis and summary |
| POST | `/api/reports/{id}/chat` | Ask a question (SSE stream) |
| DELETE | `/api/reports/{id}` | Delete the report and its data |
| GET | `/api/samples` | List the bundled sample reports |
| GET | `/actuator/health` | Health check |

---

## 9. Repository Structure

```
medi-scan/
├── client/
│   ├── app/
│   │   ├── page.tsx
│   │   ├── report/[id]/
│   │   └── biomarkers/[slug]/
│   ├── content/biomarkers/   (curated markdown, also indexed for RAG)
│   ├── components/           (upload, samples, status, results, chat, disclaimer)
│   ├── lib/
│   └── package.json
├── backend/
│   ├── src/main/java/.../
│   │   ├── session/       (token issue, auth filter)
│   │   ├── upload/        (controllers, validation, async jobs)
│   │   ├── extract/       (pdf, scanned-pdf fallback, ocr, text)
│   │   ├── mask/          (regex, opennlp, integrity check)
│   │   ├── analysis/      (extraction, validation, flagging, summary)
│   │   ├── llm/           (provider interface, Gemini, OpenAI, usage logging)
│   │   ├── chat/          (context building, biomarker RAG, SSE)
│   │   ├── ratelimit/     (Bucket4j filter, daily cap)
│   │   ├── retention/     (cleanup job)
│   │   └── config/
│   ├── src/main/resources/ (application.yml, OpenNLP models, sample reports)
│   ├── src/test/resources/eval/ (synthetic reports + expected JSON)
│   ├── pom.xml
│   └── Dockerfile            (multi-arch: arm64 + amd64)
├── deploy/
│   ├── docker-compose.prod.yml
│   └── Caddyfile
├── docker/postgres/init-extensions.sql
├── docker-compose.yml
├── .env.example
├── README.md
└── SECURITY.md
```

---

## 10. Configuration (`.env.example`)

```
LLM_PROVIDER=gemini                # gemini | openai
GEMINI_API_KEY=
GEMINI_CHAT_MODEL=<free-tier model>
OPENAI_API_KEY=                    # optional, leave empty to disable
OPENAI_CHAT_MODEL=<small, low-cost model>
EMBEDDING_PROVIDER=gemini          # fixed once chosen
EMBEDDING_MODEL=<embedding model>
EMBEDDING_DIMENSIONS=<must match the pgvector column>
DATABASE_URL=jdbc:postgresql://localhost:5432/mediscan
DATABASE_USER=
DATABASE_PASSWORD=
ALLOWED_ORIGIN=http://localhost:3000
RATE_LIMIT_UPLOADS_PER_HOUR=10
GLOBAL_DAILY_LLM_CALLS=500
RETENTION_HOURS=24
DOMAIN=api.example.duckdns.org     # used by Caddy for TLS
```

---

## 11. Deployment

**Frontend: Vercel.** Calls the backend over HTTPS only. Browsers block calls from an HTTPS page to a plain-HTTP backend.

**Backend: AWS EC2 t4g.small (2 GB RAM, ARM).**
- **Free period:** new AWS accounts get credits on a Free plan that lasts about six months or until the credits are used. Confirm the current terms at signup.
- **Container setup:** Docker Compose runs two containers. Caddy terminates TLS with a free Let's Encrypt certificate and proxies to Spring Boot.
- **Domain:** Caddy needs a domain name. A free DuckDNS subdomain works, or a cheap custom domain.
- **ARM image:** build the backend image for `linux/arm64` with `docker buildx`. Use a Debian-based JDK 21 image with `tesseract-ocr` and `tesseract-ocr-eng` installed.
- **JVM memory:** cap the heap at about 1 GB (for example `-XX:MaxRAMPercentage=60`) and add 1–2 GB of swap as a safety net.
- **Instance settings:** allow only ports 80 and 443 in the security group, use SSH keys only, and attach an Elastic IP (it is billed against credits).
- **Budget alert:** set an AWS Budget alert at $1 on day one. Public IPv4, EBS storage, and data transfer all draw down credits.

**Database: Neon free tier with pgvector.** Postgres stays off the EC2 box to save memory and AWS credits. The first query after Neon has been idle is slower.

**After the AWS credits run out:** move the same Docker Compose setup to Oracle Cloud Always Free (Ampere ARM instance). Because the image is already arm64, only the DNS record and env file need to change.

**CI (optional):** a GitHub Actions workflow builds the arm64 image, pushes it to GitHub Container Registry, and deploys over SSH.

---

## 12. Testing and Evaluation

**Extraction test set:** 15–20 synthetic reports with expected JSON. They cover:
- text-based PDFs, scanned PDFs, and photos
- qualitative values and odd ranges
- reports in different layouts

**Metrics:** a script reports precision and recall on biomarkers, the share of exact value matches, flag accuracy, and the masking leak rate. The README shows the numbers.

**Masking tests:** check that personal data is removed and that every lab value and reference range survives.

**Prompt-injection tests:** synthetic reports with embedded instructions.

**Unit tests:** the range parser and flag recomputation (`<200`, `3.5-5.0`, `>60`, `Negative`, ranges that depend on sex).

---

## 13. Risks and Mitigations

| Risk | Mitigation |
|---|---|
| Personal data slips through masking | Layered masking, sample reports, synthetic-data policy, no raw logging, 24h retention |
| Masking corrupts lab values | Value integrity check, two-way masking tests |
| Wrong extraction or invented values | Strict schema, values must appear in source, flags recomputed in code, eval set |
| Summary contradicts flags | Summary generated from validated data in a second call |
| Prompt injection via report text | Delimiters, no tools, schema validation, injection tests |
| LLM cost or quota limits | Free-tier default, Postgres-backed daily cap, caching, OpenAI off by default |
| Free-tier LLM data use | Synthetic-data framing, masking before every call, documented in SECURITY.md |
| OCR memory pressure | 2 GB instance, heap cap, swap, image size cap, one OCR job at a time |
| Slow processing / HTTP timeouts | Background jobs with status polling |
| Unauthorized report access | UUIDs, session token header, ownership check on every endpoint |
| AWS credits run out | Budget alert, arm64 image ready to move to Oracle Always Free |
| Over-claiming medical value | Educational framing, no critical flag, decline diagnosis questions |

---

## 14. Build Order

1. **Foundation:**
   - Monorepo setup.
   - Docker Postgres + pgvector.
   - Spring Boot and Next.js skeletons.
   - Health endpoint and CORS.
   - Session tokens and ownership checks.
2. **Text and PDF pipeline:**
   - Upload with background jobs and status.
   - PDFBox extraction.
   - Masking with the integrity check.
   - Step-1 extraction, validation, and flag recomputation.
   - Step-2 summary.
   - Sample reports.
3. **Evaluation:** build the synthetic test set and scoring script now, before adding OCR, so every later change is measured.
4. **OCR:** Tess4J for images and the scanned-PDF fallback, feeding the same pipeline.
5. **Chat:**
   - Curated biomarker content and embeddings.
   - Report-context chat with RAG and SSE.
   - Prompt-injection defenses.
   - Rate limits and the Postgres daily cap.
   - Caching and token logging.
6. **Frontend polish and SEO:**
   - Results view with range bars.
   - Status and skeleton states.
   - Disclaimer and consent.
   - Biomarker pages with metadata.
7. **Deploy and harden:**
   - arm64 image, EC2 + Caddy + DuckDNS.
   - Neon.
   - Budget alert and retention job.
   - `SECURITY.md`, plus eval results in the README.
