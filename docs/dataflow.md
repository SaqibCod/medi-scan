# Medi-Scan: Data Flow Design

This document shows how data moves through Medi-Scan, what form it takes at each step, where it is stored, and when it is deleted. It matches plan v3 and API contract v2.1. Exact request and response shapes are in `docs/api-contract.md`. This document covers how the data moves between them.

There are seven flows:

1. **Identity:** guest sessions, Google sign-in, token refresh, logout, and how each request is authenticated.
2. **Report analysis:** an upload goes through the processing pipeline.
3. **Results:** polling for a report's status and results.
4. **Chat:** a question is answered from the report plus the curated biomarker pages.
5. **History and trends:** signed-in users only.
6. **Account deletion and admin stats.**
7. **Background flows:** loading biomarker content, cleaning up expired data, and enforcing limits.

---

## 1. System overview

```mermaid
flowchart LR
    U["Browser<br/>Next.js on Vercel"] -->|"HTTPS + session token or Bearer JWT"| C["Caddy<br/>TLS"]
    U <-->|"Sign in with Google"| G["Google Identity Services"]
    C --> API["Spring Boot API<br/>EC2 t4g.small"]
    API -->|"fetch public keys, cached"| GK["Google JWKS"]
    API -->|"upload bytes"| TMP[("Local temp file<br/>deleted after extraction")]
    API --> Q["Job executor<br/>2 workers, queue of 20"]
    Q --> P["Pipeline<br/>extract, mask, analyze"]
    P -->|"masked text only"| LLM["LLM provider<br/>Gemini or OpenAI"]
    P --> DB[("Neon Postgres<br/>+ pgvector")]
    API -->|"chat context"| LLM
    API --> DB
    MD["Curated biomarker<br/>markdown"] -->|"chunk + embed at startup"| DB
```

**Trust boundaries**
- **Browser to API:** everything is untrusted. Validate every input, including tokens.
- **Google to API:** the Google ID token arrives through the browser, so it is untrusted until its signature, issuer, audience, and expiry are verified against Google's public keys.
- **API to LLM:** only masked text crosses this line. Raw text and images never leave the server.
- **API to database:** only masked text, derived data, minimal user identity, and hashed tokens are stored. Raw files never reach the database.

---

## 2. Data inventory

### 2.1 Report data stages

Each report's data changes form as it moves through the pipeline.

| # | Stage | Data form | Where it lives | Contains personal data? | Lifetime |
|---|---|---|---|---|---|
| 1 | Upload | File bytes (PDF/image) or text | Temp file on EC2 disk | Possibly | Deleted right after step 2, even on failure |
| 2 | Extraction | Raw text (String) | JVM memory only | Possibly | Dropped right after step 3 |
| 3 | Masking | Masked text | JVM memory until the report is DONE, then the `report_text` table | No (best-effort) | Report lifetime |
| 4 | Step-1 LLM | Extraction JSON from the model | JVM memory only | No | Dropped after step 5 |
| 5 | Validation | Validated biomarker rows + `collected_on` | `biomarker`, `report` tables | No | Report lifetime |
| 6 | Step-2 LLM | Summary + highlights | `report_summary` table | No | Report lifetime |
| 7 | Chat | Question + streamed answer | Not stored | No | Discarded after streaming |

**Report lifetime** depends on the owner:
- **Guest:** 24 hours (expires with the session).
- **Signed-in user:** 30 days from creation.

### 2.2 Identity and token data

| Data | Where it lives | Stored form | Lifetime |
|---|---|---|---|
| Guest session token | Browser `sessionStorage`; `session` table | Hash only on the server | 24 hours |
| Google ID token | Browser memory, request body | Never stored or logged; discarded after verification | One request |
| User identity | `app_user` table | Google `sub`, display name, role, timestamps | Until account deletion, or 180 days without sign-in |
| Access token (JWT) | Browser memory only | Not stored on the server (self-contained, signed with the current key, `kid` in header) | 15 minutes |
| Refresh token | Browser `sessionStorage` (one family per tab); `refresh_token` table | Hash only on the server | 7 days, family max 30 days |
| JWT signing keys | `.env` on the server (`JWT_SIGNING_KEYS`) | Raw, in the environment only | Rotated at least yearly (3.7) |

### 2.3 Aggregated stats

| Data | Where it lives | Contents | Lifetime |
|---|---|---|---|
| Daily LLM cap | `llm_usage(day, calls)` | One counter per day | 90 days |
| Daily stats | `daily_stats(day, …)` | Counts only: tokens, reports created and failed, rate-limit rejections, masking conflicts | 90 days |
| Failures by code | `daily_failure(day, code, count)` | Counts only | 90 days |
| Processing time | `daily_processing(day, source_type, total_ms, count)` | Totals only | 90 days |

Stats are kept as counters, separate from reports, so the admin dashboard still works after reports are deleted. They contain no report ids, user ids, or content.

**Key rules**
- **Raw text stays in memory.** A crash or restart can't leave personal data behind on disk or in the database.
- **Temp files** are deleted in a `finally` block.
- **Chat messages** are never stored, for guests or users.
- **Server-side token storage:** the server never stores a raw token of any kind. It stores hashes only, and access tokens aren't stored at all.

---

## 3. Flow A: Identity

### 3.1 Guest session start

```mermaid
sequenceDiagram
    participant B as Browser
    participant A as API
    participant D as Postgres

    B->>A: POST /api/sessions
    A->>A: Per-IP rate limit
    A->>A: Generate random 32-byte token
    A->>D: INSERT session (token_hash, expires_at = now + 24h)
    A-->>B: 201 { token, expiresAt }
    B->>B: Save token in sessionStorage
    Note over B: Guest calls send<br/>X-Session-Token
```

**Details**
- **When it runs:** lazily, on the first guest action that needs it (an upload, picking a sample, or the history list), and only if the tab isn't signed in and has no guest token. Page loads and browsing the biomarker pages don't create sessions.
- **New tab:** `sessionStorage` is per tab, so a guest's new tab gets its own guest session the first time it needs one.

### 3.2 Google sign-in (with claiming guest reports)

```mermaid
sequenceDiagram
    participant B as Browser
    participant G as Google Identity Services
    participant A as API
    participant K as Google JWKS
    participant D as Postgres

    B->>G: User clicks "Sign in with Google"
    G-->>B: ID token (signed JWT)
    B->>A: POST /api/auth/google { idToken, guestSessionToken }
    A->>A: Per-IP rate limit
    A->>K: Fetch public keys (cached)
    A->>A: Verify signature, issuer, audience, expiry
    alt verification fails
        A-->>B: 401 GOOGLE_TOKEN_INVALID
    else verified
        A->>D: Upsert app_user by google_sub
        Note over A,D: role = ADMIN if sub in ADMIN_GOOGLE_SUBS
        opt guestSessionToken is valid
            A->>D: Move session reports to user, expires_at = created_at + 30 days
            A->>D: DELETE guest session
        end
        A->>A: Sign access JWT (15 min)
        A->>D: INSERT refresh_token (hash, new family_id)
        A-->>B: 200 { tokens, user, claimedReportCount, newUser }
        B->>B: Access token in memory
        B->>B: Refresh token in sessionStorage
        B->>B: Remove guest token
    end
```

**Details**
- **One transaction:** the user upsert, report claim, session delete, and refresh-token insert happen in one transaction. If any step fails, nothing changes and the guest keeps their reports.
- **In-flight reports:** reports that are still `PENDING` or `PROCESSING` are claimed too. The background job writes by report id, so it doesn't matter who owns the report when the job finishes.
- **ID token handling:** the Google ID token is discarded after verification. It is never stored or logged.
- **Stored identity:** only `sub` and the display name are copied from the token. The email and photo claims are ignored.
- **Admin role:** set on every sign-in from config. Removing a `sub` from `ADMIN_GOOGLE_SUBS` downgrades that user on their next sign-in. Their current access token keeps its role for up to 15 minutes.

### 3.3 New tabs and returning visits (auto sign-in)

A new tab, or a visit after all tabs were closed, has no tokens in `sessionStorage`. Instead of storing a long-lived token in the browser, the app relies on the user's Google session to sign them back in.

```mermaid
sequenceDiagram
    participant B as Browser (new tab)
    participant G as Google Identity Services
    participant A as API

    B->>B: Load page, auth state = RESOLVING
    alt sessionStorage has a refresh token (reload)
        B->>A: POST /api/auth/refresh
        A-->>B: 200 new tokens
        B->>B: auth state = SIGNED_IN
    else no tokens (new tab or later visit)
        B->>G: initialize with auto_select = true
        alt user signed in before and is logged into Google
            G-->>B: ID token, no click needed
            B->>A: POST /api/auth/google { idToken }
            A-->>B: 200 tokens (new family for this tab)
            B->>B: auth state = SIGNED_IN
        else no auto sign-in within about 2s
            B->>B: auth state = GUEST, show "Sign in with Google"
        end
    end
    Note over B: Report calls wait until<br/>RESOLVING has finished
```

**Details**
- **Auth states:** the client has three: `RESOLVING`, `SIGNED_IN`, and `GUEST`. Report pages and the history list wait while it's `RESOLVING`, so a report opened in a new tab isn't fetched as a guest and wrongly returns 404.
- **One family per tab:** each tab signs in separately and gets its own refresh-token family. Tabs never share a refresh token, so tabs can't race each other on refresh, and reuse detection stays simple.
- **Guest claim:** a new tab has no guest token, so there's nothing to claim. The `guestSessionToken` field is only sent when a guest in the same tab clicks "Sign in with Google".
- **When auto sign-in doesn't happen:** Google may skip it, for example when the browser doesn't support FedCM, when the prompt is paused after a recent dismissal, or when the user has several Google accounts. The tab then becomes `GUEST` with the normal sign-in button. Nothing breaks.
- **Cost on the server:** each auto sign-in is one `POST /api/auth/google`, counted against the per-IP limit of 10 per minute. That's enough for normal tab-opening. Extra refresh-token rows are removed by the retention job (9.2).
- **What the browser keeps:** nothing outlives the tab. Remembering the user is left to Google's session, not to a token stored in `localStorage`.

### 3.4 Token refresh, with reuse detection

```mermaid
sequenceDiagram
    participant B as Browser
    participant A as API
    participant D as Postgres

    B->>A: Any call with an expired or rejected access token
    A-->>B: 401 TOKEN_EXPIRED or TOKEN_INVALID
    B->>A: POST /api/auth/refresh { refreshToken }
    A->>D: Find refresh_token by hash
    alt not found or expired
        A-->>B: 401 REFRESH_TOKEN_INVALID
    else already revoked (reuse)
        A->>D: Revoke every token in the family
        A->>A: Log security event (family id only)
        A-->>B: 401 REFRESH_TOKEN_INVALID
    else valid
        A->>D: Revoke this token, insert new token in same family
        A->>A: Sign new access JWT
        A-->>B: 200 { new access token, new refresh token, user }
        B->>B: Replace both tokens
        B->>A: Retry the original call
    end
```

**Details**
- **Atomic swap:** revoking the old token and inserting the new one happen in one transaction. A conditional `UPDATE … WHERE revoked_at IS NULL` makes sure only one of two racing requests wins. The loser is treated as reuse.
- **Which errors trigger a refresh:** the client refreshes once on either `TOKEN_EXPIRED` or `TOKEN_INVALID`. `TOKEN_INVALID` covers access tokens signed with a key that has since been rotated out (3.7). The refresh token doesn't depend on the signing key, so the refresh still works.
- **One refresh at a time per tab:** the client runs one refresh at a time and makes other failed calls wait for it. Without this, two parallel refreshes look like reuse and sign the user out.
- **No cross-tab races:** each tab has its own `sessionStorage` and its own family (3.3), so tabs never refresh the same token.
- **One retry only:** if the retried call fails again with 401, the client stops and shows the user as signed out instead of looping.
- **Family lifetime:** a family can't outlive 30 days from the original sign-in. After that, refresh fails and the user signs in again.
- **Proactive refresh:** the client may refresh a minute before `accessTokenExpiresAt` instead of waiting for a 401.
- **On page load:** if `sessionStorage` has a refresh token, the client refreshes before making other calls, to get an access token into memory.

### 3.5 Logout (every tab)

```mermaid
sequenceDiagram
    participant T1 as Tab 1 (clicks logout)
    participant BC as BroadcastChannel
    participant T2 as Tab 2
    participant G as Google Identity Services
    participant A as API

    T1->>A: POST /api/auth/logout { tab 1 refreshToken }
    A-->>T1: 204 (tab 1 family revoked)
    T1->>T1: Clear tokens, auth state = GUEST
    T1->>G: disableAutoSelect()
    T1->>BC: post "logout"
    BC-->>T2: "logout"
    T2->>A: POST /api/auth/logout { tab 2 refreshToken }
    A-->>T2: 204 (tab 2 family revoked)
    T2->>T2: Clear tokens, auth state = GUEST
```

**Details**
- **Each tab revokes its own family.** Refresh tokens never leave the tab they belong to, not even over the `BroadcastChannel`. The message only says "logout".
- **Why `disableAutoSelect()`:** without it, Google auto sign-in would sign the user straight back in on the next page load. After logout, the next sign-in needs a click.
- **Closed tabs:** tabs that were already closed have no token left to revoke. Their families expire on their own within 7 days, and nothing in the browser can use them.
- **Back to guest:** after logout, the tabs are guests. A guest session is created lazily if the user does something that needs one.
- **Access tokens:** not revoked on the server, because they're stateless. They stop working within 15 minutes, and every tab has already deleted its copy.

### 3.6 How each request is authenticated

```mermaid
flowchart TD
    R["Incoming request"] --> H1{"Authorization<br/>header present?"}
    H1 -->|"yes"| V["Validate JWT<br/>signature, iss, aud, exp"]
    V -->|"expired"| E1["401 TOKEN_EXPIRED"]
    V -->|"invalid"| E2["401 TOKEN_INVALID"]
    V -->|"valid"| PU["Principal: User<br/>id + role"]
    H1 -->|"no"| H2{"X-Session-Token<br/>present?"}
    H2 -->|"yes"| SL[("Look up session<br/>by token hash")]
    SL -->|"found, not expired"| PG["Principal: Guest<br/>session id"]
    SL -->|"missing or expired"| PA["Principal: Anonymous"]
    H2 -->|"no"| PA
    PU --> RT{"Route"}
    PG --> RT
    PA --> RT
    RT -->|"/api/admin/**"| AD{"role ADMIN?"}
    AD -->|"yes"| OK["Handler"]
    AD -->|"User without ADMIN"| F403["403 FORBIDDEN"]
    AD -->|"Guest or Anonymous"| F401a["401 AUTH_REQUIRED"]
    RT -->|"/api/me/**, /api/trends/**"| U1{"User?"}
    U1 -->|"yes"| OK
    U1 -->|"no"| F401a
    RT -->|"/api/reports/**"| U2{"User or Guest?"}
    U2 -->|"yes"| OK
    U2 -->|"no"| F401b["401 SESSION_INVALID"]
    RT -->|"public routes"| OK
```

**Details**
- **Bearer validation:** done by Spring Security's OAuth2 resource server, with no database lookup. The signing key is picked by the token's `kid` (3.7).
- **Guest tokens:** handled by a custom filter placed before it.
- **No fallback:** a bad bearer token fails the request immediately. The server never falls back to the guest token.
- **Owner reference:** handlers receive an `OwnerRef`, either `USER(userId)` or `GUEST(sessionId)`. Every report repository method takes it, so report queries can't be written without an owner filter:
  - **User:** `WHERE id = ? AND user_id = ? AND expires_at > now()`
  - **Guest:** `WHERE id = ? AND session_id = ? AND expires_at > now()`

### 3.7 Signing key rotation

Only access tokens are signed with the JWT key. Refresh tokens are random values checked against the database, so rotating the key never signs anyone out.

```mermaid
sequenceDiagram
    participant Op as You
    participant A as API
    participant B as Browser

    Op->>A: Add key k2, make it current, keep k1 (restart)
    Note over A: Signs with k2,<br/>accepts k1 and k2 by kid
    B->>A: Call with access token signed by k1
    A-->>B: 200 (k1 still accepted)
    Op->>A: After 15 min, remove k1 (restart)
    B->>A: Call with a leftover k1 token, if any
    A-->>B: 401 TOKEN_INVALID
    B->>A: POST /api/auth/refresh
    A-->>B: 200 new token signed by k2
    B->>A: Retry, 200
```

**How it works**
- **Config:** `JWT_SIGNING_KEYS` holds a list of `kid:base64key` entries, and `JWT_CURRENT_KID` picks the one used for signing.
- **Signing:** new tokens are signed with the current key, and the key's `kid` goes in the token header.
- **Verifying:** the decoder looks up the key by `kid` and rejects unknown `kid`s with `TOKEN_INVALID`.
- **Spring setup:** use Nimbus's `JWKSource` with several `OctetSequenceKey`s behind Spring's `NimbusJwtDecoder`. No hand-written signature checks.
- **Restarts:** a restart is required either way, and restarts already happen on every deploy.

**When to rotate**
- **Immediately** if the key may have leaked: it appeared in a log, a commit, or a screenshot, or someone else got into the EC2 box or the `.env` file.
- **When moving hosts,** for example the planned AWS to Oracle Cloud move, because the environment is being rebuilt anyway.
- **Otherwise once a year.** A 256-bit random HMAC key doesn't get weaker with use, and access tokens live only 15 minutes. NIST's key-management guidance allows symmetric signing keys to be used for up to about two years. Yearly keeps a margin and keeps the procedure practiced.

**Generating a key:** `openssl rand -base64 32`. Keys are never committed, logged, or shared between environments (local and production use different keys).

---

## 4. Flow B: Report analysis

### 4.1 Upload request (synchronous part)

```mermaid
sequenceDiagram
    participant B as Browser
    participant A as API
    participant D as Postgres
    participant Q as Job executor

    B->>A: POST /api/reports (file, text, or sampleId)
    A->>A: Authenticate (User or Guest), get OwnerRef
    A->>A: Upload rate limit (per user or per IP)
    A->>A: Validate consent, size, type, magic bytes
    A->>D: Is today's LLM cap already reached?
    alt cap reached
        A-->>B: 429 CAPACITY
    else queue full
        A-->>B: 429 BUSY
    else accepted
        A->>A: Write bytes to temp file
        A->>D: INSERT report (owner, status = PENDING, expires_at by owner type)
        A->>Q: Submit job(reportId, tempPath)
        A-->>B: 202 { id, status: PENDING }
    end
```

**What gets rejected before a report row is created**
- **401:** no valid credential (`SESSION_INVALID`), or a bad bearer token (`TOKEN_EXPIRED` or `TOKEN_INVALID`).
- **429:** upload limit reached (`RATE_LIMITED`), daily cap already reached (`CAPACITY`), or queue full (`BUSY`).
- **400, 404, 413, 415:** validation failures, an unknown sample, too large a file, or an unsupported type.

**Queue check.** The executor's free capacity is checked before the temp file is written. If submitting still fails in a race, the temp file and report row are deleted and the request returns `429 BUSY`.

**Expiry on insert:** `created_at + 30 days` for a user, or the session's `expires_at` for a guest.

**Sample reports** skip the file steps. The job reads the bundled sample text from resources and starts at the masking stage.

### 4.2 Background job (asynchronous part)

```mermaid
flowchart TD
    S["Job starts<br/>status = PROCESSING"] --> T{"Source type?"}
    T -->|"text / sample"| RT["Use text as is"]
    T -->|"PDF"| PDF["PDFBox text extraction"]
    T -->|"image"| OCR["Tesseract OCR<br/>one job at a time"]
    PDF --> CHK{"Enough text<br/>on each page?"}
    CHK -->|"yes"| RT
    CHK -->|"no, scanned"| RENDER["Render page at 300 DPI"] --> OCR
    OCR --> RT
    RT --> DEL["Delete temp file"]
    DEL --> MASK["Mask personal data<br/>regex + OpenNLP"]
    MASK --> INT["Value integrity check"]
    INT --> CAP{"Daily LLM cap<br/>reached?"}
    CAP -->|"yes"| FAIL["status = FAILED<br/>error = CAPACITY"]
    CAP -->|"no"| L1["Step-1 LLM call<br/>biomarkers + collection date"]
    L1 --> V["Validate in code<br/>value in source, parse numbers,<br/>recompute flags, parse date"]
    V --> L2["Step-2 LLM call<br/>summary from validated data"]
    L2 --> SAVE[("One transaction:<br/>report_text + biomarkers +<br/>collected_on + report_summary")]
    SAVE --> DONE["status = DONE"]
    DONE --> STATS[("Update daily stats<br/>counts and timing only")]
    FAIL --> STATS
```

**Step details**

1. **Extraction.** The job picks a path by source type. Scanned PDF pages are detected per page (for example, fewer than 50 characters of text) so mixed PDFs work too. OCR is guarded by a semaphore with one permit.
   - **Until the Phase 4 OCR fallback exists:** a PDF with no usable text layer has nowhere to fall back to, so the job fails with `UNREADABLE`. Image uploads don't reach the job at all — they are rejected at upload with `415 UNSUPPORTED_FILE_TYPE` (contract 4.1).
2. **Temp file deletion.** Happens right after extraction in a `finally` block.
3. **Masking.**
   - Regex rules run first, then OpenNLP.
   - Date masking only applies next to birth-date labels ("DOB", "Date of Birth"), so the collection date survives for trends.
   - The integrity check skips any match that overlaps a number or a reference range in a results row. It logs a masking conflict (count only).
4. **Daily cap check.** Each LLM call increments today's `llm_usage` row in one atomic SQL statement. If the cap is reached, the job stops with `CAPACITY`.
5. **Step-1 LLM call.**
   - **Input:** the masked text inside `<report>` delimiters.
   - **Output:** the biomarker list plus an optional `collectedOn` date, parsed with `BeanOutputConverter`.
   - **Invalid JSON:** retry once, then fail with `EXTRACTION_FAILED`.
   - **Provider outage:** a provider error after retries fails with `LLM_UNAVAILABLE`.
6. **Validation.**
   - Rows whose `rawValue` isn't in the masked text are dropped.
   - Numbers are re-parsed from the raw strings, and flags are recomputed.
   - `collectedOn` is kept only if it parses as a real date that appears in the masked text and isn't in the future. Otherwise it's `null`.
   - If zero rows survive, the job fails with `NO_RESULTS_FOUND`.
7. **Step-2 LLM call.** Input is only the validated biomarker list as JSON. The model writes the summary and picks highlights only from rows flagged `LOW` or `HIGH`.
8. **Saving.** The masked text, the biomarkers, `collected_on`, the summary, and `status = DONE` are all written in one transaction at the end, so a report is never left with biomarkers but no summary. Until that transaction commits, the masked text exists only in JVM memory, which means **a report that fails stores no text at all**.
9. **Deleted mid-job.** If the report was deleted while processing (by the user, by account deletion, or by expiry), the save finds no row. The job stops without writing anything.
10. **Stats.** Daily counters are updated with counts and timing only.

### 4.3 Report status lifecycle

```mermaid
stateDiagram-v2
    [*] --> PENDING: upload accepted
    PENDING --> PROCESSING: worker picks up job
    PROCESSING --> DONE: summary saved
    PROCESSING --> FAILED: any step fails
    PENDING --> FAILED: server restarted
    PROCESSING --> FAILED: server restarted
    DONE --> [*]: deleted or expired
    FAILED --> [*]: deleted or expired
```

**Report failure codes** (stored in `report.error_code` and returned as `error.code`):

| Code | Meaning | What the user sees |
|---|---|---|
| `UNREADABLE` | No usable text found, even after OCR | "We couldn't read text from this file. Try a clearer image." |
| `DOCUMENT_TOO_LONG` | Extracted text is over the length or page limit | "This document is too long to process. Try a shorter report." |
| `NO_RESULTS_FOUND` | No lab values survived validation | "We didn't find lab results in this document." |
| `EXTRACTION_FAILED` | Model output invalid twice | "Something went wrong reading the results. Please try again." |
| `CAPACITY` | Daily LLM cap reached mid-job | "The demo has hit today's limit. Try again tomorrow." |
| `LLM_UNAVAILABLE` | Provider failed after retries | "The AI service is unavailable right now. Please try again later." |
| `INTERRUPTED` | Server restarted mid-job | "Processing was interrupted. Please upload again." |

`BUSY` is no longer a report failure. A full queue is rejected at upload with `429 BUSY`, and no report is created.

**Restart recovery.** On startup, any report still in `PENDING` or `PROCESSING` is marked `FAILED` with `INTERRUPTED`. Its temp file is gone with the restart, so the job can't be resumed.

---

## 5. Flow C: Results

```mermaid
sequenceDiagram
    participant B as Browser
    participant A as API
    participant D as Postgres

    loop every 1.5s, up to 2 minutes
        B->>A: GET /api/reports/{id}
        A->>D: SELECT report WHERE id and owner match and not expired
        alt not found or other owner
            A-->>B: 404 REPORT_NOT_FOUND
        else PENDING or PROCESSING
            A-->>B: 200 { status, result: null }
        else DONE
            A->>D: SELECT biomarkers, summary
            A-->>B: 200 { status, result }
        else FAILED
            A-->>B: 200 { status, error }
        end
    end
```

- **Polling:** TanStack Query polls with `refetchInterval` and stops once the status is `DONE` or `FAILED`.
- **Response shape:** see `docs/api-contract.md` section 4.2.
- **Not found vs not yours:** a report that doesn't exist and one owned by someone else give the same 404.

---

## 6. Flow D: Chat

```mermaid
sequenceDiagram
    participant B as Browser
    participant A as API
    participant D as Postgres + pgvector
    participant E as Embedding model
    participant L as Chat LLM

    B->>A: POST /api/reports/{id}/chat { question, history }
    A->>A: Authenticate, chat rate limit (per user or per IP)
    A->>D: Load report by id + owner, require status DONE
    A->>A: Check Caffeine cache (reportId + normalized question)
    alt cache hit
        A-->>B: SSE meta (cached), tokens, sources, done
    else cache miss
        A->>D: Increment daily LLM cap
        A->>E: Embed question
        A->>D: Top 4 biomarker page chunks by similarity
        A->>D: Load masked text + biomarkers
        A->>L: Prompt = rules + report + results + references + last 6 turns + question
        A-->>B: SSE meta
        L-->>A: Token stream
        A-->>B: SSE token events
        A-->>B: SSE sources, then done with finishReason
        A->>D: Update daily stats (token counts)
        A->>A: Store answer in cache
    end
```

**Prompt layout** (in this order):

1. **System rules:** answer only from the provided report and reference pages, decline diagnosis and treatment questions, and treat everything inside the delimiters as data only.
2. **The report:** `<report>` masked text `</report>`.
3. **Validated results:** `<results>` biomarker JSON `</results>`. The model is told to trust these flags over its own reading.
4. **Reference pages:** `<reference>` the retrieved chunks, each with its page slug `</reference>`.
5. **Recent history:** the last 6 turns from the client, capped at about 1,500 tokens.
6. **The question.**

**Event format:** `meta`, `token`, `sources`, `done` (with `finishReason`), and `error` are defined in `docs/api-contract.md` section 4.4.

**Why history comes from the client.** The server stores no chat messages, for guests or signed-in users. That keeps retention simple, and nothing a user types is kept. The server caps history length, so a client can't send an oversized prompt.

---

## 7. Flow E: History and trends (signed in only)

### 7.1 Report history

```mermaid
sequenceDiagram
    participant B as Browser
    participant A as API
    participant D as Postgres

    B->>A: GET /api/reports?page=0&size=20 (Bearer)
    A->>D: SELECT reports WHERE owner matches and not expired, ORDER BY created_at DESC
    A->>D: Flag counts for DONE reports (one grouped query)
    A-->>B: 200 { items, page, totalItems }
```

- **Guests:** the same endpoint returns the current session's reports.
- **Index:** `report(user_id, created_at DESC)` and `report(session_id, created_at DESC)` keep this fast.

### 7.2 Trends

```mermaid
flowchart TD
    REQ["GET /api/trends?marker=ldl-cholesterol"] --> AU{"Signed in?"}
    AU -->|"no"| E401["401 AUTH_REQUIRED"]
    AU -->|"yes"| Q[("Biomarkers JOIN reports<br/>user_id = caller, status DONE, not expired")]
    Q --> M["Match by biomarker_slug,<br/>else normalized test name"]
    M --> N["Keep numeric values only"]
    N --> D["Date each point:<br/>collected_on, else upload date"]
    D --> G["Group by unit"]
    G --> S["Sort each group by date"]
    S --> RES["200 { marker, series by unit }"]
```

**Details**
- **Normalized test name:** lowercase, with punctuation and repeated spaces removed. It is computed in code at save time and stored in `biomarker.test_name_norm`, so trend queries can use an index.
- **Units:** values are never converted between units. Each unit becomes its own chart series.
- **Marker picker:** `GET /api/trends/markers` runs one grouped query: markers present in at least two `DONE` reports for this user.
- **Expired reports:** trend points disappear when their report expires after 30 days. The UI says "History is kept for 30 days".

---

## 8. Flow F: Account deletion and admin stats

### 8.1 Account deletion

```mermaid
sequenceDiagram
    participant B as Browser
    participant A as API
    participant D as Postgres

    B->>A: DELETE /api/me (Bearer)
    A->>D: DELETE app_user WHERE id = caller
    Note over D: ON DELETE CASCADE removes reports,<br/>report_text, biomarkers, summaries,<br/>and refresh tokens
    A-->>B: 204
    B->>B: Clear all tokens
    B->>A: POST /api/sessions (continue as guest)
```

- **In-flight jobs:** jobs for deleted reports stop on their next save, because the row is gone (4.2, step 9).
- **Access token:** it would still pass signature checks for up to 15 minutes. Every user-scoped query finds nothing, and `/api/me` returns `401 AUTH_REQUIRED` once the user row is gone.

### 8.2 Admin stats

```mermaid
sequenceDiagram
    participant B as Browser (admin)
    participant A as API
    participant D as Postgres

    B->>A: GET /api/admin/stats?days=14 (Bearer, role ADMIN)
    A->>A: Filter chain + @PreAuthorize check ADMIN
    A->>D: Read llm_usage, daily_stats, daily_failure, daily_processing
    A->>D: COUNT app_user, COUNT active reports by owner type
    A-->>B: 200 aggregated stats
```

- **What it reads:** only counters and counts. No report rows, report ids, user ids, or names are returned.
- **Why stats survive retention:** they're stored as daily counters, so they outlive the reports they describe. They're kept for 90 days.

---

## 9. Flow G: Background flows

### 9.1 Loading biomarker content (on startup)

```mermaid
flowchart LR
    F["content/biomarkers/*.md<br/>bundled in the backend image"] --> H["Hash each file"]
    H --> CMP{"Hash already<br/>in vector_store?"}
    CMP -->|"yes"| SKIP["Skip"]
    CMP -->|"no"| CH["Split by heading<br/>~500 tokens per chunk"]
    CH --> EMB["Embed chunks"]
    EMB --> DEL["Delete old chunks for this slug"]
    DEL --> INS[("Insert into vector_store<br/>metadata: slug, heading, hash")]
```

**Details**
- **Startup cost:** content is only re-embedded when a file changes, so restarts don't spend embedding quota.
- **One source of truth:** the frontend's static pages and the backend's RAG index read the same markdown folder. A small build step copies it into the backend image.
- **No report data:** `vector_store` only ever holds reference content, so retention doesn't need to touch it.

### 9.2 Retention cleanup

```mermaid
flowchart LR
    CRON["@Scheduled every 15 min"] --> Q1[("DELETE expired guest sessions")]
    Q1 --> C1["Cascade: guest reports<br/>and all their data"]
    CRON --> Q2[("DELETE expired user reports")]
    Q2 --> C2["Cascade: report_text,<br/>biomarkers, summaries"]
    CRON --> Q3[("DELETE refresh tokens<br/>expired or revoked over 1 day ago")]
    CRON --> Q4["Delete temp files<br/>older than 10 min"]
    DAILY["@Scheduled daily"] --> Q5[("DELETE app_user<br/>no sign-in for 180 days")]
    DAILY --> Q6[("DELETE stats rows<br/>older than 90 days")]
```

**Details**
- **Cascading deletes:** foreign keys use `ON DELETE CASCADE` from both `session` and `app_user`, so one delete removes everything below it.
- **Read-time check:** every read also filters on `expires_at > now()`, so expired data is never served even if the cleanup job hasn't run yet.
- **Revoked tokens:** kept for one day after revocation, so reuse can still be detected during that time.

### 9.3 Rate limit and quota

```mermaid
flowchart TD
    R["Incoming request"] --> AUTH["Authenticate<br/>(section 3.6)"]
    AUTH --> KEY{"Signed in?"}
    KEY -->|"yes"| KU["Bucket key = user id"]
    KEY -->|"no"| KI["Bucket key = client IP<br/>from X-Forwarded-For set by Caddy"]
    KU --> B{"Bucket has tokens?"}
    KI --> B
    B -->|"no"| R429["429 RATE_LIMITED + Retry-After<br/>count rejection in daily stats"]
    B -->|"yes"| H["Handler"]
    H --> LLMQ{"Needs an LLM call?"}
    LLMQ -->|"no"| OK["Continue"]
    LLMQ -->|"yes"| INC[("UPDATE llm_usage SET calls = calls + 1<br/>WHERE day = today AND calls below cap<br/>RETURNING calls")]
    INC -->|"row updated"| OK
    INC -->|"no row updated"| CAPX["Stop: CAPACITY"]
```

**Details**
- **Bucket types:** uploads (10 per hour) and chat (30 per hour) are keyed by user or IP. Session creation (20 per hour) and auth endpoints (10 per minute) are always keyed by IP.
- **Buckets live in memory.** Losing them on restart is fine.
- **Daily cap:** the conditional `UPDATE` checks and increments in one atomic step. Today's row is created first with `INSERT … ON CONFLICT DO NOTHING`.
- **Counting:** each upload costs 2 LLM calls. Each uncached chat message costs 1 call plus 1 embedding call.

---

## 10. What is never stored or logged

- Original files (temp file only, deleted within seconds)
- Unmasked text (memory only)
- Chat questions and answers
- Google ID tokens, and users' email addresses or photos
- Raw session tokens or refresh tokens (hashes only); access tokens (not stored at all)
- Any token in logs, including in exception messages and request logging
- Any report content in logs. Logs hold ids, status changes, error codes, token counts, and timings only.

---

## 11. Open questions to decide during build

1. **Polling vs SSE for job status.** Polling is simpler and enough here. SSE for status could come later.
2. **Masking on mixed PDFs.** The current design merges all page text first, then masks once. That's simpler, but page boundaries are lost. Decide whether page numbers matter in the UI.
3. **Chat cache key.** Normalizing (lowercase, trimmed) catches more repeats than exact matching. The cache key always includes the report id, so answers never leak across reports or owners.
4. **Job queue persistence.** In-memory is fine for one instance. If you ever run two instances, move jobs to a Postgres table polled with `SELECT … FOR UPDATE SKIP LOCKED`.

**Decided**
- **Signing key rotation:** rotate yearly, when moving hosts, and immediately on a suspected leak, using `kid` with overlapping keys. Rotation never signs anyone out, because refresh tokens don't depend on the key and the client refreshes once on `TOKEN_INVALID`. See 3.7.
- **Staying signed in across tabs and visits:** yes, through Google auto sign-in, with one refresh family per tab and logout broadcast to every tab. Refresh tokens stay in `sessionStorage`. `localStorage` was rejected because it keeps a long-lived token in the browser and makes tabs share one refresh token, which would need cross-tab locking. See 3.3 and 3.5.
