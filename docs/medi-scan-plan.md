# Medi-Scan: Project Plan (v3)

Medi-Scan is a web app that explains medical lab reports in plain language. A user uploads a report (PDF, scanned image, or pasted text) or picks a built-in sample report. The app returns the results as structured data with a simple summary, and the user can then ask follow-up questions that are answered from their own report and from curated biomarker reference pages.

Anyone can use the app as a guest with no login. Signing in with Google is optional and unlocks report history and biomarker trends. An admin role opens a usage dashboard.

> **Disclaimer (shown throughout the UI):** This tool is for educational purposes only and is not a substitute for professional medical advice, diagnosis, or treatment.

**Changes since v2**
- **Guest by default:** the full demo still works with no login.
- **Optional Google sign-in:** uses Google's ID token. The backend issues its own short-lived access JWT and a refresh token that is replaced on every use. No passwords and no email service.
- **The $0 token setup:** the access token is kept in memory and the refresh token in `sessionStorage`. A strict Content Security Policy limits the damage from a script-injection bug.
- **Signed-in features:**
  - Report history kept for 30 days.
  - Biomarker trend charts across reports.
  - Account deletion.
  - Reports from the current guest session move to the account on sign-in.
- **Admin role:** an admin dashboard with usage and error stats, granted through config.
- **Authorization model:** every report has exactly one owner, either a guest session or a user. Admin routes use role checks.
- **Staying signed in:** Google auto sign-in keeps users signed in across tabs and return visits, with one refresh family per tab and logout sent to every tab.
- **Key rotation:** signing keys have ids (`kid`) so they can be rotated without signing anyone out.

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
- **Optional Google sign-in** for 30-day report history, biomarker trends, and account deletion.
- **Admin dashboard** behind role-based access.

**Out of scope**
- Real patient data or any compliance claim (HIPAA etc.). The app is a demo meant for synthetic or sample reports.
- Email/password accounts, password reset, email verification, and other identity providers. Google is the only sign-in method.
- Requiring login to use the demo.
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
| Auth | Spring Security (stateless). Google Identity Services on the client. Google ID tokens are verified with Nimbus against Google's public keys. The app's own access JWTs (HS256) are validated by Spring's OAuth2 resource server. |
| Rate limiting | Bucket4j (per IP, or per user when signed in) + a daily counter in Postgres (global) |
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
    |  HTTPS + X-Session-Token (guest) or Authorization: Bearer <JWT> (signed in)
    |  Google Identity Services -> ID token -> /api/auth/google
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
- A "Sign in with Google" button in the header. Guests see what signing in unlocks, but nothing is blocked.

### 4.9 Authentication

There are two kinds of caller, and every report endpoint accepts either.

| Caller | Credential | How it's obtained | Lifetime |
|---|---|---|---|
| Guest | `X-Session-Token` header | `POST /api/sessions` | 24 hours |
| Signed-in user | `Authorization: Bearer <access JWT>` | `POST /api/auth/google`, then `POST /api/auth/refresh` | 15 minutes (refresh token: 7 days) |

If a request carries both, the bearer token wins and the session token is ignored.

**Google sign-in flow**
1. The client loads Google Identity Services and shows the "Sign in with Google" button. Google returns an ID token (a signed JWT) to the page.
2. The client sends it to `POST /api/auth/google`, along with the current guest session token if there is one.
3. The backend verifies the ID token:
   - **Signature:** checked against Google's published keys (JWKS, cached).
   - **Issuer:** must be `accounts.google.com` or `https://accounts.google.com`.
   - **Audience:** must equal `GOOGLE_CLIENT_ID`.
   - **Expiry:** must not have passed.
4. The backend finds or creates the user by Google `sub`. It stores only `sub` and a display name, not the email address or the profile photo.
5. If a guest session token was sent, that session's reports move to the user. Their retention is extended to 30 days, and the guest session is deleted.
6. The backend returns its own access token and refresh token.

**Access token (JWT)**
- Signed with HS256. The keys are listed in `JWT_SIGNING_KEYS`, each 32 random bytes with a key id (`kid`). `JWT_CURRENT_KID` picks the signing key, and verification accepts any listed key by `kid`.
- Claims: `sub` (user id), `role`, `iss`, `aud`, `iat`, `exp`, `jti`. The header carries `kid`.
- Valid for 15 minutes.
- Validated by Spring Security's OAuth2 resource server. No database lookup per request.

**Refresh token**
- 32 random bytes. Only its SHA-256 hash is stored, in `refresh_token`.
- **Rotation:** every `POST /api/auth/refresh` revokes the token used and issues a new one in the same "family".
- **Reuse detection:** if a revoked token is used again, someone has a stolen copy, so the whole family is revoked and the user must sign in again.
- **Expiry:** valid for 7 days from issue. A family can't outlive 30 days from the original sign-in.
- **Logout:** `POST /api/auth/logout` revokes the family.

**Token storage on the client (the $0 setup)**
- **Access token:** kept in a JavaScript variable in memory only, so it is gone on reload.
- **Refresh token:** kept in `sessionStorage`, one per tab. On reload, the client calls `/api/auth/refresh` to get a new access token.
- **New tabs and returning visits:** Google auto sign-in (`auto_select: true`) signs the user in without a click if they signed in before and are still logged into Google.
  - Each tab gets its own refresh-token family, so tabs never race on refresh.
  - Report pages wait for sign-in to resolve, about 2 seconds at most, before fetching. That way a report opened in a new tab doesn't 404 as a guest.
  - If Google skips auto sign-in, the tab continues as a guest with the normal button.
- **Logout in every tab:** logout is sent to every tab over `BroadcastChannel`. Each tab revokes its own family and calls `disableAutoSelect()`, so the user isn't signed straight back in.
- **Guest sessions:** created lazily on the first guest action that needs one, not on page load.
- **Refreshing:** the client refreshes once on `401 TOKEN_EXPIRED` or `401 TOKEN_INVALID`, then retries once.
- **Why not cookies:** the frontend (Vercel) and API (EC2) are on different sites, and Safari blocks third-party cookies. The cookie-based setup needs both on one domain, which costs a paid domain.
- **The trade-off:** a script-injection (XSS) bug could read `sessionStorage`. Mitigations:
  - A strict Content Security Policy (see section 6).
  - No `dangerouslySetInnerHTML`.
  - Model output rendered as sanitized markdown with raw HTML disabled.
  - Short access token life.
  - Refresh token rotation with reuse detection.
  - The README explains this trade-off.

**Signing key rotation**
- **When:** at least once a year, whenever the backend moves hosts, and immediately on any suspected leak.
- **How:** add the new key and make it current, keep the old one for 15 minutes (the access token lifetime), then remove it.
- **Effect on users:** none. Refresh tokens don't depend on the signing key, and a leftover old-key token just triggers one silent refresh.
- **Details:** in `docs/dataflow.md` section 3.7.

**CSRF.** No cookies carry credentials, so cross-site request forgery doesn't apply. CSRF protection is disabled in Spring Security, and the reason is documented in a comment.

### 4.10 Authorization

- **Ownership:** every report has exactly one owner, either `session_id` (guest) or `user_id` (signed in). A database check constraint enforces exactly one.
- **Report access:** every report query filters by the caller's owner id. Someone else's report returns 404, never 403, so the API never confirms that an id exists.
- **Roles:** `USER` and `ADMIN`. Guests have no role.
- **Admin grant:** granted only through config. Google `sub` values listed in `ADMIN_GOOGLE_SUBS` get `ADMIN` on sign-in. There is no UI to grant roles.
- **Admin routes:** `/api/admin/**` requires `ADMIN` (`@PreAuthorize("hasRole('ADMIN')")` plus a matcher in the filter chain).
- **Signed-in-only routes:** `/api/me/**` and `/api/trends/**` return `401 AUTH_REQUIRED` for guests.

### 4.11 History and trends (signed-in only)

- **History:** a "My reports" page lists the user's reports, newest first, paginated.
- **Trends:**
  - **What they are:** pick a biomarker and see its value across reports on a line chart, drawn against the reference range.
  - **Matching:** biomarkers are matched across reports by `biomarkerSlug` when one exists, otherwise by normalized test name.
  - **Units:** points with different units are never put on the same chart. The API groups points by unit.
  - **Dates:** each point is dated by the report's collection date when step 1 can extract it (`collectedOn`), otherwise by upload date. Collection dates are kept by masking. Only dates next to birth-date labels are masked.
- **Account deletion:** `DELETE /api/me` deletes the user, all their reports, and all refresh tokens in one cascade.

### 4.12 Admin dashboard

`/admin` shows:
- LLM calls and tokens per day, against the daily cap
- report counts by status and by failure code
- average processing time per source type
- counts of masking conflicts
- rate-limit rejections per day

It reads only aggregated counts. It never shows report content, ids of other users' reports, or user names.

---

## 5. Rate Limiting and Cost Control

- **Per caller:** 10 uploads per hour plus a chat message limit (Bucket4j). Limits are keyed by user id when signed in, and by client IP otherwise. The real client IP is read from `X-Forwarded-For`, set by Caddy, with `server.forward-headers-strategy=framework`.
- **Auth endpoints:** `POST /api/auth/google` and `POST /api/auth/refresh` are limited to 10 per minute per IP.
- **Global:** a daily LLM call cap stored in a Postgres table (`llm_usage(day, calls)`), so it survives restarts and redeploys.
- **Caching:** Caffeine caches biomarker page retrieval and repeated identical questions.
- **Over-limit responses:** when a limit trips, return HTTP 429 with a friendly "demo is at capacity" message.
- **Usage logging:** every LLM call logs provider, model, input and output tokens, and latency (never content).

---

## 6. Security and Privacy

- **API keys:** none in the frontend. All LLM calls go through the backend.
- **Logs:** no raw or unmasked content.
- **Retention:** raw uploads are deleted right after extraction. Guest sessions and their reports auto-delete after 24 hours, and signed-in users' reports after 30 days, via a scheduled job. Reads also refuse anything past `expires_at`.
- **Minimal user data:** users are stored as Google `sub`, display name, role, and timestamps only. No email address and no photo. Accounts with no sign-in for 180 days are deleted.
- **Tokens:** session tokens and refresh tokens are stored only as SHA-256 hashes. `JWT_SIGNING_KEYS` and `GOOGLE_CLIENT_ID` come from the environment. Local and production use different signing keys.
- **Content Security Policy** (set in `next.config` headers):
  - `default-src 'self'`
  - `script-src 'self' https://accounts.google.com/gsi/client`
  - `frame-src https://accounts.google.com`
  - `connect-src 'self' <API origin> https://accounts.google.com`
  - `style-src 'self' 'unsafe-inline' https://accounts.google.com/gsi/style`
  - `object-src 'none'`
  - `base-uri 'self'`
- **No third-party scripts** other than Google Identity Services. No analytics or tag managers.
- **HTTPS:** HTTPS only, terminated by Caddy. CORS is restricted to the Vercel origin.
- **Guest session token:** the backend issues a random token when a session starts. The client stores it in `sessionStorage` and sends it as an `X-Session-Token` header. No cookies are used, so Safari's third-party cookie blocking isn't an issue.
- **Signed-in tokens:** see section 4.9.
- **Ownership checks:** report ids are random UUIDs, and every report endpoint checks that the report belongs to the caller (session or user). Anything else returns 404.
- **SECURITY.md:** documents the masking limits, the synthetic-data policy, and the free-tier LLM data terms.

---

## 7. Data Model

- `app_user(id UUID, google_sub UNIQUE, display_name, role, created_at, last_login_at)`
- `refresh_token(id, user_id, family_id, token_hash UNIQUE, issued_at, expires_at, revoked_at, family_started_at)`
- `session(id, token_hash, created_at, expires_at)`
- `report(id UUID, session_id NULL, user_id NULL, source_type, status, error_code, collected_on NULL, created_at, expires_at)`. A check constraint requires exactly one of `session_id` and `user_id`. Both foreign keys use `ON DELETE CASCADE`.
- `report_text(report_id, masked_text)`
- `biomarker(id, report_id, test_name, test_name_norm, biomarker_slug, raw_value, numeric_value, unit, reference_range_text, ref_low, ref_high, flag)`. `test_name_norm` is lowercase with punctuation removed, used to match markers for trends.
- `report_summary(report_id, patient_summary, highlights_json)`
- `llm_usage(day, calls)`
- `daily_stats(day, input_tokens, output_tokens, reports_created, reports_failed, rate_limit_rejections, masking_conflicts)`
- `daily_failure(day, code, count)`
- `daily_processing(day, source_type, total_ms, count)`

The three stats tables are counters only, kept for 90 days, with no report ids, user ids, or content. They feed the admin dashboard and survive report retention.
- `vector_store` (managed by Spring AI; holds only biomarker reference page chunks)

No names from reports, birth dates, contact details, or email addresses are stored. The only user name stored is the Google display name, for the header.

---

## 8. API

Report endpoints accept either a guest `X-Session-Token` or a signed-in `Authorization: Bearer` token. The full contract is in `docs/api-contract.md`.

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/auth/google` | Exchange a Google ID token for app tokens; can claim the guest session's reports |
| POST | `/api/auth/refresh` | Rotate the refresh token and get a new access token |
| POST | `/api/auth/logout` | Revoke the refresh token family |
| GET | `/api/me` | Current user (signed in only) |
| DELETE | `/api/me` | Delete the account and all its data |
| GET | `/api/reports` | List the caller's reports (paginated) |
| GET | `/api/trends` | Biomarker values across reports (signed in only) |
| GET | `/api/admin/stats` | Aggregated usage stats (admin only) |
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
│   │   ├── auth/          (Google token check, JWT issue, refresh rotation, security config)
│   │   ├── session/       (guest token issue, guest auth filter)
│   │   ├── user/          (me, account deletion, history)
│   │   ├── trends/        (biomarker matching across reports)
│   │   ├── admin/         (aggregated stats)
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
RETENTION_HOURS=24                 # guest sessions
USER_RETENTION_DAYS=30             # signed-in users' reports
INACTIVE_ACCOUNT_DAYS=180
GOOGLE_CLIENT_ID=<web client id from Google Cloud console>   # public, not a secret
JWT_SIGNING_KEYS=k2026a:<base64 key>   # comma-separated kid:key pairs; generate with: openssl rand -base64 32
JWT_CURRENT_KID=k2026a
JWT_ISSUER=medi-scan
ACCESS_TOKEN_TTL_MINUTES=15
REFRESH_TOKEN_TTL_DAYS=7
REFRESH_FAMILY_MAX_DAYS=30
ADMIN_GOOGLE_SUBS=<comma-separated Google sub ids>
DOMAIN=api.example.duckdns.org     # used by Caddy for TLS

# client/.env.local
NEXT_PUBLIC_API_URL=http://localhost:8080
NEXT_PUBLIC_GOOGLE_CLIENT_ID=<same web client id>
```

**Google setup (free):** create an OAuth "Web application" client in Google Cloud console. Add `http://localhost:3000` and the Vercel URL as authorized JavaScript origins. No client secret is needed for the ID token flow.

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

**Auth and authorization tests:**
- **Cross-owner access:** user A can't read, chat with, or delete user B's report. A guest can't access a user's report, and the other way round. All of these return 404.
- **Guest restrictions:** guests get `401 AUTH_REQUIRED` on `/api/me`, `/api/trends`, and `/api/admin/**`.
- **Role checks:** a `USER` gets `403 FORBIDDEN` on `/api/admin/**`.
- **Google token checks:** rejected for wrong audience, wrong issuer, expired, or a bad signature. Tests use a local test key set (JWKS), not Google.
- **Refresh rotation:** a used refresh token stops working, and reusing it revokes the whole family.
- **Expired tokens:** an expired access token returns `401 TOKEN_EXPIRED`.
- **Claiming guest reports:** guest reports move to the user, get a 30-day expiry, and the guest session is deleted.
- **Account deletion:** removes reports, report data, and refresh tokens.

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
| Unauthorized report access | UUIDs, one owner per report, ownership check on every endpoint, authorization tests |
| Token theft through XSS (tokens in browser storage) | Strict CSP, no raw HTML rendering, 15-minute access tokens, refresh rotation with reuse detection |
| Forged Google sign-in | Full ID token verification (signature, issuer, audience, expiry) |
| Signed-in users upload real data | Consent checkbox repeats the synthetic-data policy, minimal user data, 30-day retention, one-click account deletion |
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
6. **Accounts:**
   - Spring Security filter chain for guest and bearer callers.
   - Google sign-in, JWT issue, and refresh rotation.
   - Claiming guest reports.
   - `/api/me` and account deletion.
   - Report history and trends.
   - Admin role and stats.
   - CSP.
   - Authorization tests.
7. **Frontend polish and SEO:**
   - Results view with range bars.
   - History and trend charts.
   - Status and skeleton states.
   - Disclaimer and consent.
   - Biomarker pages with metadata.
8. **Deploy and harden:**
   - arm64 image, EC2 + Caddy + DuckDNS.
   - Neon.
   - Budget alert and retention job.
   - `SECURITY.md`, plus eval results in the README.
