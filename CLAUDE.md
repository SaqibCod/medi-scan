# CLAUDE.md

Guidance for Claude when working in the Medi-Scan repository.

## Project

Medi-Scan explains medical lab reports in plain language. A user uploads a PDF, an image, or pasted text, or picks a bundled sample report. The backend extracts the text, masks personal data, pulls out structured lab values with an LLM, checks them in code, and writes a 6th-grade-level summary. Users can then chat about their report, and answers are grounded in the report plus curated biomarker pages.

It is a portfolio demo built for synthetic data. Running cost must stay at $0.

**Design docs (read before larger changes):**
- `docs/plan.md`: scope, stack, data model, API, deployment
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
| `session` | Token issue and the auth filter |
| `upload` | Controllers, validation, async jobs |
| `extract` | PDFBox text extraction, the scanned-PDF fallback, Tess4J OCR |
| `mask` | Regex rules, OpenNLP, the value integrity check |
| `analysis` | Step-1 extraction, validation and flag recomputation, step-2 summary |
| `llm` | The provider interface, Gemini and OpenAI providers, usage logging |
| `chat` | Context building, RAG over biomarker pages, SSE |
| `ratelimit` | Bucket4j per-IP limits, the Postgres daily cap |
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
7. **Every report endpoint checks session ownership.** A report from another session returns 404, not 403. Report ids are random UUIDs.
8. **Every LLM call goes through the daily cap** (atomic conditional `UPDATE` on `llm_usage`) and is logged for token usage.
9. **Chat messages are never stored.** History comes from the client and is capped on the server.
10. **No medical advice.** Diagnosis and treatment questions are declined with a pointer to a clinician, and the disclaimer stays visible in the UI.

## Conventions

**Backend**
- **Java types:** use `record`s for DTOs and LLM output types. Keep entities separate from DTOs.
- **LLM access:** all model calls go through the `llm` provider interface. Don't call a Spring AI client directly from other packages.
- **Embeddings:** the embedding model is fixed. Changing it requires re-embedding and a pgvector column migration, so don't change it casually.
- **HTTP errors:** return RFC 9457 `ProblemDetail` with extra `code` and `requestId` fields, from one `@RestControllerAdvice`. Use only the codes in `docs/api-contract.md` section 6. Add a new code to the contract first.
- **Report failure codes:** `UNREADABLE`, `NO_RESULTS_FOUND`, `EXTRACTION_FAILED`, `CAPACITY`, `LLM_UNAVAILABLE`, `INTERRUPTED`. These are stored on the report and returned in `error.code`.
- **Full queue or reached cap at upload:** return `429 BUSY` or `429 CAPACITY` without creating a report. Always set `Retry-After` on 429.
- **Spring AI versions:** its APIs change between versions. Check the version in `pom.xml` and that version's docs before writing Spring AI code. Don't guess class or method names.
- **Schema changes:** use Flyway migrations in `src/main/resources/db/migration`. Never edit a migration that has already been committed.
- **Config:** read from environment variables through `application.yml`. No secrets in code or YAML.
- **Transactions:** saving biomarkers and the summary happens in one transaction.
- **Memory:** the production box has 2 GB of RAM. OCR runs under a single-permit semaphore. Avoid loading large files fully into memory twice.
- **Retention:** foreign keys use `ON DELETE CASCADE` from `session`. Reads always filter on `expires_at > now()`.

**Frontend**
- **API calls:** go through one client in `client/lib/` that attaches `X-Session-Token`. The token lives in `sessionStorage`, never in cookies or `localStorage`.
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
- **LLM calls in tests:** use a fake provider implementation. Tests never call a real LLM API, except the eval, which you run on purpose.
- **Test data:** use synthetic data only. Never add real reports or real-looking personal data to the repo.

## Deployment

- **Frontend:** Vercel.
- **Backend:** AWS EC2 t4g.small (ARM, 2 GB) running Docker Compose. Caddy terminates TLS with Let's Encrypt.
- **Database:** Neon free tier with pgvector.
- **Fallback host:** Oracle Cloud Always Free, using the same arm64 image. That is why every image must build for `linux/arm64`.
- **JVM heap:** capped with `-XX:MaxRAMPercentage=60`.

## Don't

- Add user accounts, OAuth, or JWT. They are out of scope.
- Add paid services or make OpenAI the default provider.
- Store anything new about a report without adding it to the retention cascade.
- Send images, raw text, or unvalidated values to the summary or chat prompts.
- Expand or "improve" scope beyond the task asked. Suggest instead.
- Add a new dependency without asking first and saying why it's needed.
- Jump ahead of the build order in `docs/plan.md`.

## When finishing a task

Say what changed, what tests you ran and their results, and anything left undone or uncertain.