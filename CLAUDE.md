# CLAUDE.md

Guidance for Claude when working in the Medi-Scan repository.

## Project

Medi-Scan explains medical lab reports in plain language. A user uploads a PDF, an image, or pasted text, or picks a bundled sample report. The backend extracts the text, masks personal data, pulls out structured lab values with an LLM, checks them in code, and writes a 6th-grade-level summary. Users can then chat about their report, and answers are grounded in the report plus curated biomarker pages.

Anyone can use it as a guest with no login. Signing in with Google is optional and unlocks 30-day report history, biomarker trends, and account deletion. An `ADMIN` role opens a stats dashboard.

It is a portfolio demo built for synthetic data. Running cost must stay at $0.

**Design docs (read before larger changes):**
- `docs/plan.md`: scope, stack, data model, auth design (sections 4.9–4.12), API, deployment
- `docs/dataflow.md`: pipeline stages, sequence diagrams, error codes, retention
- `docs/api-contract.md`: every endpoint, request and response shape, error code, and SSE event. This is the source of truth for the API.

**Keep the contract in sync.** Any change to an endpoint, field, enum, error code, or SSE event must update `docs/api-contract.md` and `client/lib/api-types.ts` in the same change. If code and the contract disagree, stop and ask which one is right.

## Repository layout

```
client/    Next.js (App Router), TypeScript, Tailwind, shadcn/ui, TanStack Query
backend/   Java 21, Spring Boot, Spring AI, Spring Data JPA
deploy/    docker-compose.prod.yml, Caddyfile (EC2)
docker/    local Postgres + pgvector init
docs/      design docs
```

Backend packages under `backend/src/main/java/.../`:

| Package | Contents |
|---|---|
| `auth` | Security filter chain, Google ID token verification, JWT issue, refresh rotation, logout |
| `session` | Guest token issue and the guest auth filter |
| `user` | `/api/me`, account deletion, report history |
| `trends` | Matching biomarkers across a user's reports |
| `admin` | Aggregated stats for the admin dashboard |
| `upload` | Controllers, validation, async jobs |
| `extract` | PDFBox text extraction, the scanned-PDF fallback, Tess4J OCR |
| `mask` | Regex rules, OpenNLP, the value integrity check |
| `analysis` | Step-1 extraction, validation and flag recomputation, step-2 summary |
| `llm` | The provider interface, Gemini and OpenAI providers, usage logging |
| `chat` | Context building, RAG over biomarker pages, SSE |
| `ratelimit` | Bucket4j limits (per user when signed in, otherwise per IP), the Postgres daily cap |
| `retention` | The scheduled cleanup job |
| `config` | Spring configuration |

## Commands

These are the intended commands. Update this section once the build is set up.

```bash
# Local database (Postgres 16 + pgvector)
docker compose up -d

# Backend
cd backend && ./mvnw spring-boot:run
cd backend && ./mvnw test
cd backend && ./mvnw test -Dtest=ExtractionEvalTest   # extraction accuracy eval

# Frontend
cd client && npm install && npm run dev
cd client && npm run lint && npm run build

# Production image (EC2 is ARM)
docker buildx build --platform linux/arm64 -t medi-scan-backend backend/
```

Configuration comes from `.env` (see `.env.example`). Never commit real keys.

## Non-negotiable rules

These are the core guarantees of the project. Don't weaken them, even to fix a bug or make a test pass. If a task seems to need breaking one, stop and ask.

1. **Only masked text goes to the LLM or the database.** Raw extracted text lives in memory only, and images never go to the LLM.
2. **Uploaded files are temp files.** Delete them in a `finally` block right after extraction. They are never stored in the database.
3. **No report content in logs.** Log ids, status changes, error codes, token counts, and timings only. This includes exception messages that might contain report text.
4. **Never trust the model's numbers or flags.**
   - Every `rawValue` must appear in the masked source text, or the row is dropped.
   - `numericValue`, `refLow`, and `refHigh` are re-parsed in code.
   - Flags (`LOW`, `NORMAL`, `HIGH`, `UNKNOWN`) are always recomputed in code.
   - There is no `CRITICAL` flag.
5. **The summary is a separate LLM call** that receives only the validated biomarker list, never raw text.
6. **Report text is data, not instructions.** Always wrap it in delimiters (`<report>`, `<results>`, `<reference>`). The model gets no tools.
7. **Every report endpoint checks ownership.**
   - Each report has exactly one owner: a guest `session_id` or a `user_id`.
   - Every report query filters by the caller's owner id.
   - Someone else's report returns 404, not 403. Report ids are random UUIDs.
8. **Every LLM call goes through the daily cap** (atomic conditional `UPDATE` on `llm_usage`) and is logged for token usage.
9. **Chat messages are never stored.** History comes from the client and is capped on the server.
10. **No medical advice.** Diagnosis and treatment questions are declined with a pointer to a clinician, and the disclaimer stays visible in the UI.
11. **Guests never need to log in.** Every core feature (upload, results, chat, samples) works without signing in. Only history, trends, and admin require it.
12. **Verify every Google ID token fully.** Check the signature against Google's public keys (JWKS), the issuer, the audience (`GOOGLE_CLIENT_ID`), and the expiry. Never decode a token without verifying it.
13. **Store as little about users as possible.**
    - Store only Google `sub`, display name, role, and timestamps.
    - Never store the email address, the profile photo, or the Google ID token.
14. **Hash every stored credential.** Session tokens and refresh tokens are stored only as SHA-256 hashes, and are never logged.
15. **Refresh tokens rotate.** Every refresh revokes the old token. Reusing a revoked token revokes the whole family.
16. **A bad bearer token never falls back to guest.** If `Authorization` is present and invalid, return 401 even when `X-Session-Token` is also sent.
17. **Admin comes from config only** (`ADMIN_GOOGLE_SUBS`). No endpoint or UI grants roles. Admin endpoints return aggregated counts only, never report content or user names.

## Conventions

**Backend**
- **Java types:** use `record`s for DTOs and LLM output types. Keep entities separate from DTOs.
- **LLM access:** all model calls go through the `llm` provider interface. Don't call a Spring AI client directly from other packages.
- **Embeddings:** the embedding model is fixed. Changing it requires re-embedding and a pgvector column migration, so don't change it casually.
- **HTTP errors:** return RFC 9457 `ProblemDetail` with extra `code` and `requestId` fields, from one `@RestControllerAdvice`. Use only the codes in `docs/api-contract.md` section 8. Add a new code to the contract first.
- **Report failure codes:** `UNREADABLE`, `NO_RESULTS_FOUND`, `EXTRACTION_FAILED`, `CAPACITY`, `LLM_UNAVAILABLE`, `INTERRUPTED`. These are stored on the report and returned in `error.code`.
- **Full queue or reached cap at upload:** return `429 BUSY` or `429 CAPACITY` without creating a report. Always set `Retry-After` on 429.
- **Spring AI versions:** its APIs change between versions. Check the version in `pom.xml` and that version's docs before writing Spring AI code. Don't guess class or method names.
- **Schema changes:** use Flyway migrations in `src/main/resources/db/migration`. Never edit a migration that has already been committed.
- **Config:** read from environment variables through `application.yml`. No secrets in code or YAML.
- **Transactions:** saving biomarkers and the summary happens in one transaction.
- **Memory:** the production box has 2 GB of RAM. OCR runs under a single-permit semaphore. Avoid loading large files fully into memory twice.
- **Retention:**
  - Report foreign keys to `session` and `app_user` both use `ON DELETE CASCADE`.
  - Guest reports expire after 24 hours, and user reports after 30 days.
  - Reads always filter on `expires_at > now()`.
- **Security config:**
  - One stateless `SecurityFilterChain`.
  - Bearer tokens are validated by Spring's OAuth2 resource server (HS256, checking issuer and audience).
  - A custom filter handles `X-Session-Token` for guests.
  - CSRF is disabled because no cookies carry credentials. Keep the comment that explains why.
  - Use `@PreAuthorize("hasRole('ADMIN')")` on admin controllers, and also protect `/api/admin/**` in the filter chain.
- **Auth library code:** use Spring Security and Nimbus classes for JWT and JWKS work. Don't write token parsing or signature checks by hand.
- **Signing keys:**
  - Read the keys from `JWT_SIGNING_KEYS` (`kid:key` pairs) and sign with `JWT_CURRENT_KID`.
  - Put `kid` in every token header, and verify by looking up the key by `kid`.
  - Never hard-code a key, log one, or use the same key in two environments.
  - The rotation procedure is in `docs/dataflow.md` 3.7.

**Frontend**
- **API calls:** go through one client in `client/lib/api.ts`. It attaches `Authorization: Bearer` when signed in, and otherwise `X-Session-Token`.
- **Token storage (the $0 setup):**
  - **Access token:** in memory only.
  - **Refresh token and guest token:** in `sessionStorage`.
  - **Never** use cookies or `localStorage` for tokens.
- **Refresh handling:**
  - On `401 TOKEN_EXPIRED` or `401 TOKEN_INVALID`, refresh once and retry once. Never loop.
  - Within a tab, concurrent failures must share a single in-flight refresh call, because parallel refreshes look like token reuse and revoke the family.
- **Auth state:** the client tracks `RESOLVING`, `SIGNED_IN`, or `GUEST`. Report pages and history wait while it's `RESOLVING` (about 2 seconds at most), so a report opened in a new tab doesn't 404 as a guest.
- **New tabs and return visits:**
  - Use Google Identity Services with `auto_select: true` to sign in without a click.
  - Each tab has its own refresh-token family.
  - Never share refresh tokens between tabs, and never move them to `localStorage`.
- **Logout:** broadcast `"logout"` over a `BroadcastChannel`. The message carries no tokens. Each tab revokes its own refresh token, clears its tokens, and calls `google.accounts.id.disableAutoSelect()`.
- **Guest sessions:** create them lazily, on the first guest action that needs one, never on page load.
- **XSS protection** (tokens live in the browser, so an XSS bug would expose them):
  - Never use `dangerouslySetInnerHTML`.
  - Render model output as markdown with raw HTML disabled.
  - Keep the Content Security Policy in `next.config` strict.
  - The only allowed third-party script is Google Identity Services. Don't add analytics or other third-party scripts.
- **Data fetching:** use TanStack Query. Report status is polled with `refetchInterval` until `DONE` or `FAILED`.
- **Chat streaming:** consumes SSE events `meta`, `token`, `sources`, `done` (with `finishReason`), and `error`. The endpoint is a POST with a custom header, so use `fetch` with a streamed body (or `@microsoft/fetch-event-source`), not `EventSource`.
- **Errors in the UI:** switch on the error `code`, never on `title` or `detail` text. On `401 SESSION_INVALID`, create a new session and drop the report ids from the old one.
- **Types:** shared API types live in `client/lib/api-types.ts` and must match the contract.
- **Biomarker pages:** content is markdown in `client/content/biomarkers/`, statically generated. The same files feed the backend RAG index, so keep headings meaningful because chunks are split by heading.
- **Qualitative values:** results with `numericValue: null` show as text, not on a range bar.

## Testing

Run the relevant tests before calling a change done.

- **Masking:** tests must check both directions: personal data is removed, and lab values and reference ranges survive unchanged.
- **Range parsing and flags:** unit tests cover cases such as `<200`, `3.5-5.0`, `>60`, `Negative`, and ranges that depend on sex.
- **Extraction eval:** synthetic reports with expected JSON live in `backend/src/test/resources/eval/`. Rerun the eval after any change to prompts, masking, extraction, or validation, and report the before and after numbers.
- **Prompt injection:** tests use synthetic reports that contain embedded instructions.
- **API tests:** controller tests check status codes and error `code` values against the API contract.
- **Authorization tests** (every new endpoint that touches reports needs these):
  - A user can't reach another user's report.
  - A guest and a user can't reach each other's reports.
  - Guests get `401 AUTH_REQUIRED` on signed-in-only routes.
  - A `USER` gets `403 FORBIDDEN` on admin routes.
- **Auth tests:**
  - Google token checks use a local test key set (JWKS), never Google itself. Cover a wrong audience, wrong issuer, an expired token, and a bad signature.
  - Refresh rotation and reuse detection.
  - Key rotation:
    - A token signed with a previous key that is still listed is accepted.
    - A token with a removed or unknown `kid` returns `TOKEN_INVALID`.
    - A refresh token still works after rotation.
  - Moving guest reports to the user on sign-in.
  - Account deletion cascades.
- **LLM calls in tests:** use a fake provider implementation. Tests never call a real LLM API, except the eval, which you run on purpose.
- **Test data:** use synthetic data only. Never add real reports or real-looking personal data to the repo.

## Deployment

- **Frontend:** Vercel.
- **Backend:** AWS EC2 t4g.small (ARM, 2 GB) running Docker Compose. Caddy terminates TLS with Let's Encrypt.
- **Database:** Neon free tier with pgvector.
- **Fallback host:** Oracle Cloud Always Free, using the same arm64 image. That is why every image must build for `linux/arm64`.
- **JVM heap:** capped with `-XX:MaxRAMPercentage=60`.

## Don't

- Add email/password login, password reset, email sending, or identity providers other than Google.
- Put tokens in cookies or `localStorage`, or require login for core features.
- Store a user's email, photo, or Google token, or log any token.
- Add paid services or make OpenAI the default provider.
- Store anything new about a report without adding it to the retention cascade.
- Send images, raw text, or unvalidated values to the summary or chat prompts.
- Expand or "improve" scope beyond the task asked. Suggest instead.
- Add a new dependency without asking first and saying why it's needed.
- Jump ahead of the build order in `docs/plan.md`.

## When finishing a task

Say what changed, what tests you ran and their results, and anything left undone or uncertain.
