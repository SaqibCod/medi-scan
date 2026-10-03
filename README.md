# Medi-Scan

Medi-Scan explains medical lab reports in plain language. You upload a PDF, an
image, or pasted text — or pick a bundled sample report — and it returns your
results as structured data with a summary written at a 6th-grade reading level.
You can then ask follow-up questions, answered from your own report and from
curated biomarker reference pages.

Anyone can use it as a guest, with no login. Signing in with Google is optional
and unlocks 30-day report history, biomarker trends, and account deletion.

> **This tool is for educational purposes only and is not a substitute for
> professional medical advice, diagnosis, or treatment.**

**It is a portfolio demo built for synthetic data, and it runs at $0.** Please
don't upload a real medical report — see [SECURITY.md](SECURITY.md) for what
that means and why.

## How it works

```
upload  ->  extract text  ->  mask personal data  ->  extract values (LLM)
                                                            |
                              summary (LLM)  <-  validate and recompute flags
```

Four things that are load-bearing rather than incidental:

- **Only masked text ever reaches the model or the database.** Raw extracted text
  lives in memory only, uploaded files are deleted in a `finally` block right
  after extraction, and images are never sent to a model.
- **The model's numbers are never trusted.** Every value must appear in the
  masked source text or its row is dropped; numbers are re-parsed in code; and
  the `LOW` / `NORMAL` / `HIGH` / `UNKNOWN` flags are always recomputed in code.
- **The summary is a separate call** that receives only the validated values, so
  it cannot contradict the flags.
- **Report text is data, not instructions.** It is always wrapped in delimiters,
  and the model has no tools.

## Status

Built in the phases listed in [`docs/plan.md`](docs/plan.md) section 14.

| Phase | | |
|---|---|---|
| 1 | Foundation — sessions, errors, CORS, migrations, app shell | **done** |
| 2 | Text and PDF pipeline | not started |
| 3 | Extraction accuracy eval | not started |
| 4 | OCR | not started |
| 5 | Chat with RAG | not started |
| 6 | Accounts, Google sign-in, history, trends, admin | not started |
| 7 | Frontend polish and SEO | not started |
| 8 | Deploy and harden | not started |

Phase 1 gives you a working guest session, the error contract, and placeholder
pages. Uploading a report does not work yet.

## Running it locally

**You need:** Docker, a JDK (21 or newer), and Node 24+.

```bash
cp .env.example .env          # defaults already match the compose file below
```

**1. Database** — Postgres 16 with pgvector:

```bash
docker compose up -d
```

**2. Backend** — <http://localhost:8080>:

```bash
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

**3. Frontend** — <http://localhost:3000>:

```bash
cd client && npm install && npm run dev
```

Check it came up:

```bash
curl -i localhost:8080/actuator/health      # 200 {"status":"UP"}
curl -i -X POST localhost:8080/api/sessions # 201 { token, expiresAt }
```

### Tests

```bash
cd backend && ./mvnw verify                 # needs Docker (Testcontainers)
cd client  && npm run lint && npm run typecheck && npm test && npm run build
```

### Production image

EC2 is ARM, so the image must build for `linux/arm64`:

```bash
docker buildx build --platform linux/arm64 -t medi-scan-backend backend/
```

## Toolchain notes

Two versions here are deliberately *not* the newest thing on the registry:

- **TypeScript is pinned to 5.x.** npm's `latest` tag is now TypeScript 7, which
  is the Go-native rewrite. `eslint-config-next` and Next's TS plugin aren't
  verified against it yet, so `npm install typescript@latest` is not a safe bump.
- **Flyway, the Postgres driver and Testcontainers are unpinned** and come from
  Spring Boot's dependency management. Overriding them with a newer standalone
  release gets you a combination Boot has not tested.

The backend targets Java 21 (`--release 21`) and builds fine on a newer JDK.

## Design docs

Read these before any larger change. They are the source of truth, and code that
disagrees with them is a bug in one or the other.

| Doc | What's in it |
|---|---|
| [`docs/plan.md`](docs/plan.md) | Scope, stack, data model, auth design, deployment, build order |
| [`docs/dataflow.md`](docs/dataflow.md) | Pipeline stages, sequence diagrams, error codes, retention |
| [`docs/api-contract.md`](docs/api-contract.md) | **The API's source of truth** — every endpoint, shape, error code and SSE event |

## Layout

```
client/    Next.js (App Router), TypeScript, Tailwind, shadcn/ui, TanStack Query
backend/   Java 21, Spring Boot, Spring AI, Spring Data JPA
deploy/    production compose + Caddyfile (phase 8)
docker/    local Postgres + pgvector init
docs/      design docs
```
