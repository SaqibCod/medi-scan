# Medi-Scan: API Contract

This document defines the HTTP API between the Next.js client and the Spring Boot backend. It matches the v3 project plan and the data flow design. If the code and this document disagree, fix one of them before merging.

**Version:** 2.2 (adds the `DOCUMENT_TOO_LONG` failure code; images rejected until OCR ships)

---

## 1. Conventions

### 1.1 Base URL

| Environment | Base URL |
|---|---|
| Local | `http://localhost:8080` |
| Production | `https://<DOMAIN>` (the DuckDNS or custom domain behind Caddy) |

All endpoints except health live under `/api`.

### 1.2 Formats

- **Request and response bodies:** JSON (`application/json`, UTF-8), except file uploads (`multipart/form-data`) and chat responses (`text/event-stream`).
- **Field names:** `camelCase`.
- **IDs:** UUID v4 strings.
- **Timestamps:** ISO-8601 in UTC, for example `2026-10-02T09:30:00Z`.
- **Missing values:** `null`. Fields are never left out of a response; they are present with `null`.
- **Enums:** uppercase strings. Clients must handle values they don't recognize (see 9.2).

### 1.3 Headers

**Request headers**

| Header | When | Notes |
|---|---|---|
| `X-Session-Token` | Guest calls to `/api/reports/**` | Token from `POST /api/sessions` |
| `Authorization` | Signed-in calls | `Bearer <access token>` from `/api/auth/google` or `/api/auth/refresh`. If both credentials are sent, this one wins. |
| `Content-Type` | Requests with a body | `application/json` or `multipart/form-data` |
| `Accept` | Chat | `text/event-stream` |

**Response headers**

| Header | When | Notes |
|---|---|---|
| `X-Request-Id` | Every response | Random id, also written to server logs. Shown in error UI for debugging. |
| `Retry-After` | 429 responses | Seconds to wait before retrying |
| `Location` | 202 from `POST /api/reports` | `/api/reports/{id}` |

### 1.4 CORS

- **Allowed origin:** the value of `ALLOWED_ORIGIN` (the Vercel URL). No wildcards.
- **Allowed methods:** `GET`, `POST`, `DELETE`, `OPTIONS`.
- **Allowed request headers:** `Content-Type`, `X-Session-Token`, `Authorization`, `Accept`.
- **Exposed response headers:** `X-Request-Id`, `Retry-After`, `Location`, `WWW-Authenticate`.
- **Credentials:** not used. No cookies.

### 1.5 Errors

Every error response uses RFC 9457 Problem Details (`application/problem+json`). Spring Boot supports this format natively through `ProblemDetail`. Medi-Scan adds `code` and `requestId`.

```json
{
  "type": "https://medi-scan.dev/errors/file-too-large",
  "title": "File too large",
  "status": 413,
  "detail": "The file is 14.2 MB. The limit is 10 MB.",
  "code": "FILE_TOO_LARGE",
  "requestId": "b7e2c1d0-4f1a-4c55-9d8e-2a6f0e1c9b3a"
}
```

- **`code`** is the stable field the client switches on. `title` and `detail` are for humans and may change.
- **Validation errors** add an `errors` array:

```json
{
  "type": "https://medi-scan.dev/errors/validation",
  "title": "Invalid request",
  "status": 400,
  "detail": "One or more fields are invalid.",
  "code": "VALIDATION_ERROR",
  "requestId": "…",
  "errors": [
    { "field": "question", "message": "must be between 1 and 500 characters" }
  ]
}
```

The full list of codes is in section 8.

### 1.6 Rate limits

| Limit | Scope | Default | Exceeded |
|---|---|---|---|
| Session creation | Per IP | 20 per hour | 429 `RATE_LIMITED` |
| Google sign-in and token refresh | Per IP | 10 per minute | 429 `RATE_LIMITED` |
| Uploads | Per user when signed in, otherwise per IP | 10 per hour | 429 `RATE_LIMITED` |
| Chat messages | Per user when signed in, otherwise per IP | 30 per hour | 429 `RATE_LIMITED` |
| LLM calls | Whole app | 500 per day (UTC) | 429 `CAPACITY`, or report fails with `CAPACITY` |

429 responses always include `Retry-After`. For `CAPACITY`, it is the number of seconds until midnight UTC.

---

## 2. Authentication

### 2.1 Callers and credentials

| Caller | Credential | Can use |
|---|---|---|
| Anonymous | none | `POST /api/sessions`, `POST /api/auth/*`, `GET /api/samples`, health |
| Guest | `X-Session-Token` | All of the above, plus `/api/reports/**` |
| User | `Authorization: Bearer <access token>` | All of the above, plus `/api/me/**` and `/api/trends` |
| Admin | Bearer token with role `ADMIN` | All of the above, plus `/api/admin/**` |

**How the server reads credentials**
- **Bearer token sent:** it must be valid. If it isn't, the request fails with `401 TOKEN_EXPIRED` or `401 TOKEN_INVALID`, even if a session token was also sent. The server never falls back to the guest token.
- **Report endpoints with no valid credential:** return `401 SESSION_INVALID`.
- **Signed-in-only endpoints called by a guest or anonymous caller:** return `401 AUTH_REQUIRED`.
- **Admin endpoints called by a non-admin user:** return `403 FORBIDDEN`.
- **Bearer failures:** responses include a `WWW-Authenticate: Bearer error="invalid_token"` header.

**Client token storage (the $0 setup)**
- **Access token:** in memory only. It is lost on reload.
- **Refresh token:** in `sessionStorage`, so each tab has its own. On reload, call `POST /api/auth/refresh` to get a new access token.
- **Guest session token:** in `sessionStorage`. It is created lazily, on the first guest action that needs it (an upload, picking a sample, or the history list), not on page load.

**New tabs and returning visits (Google auto sign-in)**
- **Auto sign-in:** a tab with no refresh token asks Google Identity Services for an ID token with auto sign-in turned on (`auto_select: true`). If the user signed in before and is still logged into Google, Google returns a token without a click. The client then calls `POST /api/auth/google` as usual.
- **Separate families:** each tab gets its own refresh token family. Tabs never share a refresh token, so tabs can't race each other on refresh.
- **When auto sign-in doesn't happen:** for example, the browser doesn't support it, Google has paused the prompt, or there are several Google accounts. The tab continues as a guest and shows the normal "Sign in with Google" button.
- **Waiting for sign-in:** while sign-in is being checked, the client waits up to about 2 seconds before making report calls, so a report opened in a new tab isn't fetched as a guest and wrongly returns 404.

**Refreshing an access token.** When a bearer call fails with `401 TOKEN_EXPIRED` or `401 TOKEN_INVALID`:
1. Call `POST /api/auth/refresh` once.
2. If it succeeds, retry the original call once.
3. If the refresh fails with `401 REFRESH_TOKEN_INVALID`, clear the tokens and show the user as signed out.

`TOKEN_INVALID` is included because the server's signing key may have been rotated (see 2.8). The refresh token doesn't depend on that key, so it still works.

Within a tab, if several calls fail at the same time, share one refresh call between them. Two parallel refreshes from the same tab would look like token reuse and revoke that tab's family.

**Signing out in every tab.** On logout, the tab that logged out sends a `BroadcastChannel` message. Every open tab then:
- calls `POST /api/auth/logout` with its own refresh token
- clears its tokens
- calls Google's `disableAutoSelect()`, so the user isn't signed straight back in

### 2.2 `POST /api/sessions`

Starts an anonymous session. The client calls this lazily, on the first guest action that needs it (not on page load), stores the token in `sessionStorage`, and sends it as `X-Session-Token` on guest report calls.

**Auth:** none
**Request body:** none

**Response `201 Created`**

```json
{
  "token": "mS9xQ2…43 chars, base64url",
  "expiresAt": "2026-10-03T09:30:00Z"
}
```

| Field | Type | Notes |
|---|---|---|
| `token` | string | 32 random bytes, base64url-encoded (43 chars). Returned once; the server stores only its hash. |
| `expiresAt` | timestamp | 24 hours after creation. Reports in this session expire at the same time. |

**Errors:** `429 RATE_LIMITED`

**Session errors on other endpoints.** Any `/api/reports/**` call with no bearer token and a missing, unknown, or expired session token returns `401 SESSION_INVALID`. The client should then create a new session and drop any report ids it was holding.

### 2.3 `POST /api/auth/google`

Signs in with Google. Exchanges a Google ID token for app tokens. If a guest session token is sent, that session's reports move to the user.

**Auth:** none (the Google ID token is the credential)

**Request body**

```json
{
  "idToken": "eyJhbGciOiJSUzI1NiIs…",
  "guestSessionToken": "mS9xQ2…"
}
```

| Field | Type | Required | Rules |
|---|---|---|---|
| `idToken` | string | yes | Google ID token from Google Identity Services |
| `guestSessionToken` | string | no | Current guest token. Its reports move to the user. |

**What the server checks:**
- **Signature:** against Google's published keys (JWKS).
- **Issuer:** `iss` is `accounts.google.com` or `https://accounts.google.com`.
- **Audience:** `aud` equals `GOOGLE_CLIENT_ID`.
- **Expiry:** `exp` is in the future.

The user is found or created by `sub`. The role is `ADMIN` if `sub` is listed in `ADMIN_GOOGLE_SUBS`, otherwise `USER`.

**Response `200 OK`**

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9…",
  "accessTokenExpiresAt": "2026-10-02T09:45:00Z",
  "refreshToken": "r8Kp…43 chars, base64url",
  "refreshTokenExpiresAt": "2026-10-09T09:30:00Z",
  "user": {
    "id": "0b6e…",
    "displayName": "Saq",
    "role": "USER",
    "createdAt": "2026-10-02T09:30:00Z"
  },
  "claimedReportCount": 2,
  "newUser": true
}
```

| Field | Type | Notes |
|---|---|---|
| `accessToken` | string | JWT, valid for 15 minutes. Send as `Authorization: Bearer`. |
| `accessTokenExpiresAt` | timestamp | Lets the client refresh early instead of waiting for a 401 |
| `refreshToken` | string | 32 random bytes, base64url. Stored hashed on the server. |
| `refreshTokenExpiresAt` | timestamp | 7 days, and never more than 30 days after the original sign-in |
| `user` | object | See `User` in 9.1 |
| `claimedReportCount` | integer | Reports moved from the guest session. 0 if none. |
| `newUser` | boolean | True on first sign-in. The UI can show a welcome. |

**Access token claims:** `sub` (user id), `role`, `iss` (`JWT_ISSUER`), `aud` (`medi-scan-api`), `iat`, `exp`, `jti`. Signed with HS256. The header carries a `kid` naming the signing key (see 2.8). The client must treat it as opaque and not read the claims.

**Errors**

| Status | Code | When |
|---|---|---|
| 400 | `VALIDATION_ERROR` | `idToken` missing |
| 401 | `GOOGLE_TOKEN_INVALID` | Signature, issuer, audience, or expiry check failed |
| 429 | `RATE_LIMITED` | Too many sign-in attempts from this IP |

An invalid or expired `guestSessionToken` is not an error. The sign-in succeeds with `claimedReportCount: 0`.

### 2.4 `POST /api/auth/refresh`

Swaps a refresh token for a new access token and a new refresh token. The old refresh token stops working.

**Auth:** none (the refresh token is the credential)

**Request body**

```json
{ "refreshToken": "r8Kp…" }
```

**Response `200 OK`:** the same shape as `POST /api/auth/google`, without `claimedReportCount` and `newUser`.

**Reuse detection.** If a refresh token that was already used is sent again, the server revokes every token in that sign-in's family and returns `401 REFRESH_TOKEN_INVALID`. The user must sign in again.

**Errors**

| Status | Code | When |
|---|---|---|
| 400 | `VALIDATION_ERROR` | `refreshToken` missing |
| 401 | `REFRESH_TOKEN_INVALID` | Unknown, expired, revoked, or reused token |
| 429 | `RATE_LIMITED` | Too many refresh attempts from this IP |

### 2.5 `POST /api/auth/logout`

Revokes the refresh token's whole family. The access token stays valid until it expires (at most 15 minutes), so the client also deletes it from memory.

**Auth:** none

**Request body**

```json
{ "refreshToken": "r8Kp…" }
```

**Response `204 No Content`.** Also returns 204 for an unknown or already revoked token, so logout always succeeds from the client's point of view.

### 2.6 `GET /api/me`

Returns the signed-in user.

**Auth:** Bearer

**Response `200 OK`**

```json
{
  "id": "0b6e…",
  "displayName": "Saq",
  "role": "USER",
  "createdAt": "2026-10-02T09:30:00Z",
  "reportCount": 4
}
```

**Errors:** `401 AUTH_REQUIRED`, `401 TOKEN_EXPIRED`, `401 TOKEN_INVALID`

### 2.7 `DELETE /api/me`

Deletes the account and everything linked to it: reports, report text, biomarkers, summaries, and refresh tokens.

**Auth:** Bearer

**Response `204 No Content`.** The client clears all tokens and returns to guest mode.

**Errors:** `401 AUTH_REQUIRED`, `401 TOKEN_EXPIRED`, `401 TOKEN_INVALID`

### 2.8 Signing key rotation

Access tokens carry a `kid` header that names the key they were signed with. The server signs with the current key and accepts tokens signed by any key in `JWT_SIGNING_KEYS`.

**To rotate:**
1. Add the new key, make it current, and restart. Keep the old key in the list.
2. After 15 minutes (the access token lifetime), remove the old key.

Clients see no change. Even if the old key is removed early, affected calls get `401 TOKEN_INVALID`, the client refreshes once (see 2.1), and carries on.

**When:**
- immediately if the key may have leaked
- when moving hosts
- at least once a year

---

## 3. Samples

### `GET /api/samples`

Lists the bundled synthetic reports for the "Try a sample report" buttons.

**Auth:** none

**Response `200 OK`**

```json
[
  {
    "id": "lipid-panel",
    "title": "Lipid panel",
    "description": "Cholesterol and triglycerides, with high LDL.",
    "markerCount": 5
  },
  {
    "id": "cbc",
    "title": "Complete blood count",
    "description": "Red and white blood cells, all in range.",
    "markerCount": 12
  },
  {
    "id": "thyroid-panel",
    "title": "Thyroid panel",
    "description": "TSH and free T4, with low TSH.",
    "markerCount": 3
  }
]
```

| Field | Type | Notes |
|---|---|---|
| `id` | string | Slug, used as `sampleId` when creating a report |
| `title` | string | Button label |
| `description` | string | One line shown under the label |
| `markerCount` | integer | Number of lab values in the sample |

The response can be cached by the client for the whole session (`Cache-Control: public, max-age=3600`).

---

## 4. Reports

Every report endpoint accepts a guest session token or a bearer token (see 2.1). A report belongs to exactly one owner: the guest session that created it, or the signed-in user. Callers only ever see their own reports.

- **Expiry:** a guest's report expires with the session (24 hours). A signed-in user's report expires 30 days after creation.
- **Bearer errors:** besides the errors listed for each endpoint, any call with a bad bearer token can return `401 TOKEN_EXPIRED` or `401 TOKEN_INVALID`.

### 4.1 `POST /api/reports`

Creates a report and queues it for processing. Accepts exactly one of: a file, pasted text, or a sample id.

**Auth:** guest `X-Session-Token` or `Authorization: Bearer`

**Option A: file upload** (`multipart/form-data`)

| Part | Type | Required | Rules |
|---|---|---|---|
| `file` | file | yes | PDF, PNG, or JPEG. Max 10 MB. The file signature (magic bytes) must match the type. |
| `consent` | string | yes | Must be `"true"` |

**Until Phase 4 (OCR):** PNG and JPEG uploads return `415 UNSUPPORTED_FILE_TYPE`. Only text-based PDFs, pasted text, and samples are accepted. A PDF with no usable text layer is accepted here and then fails the job with `UNREADABLE` (see 8.2).

**Option B: pasted text** (`application/json`)

```json
{
  "text": "LIPID PANEL\nTotal Cholesterol  238 mg/dL  <200\n…",
  "consent": true
}
```

| Field | Type | Required | Rules |
|---|---|---|---|
| `text` | string | yes | 20 to 50,000 characters |
| `consent` | boolean | yes | Must be `true` |

**Option C: sample report** (`application/json`)

```json
{
  "sampleId": "lipid-panel",
  "consent": true
}
```

| Field | Type | Required | Rules |
|---|---|---|---|
| `sampleId` | string | yes | An `id` from `GET /api/samples` |
| `consent` | boolean | yes | Must be `true` |

Sending both `text` and `sampleId`, or neither, is a `400 VALIDATION_ERROR`.

**Response `202 Accepted`**

Headers: `Location: /api/reports/6f1c2a9e-…`

```json
{
  "id": "6f1c2a9e-3b4d-4e8f-9a1b-2c3d4e5f6a7b",
  "status": "PENDING",
  "sourceType": "PDF",
  "createdAt": "2026-10-02T09:31:00Z",
  "expiresAt": "2026-10-03T09:30:00Z"
}
```

**Errors**

| Status | Code | When |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Missing consent, bad text length, both or neither of `text` and `sampleId` |
| 400 | `FILE_SIGNATURE_MISMATCH` | File content doesn't match its declared type |
| 401 | `SESSION_INVALID` | Missing, unknown, or expired token |
| 404 | `SAMPLE_NOT_FOUND` | Unknown `sampleId` |
| 413 | `FILE_TOO_LARGE` | File over 10 MB |
| 415 | `UNSUPPORTED_FILE_TYPE` | Anything other than PDF, PNG, or JPEG. Until Phase 4, also PNG and JPEG. |
| 429 | `RATE_LIMITED` | Upload limit for this IP reached |
| 429 | `CAPACITY` | Daily LLM cap already reached, so the report is not created |
| 429 | `BUSY` | Job queue is full, so the report is not created |

### 4.2 `GET /api/reports/{id}`

Returns the report's status, and the results once processing is done. The client polls this every 1.5 seconds until `status` is `DONE` or `FAILED`, for up to 2 minutes.

**Auth:** guest `X-Session-Token` or `Authorization: Bearer`

**Path parameters**

| Name | Type | Notes |
|---|---|---|
| `id` | UUID | Report id from `POST /api/reports` |

**Response `200 OK`**

The response shape depends on `status`. Fields that don't apply to the current status are `null`.

**While processing (`PENDING` or `PROCESSING`)**

```json
{
  "id": "6f1c2a9e-…",
  "status": "PROCESSING",
  "sourceType": "PDF",
  "createdAt": "2026-10-02T09:31:00Z",
  "expiresAt": "2026-10-03T09:30:00Z",
  "error": null,
  "result": null
}
```

**When done (`DONE`)**

```json
{
  "id": "6f1c2a9e-…",
  "status": "DONE",
  "sourceType": "PDF",
  "createdAt": "2026-10-02T09:31:00Z",
  "expiresAt": "2026-10-03T09:30:00Z",
  "error": null,
  "result": {
    "collectedOn": "2026-09-28",
    "biomarkers": [
      {
        "id": "a1b2c3d4-…",
        "testName": "LDL Cholesterol",
        "rawValue": "162",
        "numericValue": 162,
        "unit": "mg/dL",
        "referenceRangeText": "<100",
        "refLow": null,
        "refHigh": 100,
        "flag": "HIGH",
        "biomarkerSlug": "ldl-cholesterol"
      },
      {
        "id": "e5f6a7b8-…",
        "testName": "Urine Protein",
        "rawValue": "Negative",
        "numericValue": null,
        "unit": null,
        "referenceRangeText": "Negative",
        "refLow": null,
        "refHigh": null,
        "flag": "UNKNOWN",
        "biomarkerSlug": null
      }
    ],
    "summary": "Most of your results are in the normal range. Your LDL cholesterol is higher than the reference range on this report.",
    "highlights": [
      "LDL Cholesterol is 162 mg/dL, above the reference range of <100."
    ],
    "counts": { "total": 2, "low": 0, "normal": 0, "high": 1, "unknown": 1 }
  }
}
```

**When failed (`FAILED`)**

```json
{
  "id": "6f1c2a9e-…",
  "status": "FAILED",
  "sourceType": "IMAGE",
  "createdAt": "2026-10-02T09:31:00Z",
  "expiresAt": "2026-10-03T09:30:00Z",
  "error": {
    "code": "UNREADABLE",
    "message": "We couldn't read text from this file. Try a clearer image."
  },
  "result": null
}
```

A failed report is still a `200` response. The request succeeded; the processing didn't.

`result.collectedOn` is the sample collection date (`YYYY-MM-DD`) when step 1 can find it on the report, otherwise `null`. Trends use it to date each point.

**Errors**

| Status | Code | When |
|---|---|---|
| 400 | `VALIDATION_ERROR` | `id` isn't a valid UUID |
| 401 | `SESSION_INVALID` | Missing, unknown, or expired token |
| 404 | `REPORT_NOT_FOUND` | No such report, it belongs to another session, or it has expired |

The API never says whether a report exists in another session. All of these cases return the same 404.

### 4.3 `DELETE /api/reports/{id}`

Deletes the report and everything derived from it (masked text, biomarkers, summary).

**Auth:** guest `X-Session-Token` or `Authorization: Bearer`

**Response `204 No Content`** with an empty body.

Deleting a report that is still processing is allowed. The job notices the report is gone and stops without saving.

**Errors:** `401 SESSION_INVALID`, `404 REPORT_NOT_FOUND`

### 4.4 `POST /api/reports/{id}/chat`

Asks a question about a report. The answer streams back as Server-Sent Events.

**Auth:** guest `X-Session-Token` or `Authorization: Bearer`
**Request headers:** `Content-Type: application/json`, `Accept: text/event-stream`

**Request body**

```json
{
  "question": "Why is my LDL flagged?",
  "history": [
    { "role": "user", "content": "What does this report cover?" },
    { "role": "assistant", "content": "This is a lipid panel. It measures…" }
  ]
}
```

| Field | Type | Required | Rules |
|---|---|---|---|
| `question` | string | yes | 1 to 500 characters after trimming |
| `history` | array | no | Up to 6 items, oldest first. Defaults to empty. |
| `history[].role` | string | yes | `user` or `assistant` |
| `history[].content` | string | yes | 1 to 2,000 characters |

The server stores no chat messages. The client keeps the conversation and sends the most recent turns with each question. If the history is too long for the prompt, the server drops the oldest turns.

**Errors before the stream starts** (normal JSON Problem Details response, not SSE)

| Status | Code | When |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Bad question or history |
| 401 | `SESSION_INVALID` | Missing, unknown, or expired token |
| 404 | `REPORT_NOT_FOUND` | No such report in this session |
| 409 | `REPORT_NOT_READY` | Report status isn't `DONE` |
| 429 | `RATE_LIMITED` | Chat limit for this IP reached |
| 429 | `CAPACITY` | Daily LLM cap reached |

**Response `200 OK`** (`Content-Type: text/event-stream`)

The stream sends these events in order: any number of `token` events, then one `sources` event, then one `done` event. If something fails mid-stream, an `error` event replaces `sources` and `done`.

```
event: meta
data: {"answerId":"c4d5e6f7-…","cached":false}

event: token
data: {"text":"Your LDL cholesterol is "}

event: token
data: {"text":"162 mg/dL, which is above the range of <100 printed on your report."}

event: sources
data: {"report":true,"pages":[{"slug":"ldl-cholesterol","title":"LDL Cholesterol","heading":"What high LDL means"}]}

event: done
data: {"finishReason":"COMPLETE"}
```

**Event types**

| Event | Data | Notes |
|---|---|---|
| `meta` | `{ answerId, cached }` | Always first. `cached` is true when the answer comes from the cache. |
| `token` | `{ text }` | A piece of the answer. Append in order. |
| `sources` | `{ report, pages[] }` | `report` is true if the answer used the report. `pages` lists biomarker pages used. |
| `done` | `{ finishReason }` | `COMPLETE`, `DECLINED` (diagnosis or treatment question), or `NOT_IN_CONTEXT` (answer not in the report or pages) |
| `error` | `{ code, message }` | Stream ends after this. Codes: `LLM_UNAVAILABLE`, `CAPACITY`, `INTERNAL_ERROR`. |

**Chat history is never stored,** for guests or signed-in users.

**Keep-alive.** The server sends an SSE comment line (`: ping`) every 15 seconds while the model is thinking, so proxies don't close the connection.

**Client note.** The browser's built-in `EventSource` only supports GET requests and can't send custom headers. Use `fetch` and read the response body as a stream, or a library such as `@microsoft/fetch-event-source`.

### 4.5 `GET /api/reports`

Lists the caller's reports, newest first. For a guest, these are the current session's reports. For a user, these are all their reports that haven't expired.

**Auth:** guest `X-Session-Token` or `Authorization: Bearer`

**Query parameters**

| Name | Type | Default | Rules |
|---|---|---|---|
| `page` | integer | 0 | 0 or more |
| `size` | integer | 20 | 1 to 50 |
| `status` | string | all | Optional filter: `PENDING`, `PROCESSING`, `DONE`, or `FAILED` |

**Response `200 OK`**

```json
{
  "items": [
    {
      "id": "6f1c2a9e-…",
      "status": "DONE",
      "sourceType": "PDF",
      "createdAt": "2026-10-02T09:31:00Z",
      "expiresAt": "2026-11-01T09:31:00Z",
      "collectedOn": "2026-09-28",
      "counts": { "total": 5, "low": 0, "normal": 4, "high": 1, "unknown": 0 },
      "errorCode": null
    }
  ],
  "page": 0,
  "size": 20,
  "totalItems": 1,
  "totalPages": 1
}
```

List items are summaries. `counts` and `collectedOn` are `null` unless the report is `DONE`. Use `GET /api/reports/{id}` for the full result.

**Errors:** `400 VALIDATION_ERROR`, `401 SESSION_INVALID`

---

## 5. Trends

### `GET /api/trends`

Returns one biomarker's values across the signed-in user's reports, for a line chart.

**Auth:** Bearer (signed in only)

**Query parameters**

| Name | Type | Required | Rules |
|---|---|---|---|
| `marker` | string | yes | A `biomarkerSlug` (for example `ldl-cholesterol`), or a test name if the marker has no slug |

**Matching rules**
- **Matching:** biomarkers match by `biomarkerSlug` when present. Otherwise they match by normalized test name (lowercase, punctuation and extra spaces removed).
- **Numeric only:** only `DONE` reports and numeric values are included. Qualitative values such as `Negative` are left out.
- **Grouping by unit:** points are grouped by unit, and each group is drawn as its own chart. Values are never converted between units.
- **Dates:** each point is dated by `collectedOn`, or by the upload date when that is missing. `dateSource` says which.

**Response `200 OK`**

```json
{
  "marker": "ldl-cholesterol",
  "displayName": "LDL Cholesterol",
  "series": [
    {
      "unit": "mg/dL",
      "points": [
        {
          "reportId": "1a2b…",
          "date": "2026-03-14",
          "dateSource": "COLLECTED",
          "value": 148,
          "refLow": null,
          "refHigh": 100,
          "flag": "HIGH"
        },
        {
          "reportId": "6f1c…",
          "date": "2026-09-28",
          "dateSource": "COLLECTED",
          "value": 162,
          "refLow": null,
          "refHigh": 100,
          "flag": "HIGH"
        }
      ]
    }
  ]
}
```

Points are sorted by date, oldest first. If nothing matches, `series` is an empty array, not an error.

### `GET /api/trends/markers`

Lists the biomarkers that appear in at least two of the user's `DONE` reports, for the trend picker.

**Auth:** Bearer (signed in only)

**Response `200 OK`**

```json
[
  { "marker": "ldl-cholesterol", "displayName": "LDL Cholesterol", "reportCount": 3 },
  { "marker": "tsh", "displayName": "TSH", "reportCount": 2 }
]
```

**Errors for both trend endpoints:**
- `400 VALIDATION_ERROR` when `marker` is missing.
- `401 AUTH_REQUIRED`, `401 TOKEN_EXPIRED`, `401 TOKEN_INVALID`.

---

## 6. Admin

### `GET /api/admin/stats`

Returns aggregated usage numbers for the admin dashboard. It contains counts only: no report content, report ids, or user names.

**Auth:** Bearer with role `ADMIN`

**Query parameters**

| Name | Type | Default | Rules |
|---|---|---|---|
| `days` | integer | 14 | 1 to 90. Number of past days to include (UTC). |

**Response `200 OK`**

```json
{
  "from": "2026-09-19",
  "to": "2026-10-02",
  "dailyLlmCap": 500,
  "days": [
    {
      "date": "2026-10-02",
      "llmCalls": 212,
      "inputTokens": 418230,
      "outputTokens": 61204,
      "reportsCreated": 64,
      "reportsFailed": 5,
      "rateLimitRejections": 9,
      "maskingConflicts": 3
    }
  ],
  "failuresByCode": { "UNREADABLE": 3, "EXTRACTION_FAILED": 1, "NO_RESULTS_FOUND": 1 },
  "avgProcessingMsBySource": { "PDF": 6200, "IMAGE": 14800, "TEXT": 4100, "SAMPLE": 3900 },
  "users": { "total": 37, "activeLast7Days": 12 },
  "activeReports": { "guest": 18, "user": 95 }
}
```

**Errors:**
- `401 AUTH_REQUIRED`, `401 TOKEN_EXPIRED`, `401 TOKEN_INVALID`.
- `403 FORBIDDEN` when the caller isn't an admin.

---

## 7. Health

### `GET /actuator/health`

**Auth:** none

**Response `200 OK`**

```json
{ "status": "UP" }
```

Returns `503` with `{ "status": "DOWN" }` if the database is unreachable. Details are hidden in production (`management.endpoint.health.show-details=never`). No other actuator endpoints are exposed publicly.

The client calls this on page load to wake the backend and to show a "starting up" message if it's slow.

---

## 8. Error codes

### 8.1 HTTP error codes (`code` in Problem Details)

| Code | Status | Meaning |
|---|---|---|
| `VALIDATION_ERROR` | 400 | Request body or parameters are invalid. See `errors`. |
| `FILE_SIGNATURE_MISMATCH` | 400 | File content doesn't match its declared type |
| `SESSION_INVALID` | 401 | Report endpoint called with no bearer token and a missing, unknown, or expired session token |
| `AUTH_REQUIRED` | 401 | Signed-in-only endpoint called without a bearer token |
| `TOKEN_EXPIRED` | 401 | Access token has expired. Refresh and retry once. |
| `TOKEN_INVALID` | 401 | Access token is malformed, has a bad signature or unknown `kid`, or has the wrong issuer or audience. Refresh once and retry. |
| `GOOGLE_TOKEN_INVALID` | 401 | Google ID token failed verification |
| `REFRESH_TOKEN_INVALID` | 401 | Refresh token unknown, expired, revoked, or reused. Sign in again. |
| `FORBIDDEN` | 403 | Signed in, but missing the required role |
| `REPORT_NOT_FOUND` | 404 | Report doesn't exist for this caller (including other owners' reports) |
| `SAMPLE_NOT_FOUND` | 404 | Unknown sample id |
| `REPORT_NOT_READY` | 409 | Chat requested before the report is `DONE` |
| `FILE_TOO_LARGE` | 413 | File over 10 MB |
| `UNSUPPORTED_FILE_TYPE` | 415 | Not a PDF, PNG, or JPEG |
| `RATE_LIMITED` | 429 | Per-IP or per-user limit reached |
| `CAPACITY` | 429 | Daily LLM cap reached |
| `BUSY` | 429 | Processing queue full |
| `INTERNAL_ERROR` | 500 | Unexpected server error. Quote `requestId` when reporting. |

### 8.2 Report processing codes (`error.code` on a `FAILED` report)

| Code | Meaning | Suggested UI message |
|---|---|---|
| `UNREADABLE` | No usable text, even after OCR | "We couldn't read text from this file. Try a clearer image." |
| `DOCUMENT_TOO_LONG` | Extracted text is over the length or page limit | "This document is too long to process. Try a shorter report." |
| `NO_RESULTS_FOUND` | No lab values survived validation | "We didn't find lab results in this document." |
| `EXTRACTION_FAILED` | The model returned invalid output twice | "Something went wrong reading the results. Please try again." |
| `CAPACITY` | Daily LLM cap was reached mid-job | "The demo has hit today's limit. Try again tomorrow." |
| `LLM_UNAVAILABLE` | The LLM provider failed after retries | "The AI service is unavailable right now. Please try again later." |
| `INTERRUPTED` | The server restarted during processing | "Processing was interrupted. Please upload again." |

The server sends `error.message` with this text, but the client may use its own wording keyed on `code`.

---

## 9. Shared types

### 9.1 Type definitions

The client can keep these in `client/lib/api-types.ts`. The backend uses matching Java records.

```ts
export type ReportStatus = "PENDING" | "PROCESSING" | "DONE" | "FAILED";
export type SourceType = "PDF" | "IMAGE" | "TEXT" | "SAMPLE";
export type Flag = "LOW" | "NORMAL" | "HIGH" | "UNKNOWN";

export interface SessionResponse {
  token: string;
  expiresAt: string;
}

export interface Sample {
  id: string;
  title: string;
  description: string;
  markerCount: number;
}

export interface CreateReportResponse {
  id: string;
  status: ReportStatus;
  sourceType: SourceType;
  createdAt: string;
  expiresAt: string;
}

export interface Biomarker {
  id: string;
  testName: string;
  rawValue: string;
  numericValue: number | null;
  unit: string | null;
  referenceRangeText: string | null;
  refLow: number | null;
  refHigh: number | null;
  flag: Flag;
  biomarkerSlug: string | null; // links to /biomarkers/{slug} when a curated page exists
}

export interface ReportResult {
  collectedOn: string | null; // YYYY-MM-DD
  biomarkers: Biomarker[];
  summary: string;
  highlights: string[];
  counts: { total: number; low: number; normal: number; high: number; unknown: number };
}

export interface ReportError {
  code: string;
  message: string;
}

export interface ReportResponse extends CreateReportResponse {
  error: ReportError | null; // set only when status is FAILED
  result: ReportResult | null; // set only when status is DONE
}

export interface ChatTurn {
  role: "user" | "assistant";
  content: string;
}

export interface ChatRequest {
  question: string;
  history?: ChatTurn[];
}

export type ChatEvent =
  | { event: "meta"; data: { answerId: string; cached: boolean } }
  | { event: "token"; data: { text: string } }
  | { event: "sources"; data: { report: boolean; pages: { slug: string; title: string; heading: string }[] } }
  | { event: "done"; data: { finishReason: "COMPLETE" | "DECLINED" | "NOT_IN_CONTEXT" } }
  | { event: "error"; data: { code: string; message: string } };

// ---- Auth ----

export type Role = "USER" | "ADMIN";

export interface User {
  id: string;
  displayName: string;
  role: Role;
  createdAt: string;
}

export interface Me extends User {
  reportCount: number;
}

export interface GoogleSignInRequest {
  idToken: string;
  guestSessionToken?: string;
}

export interface TokenResponse {
  accessToken: string;
  accessTokenExpiresAt: string;
  refreshToken: string;
  refreshTokenExpiresAt: string;
  user: User;
}

export interface GoogleSignInResponse extends TokenResponse {
  claimedReportCount: number;
  newUser: boolean;
}

export interface RefreshRequest {
  refreshToken: string;
}

// ---- History ----

export type FlagCounts = { total: number; low: number; normal: number; high: number; unknown: number };

export interface ReportListItem {
  id: string;
  status: ReportStatus;
  sourceType: SourceType;
  createdAt: string;
  expiresAt: string;
  collectedOn: string | null;
  counts: FlagCounts | null;
  errorCode: string | null;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

// ---- Trends ----

export interface TrendPoint {
  reportId: string;
  date: string; // YYYY-MM-DD
  dateSource: "COLLECTED" | "UPLOADED";
  value: number;
  refLow: number | null;
  refHigh: number | null;
  flag: Flag;
}

export interface TrendResponse {
  marker: string;
  displayName: string;
  series: { unit: string | null; points: TrendPoint[] }[];
}

export interface TrendMarker {
  marker: string;
  displayName: string;
  reportCount: number;
}

// ---- Admin ----

export interface AdminStats {
  from: string;
  to: string;
  dailyLlmCap: number;
  days: {
    date: string;
    llmCalls: number;
    inputTokens: number;
    outputTokens: number;
    reportsCreated: number;
    reportsFailed: number;
    rateLimitRejections: number;
    maskingConflicts: number;
  }[];
  failuresByCode: Record<string, number>;
  avgProcessingMsBySource: Partial<Record<SourceType, number>>;
  users: { total: number; activeLast7Days: number };
  activeReports: { guest: number; user: number };
}

// ---- Errors ----

export interface ProblemDetail {
  type: string;
  title: string;
  status: number;
  detail: string;
  code: string;
  requestId: string;
  errors?: { field: string; message: string }[];
}
```

### 9.2 Compatibility rules

- **Adding** a new optional field, endpoint, enum value, or error code is a compatible change.
- **Removing or renaming** a field, changing a type, or changing what a status code means is a breaking change. Bump the version in this document and update the client in the same commit.
- **Unknown enum values:** the client treats an unknown `flag` as `UNKNOWN`, an unknown `status` as `PROCESSING` (keep polling), and an unknown error `code` as a generic error.

---

## 10. Example sessions

**Guest run:**

1. `GET /actuator/health` → `200`. The backend is awake.
2. `POST /api/sessions` → `201` with a token. Save it.
3. `GET /api/samples` → `200` with three samples. Show the buttons.
4. User clicks "Lipid panel": `POST /api/reports` with `{ "sampleId": "lipid-panel", "consent": true }` → `202` with `id`.
5. Poll `GET /api/reports/{id}` → `PENDING`, `PROCESSING`, then `DONE` with results.
6. User asks "Why is my LDL flagged?": `POST /api/reports/{id}/chat` → SSE stream with `meta`, `token` events, `sources`, `done`.
7. User clicks "Delete my report": `DELETE /api/reports/{id}` → `204`.

**Guest signs in, then comes back later:**

1. The guest has one report in their session and clicks "Sign in with Google". Google Identity Services returns an ID token.
2. `POST /api/auth/google` with `{ idToken, guestSessionToken }` → `200`.
   - The response has `claimedReportCount: 1`.
   - Keep the access token in memory and the refresh token in `sessionStorage`.
   - Delete the guest token.
3. `GET /api/reports` with `Authorization: Bearer …` → the claimed report, now expiring in 30 days.
4. Fifteen minutes later, a call returns `401 TOKEN_EXPIRED`.
   - `POST /api/auth/refresh` → `200` with new tokens. Replace both.
   - Retry the call.
5. `GET /api/trends/markers` → markers that appear in two or more reports. Then `GET /api/trends?marker=ldl-cholesterol` → chart data.
6. The user middle-clicks a report on "My reports". The new tab has no refresh token.
   - Google auto sign-in returns an ID token.
   - `POST /api/auth/google` → `200` with this tab's own tokens.
   - Then `GET /api/reports/{id}` → `200`.
7. The user closes all tabs and comes back two days later. Auto sign-in runs again, and history and trends are there. If auto sign-in doesn't happen, one click on "Sign in with Google" does the same.
8. Signing out in one tab: `POST /api/auth/logout` → `204`.
   - A `BroadcastChannel` message makes every other tab log out its own refresh token and clear its tokens.
   - Every tab calls `disableAutoSelect()`.

---

## 11. Changelog

**2.2**
- **New report failure code `DOCUMENT_TOO_LONG`** (8.2): the extracted text is over the character or page limit. Previously these reports failed as `UNREADABLE`, which was misleading. `ReportError.code` is typed `string`, so `client/lib/api-types.ts` needs no change.
- **Images rejected until Phase 4:** `POST /api/reports` returns `415 UNSUPPORTED_FILE_TYPE` for PNG and JPEG until the OCR pipeline ships (4.1). A text-less PDF is still accepted and fails the job with `UNREADABLE`.

**2.1**
- **Refresh rule:** the client now refreshes once on `401 TOKEN_INVALID` as well as `TOKEN_EXPIRED`, so rotating the signing key is invisible to users.
- **Key ids:** access tokens carry a `kid` header, and the server accepts any key in `JWT_SIGNING_KEYS` (see 2.8).
- **Cross-tab sign-in:** new tabs and returning visits sign in through Google auto sign-in, with one refresh family per tab. Logout is broadcast to every tab.
- **Lazy guest sessions:** guest sessions are created on the first guest action instead of on page load.

**2.0 (auth)**
- **New auth endpoints:** `POST /api/auth/google`, `POST /api/auth/refresh`, `POST /api/auth/logout`, `GET /api/me`, `DELETE /api/me`.
- **New data endpoints:** `GET /api/reports` (list), `GET /api/trends`, `GET /api/trends/markers`, `GET /api/admin/stats`.
- **Bearer tokens on report endpoints:** they now accept `Authorization: Bearer` as well as `X-Session-Token`.
- **New error codes:** `AUTH_REQUIRED`, `TOKEN_EXPIRED`, `TOKEN_INVALID`, `GOOGLE_TOKEN_INVALID`, `REFRESH_TOKEN_INVALID`, `FORBIDDEN`.
- **New `result.collectedOn` field.**
- **Per-user rate limits:** limits are keyed by user when signed in.

**1.0.** The contract settled a few points the data flow document left open:

- **Full queue:** returns `429 BUSY` without creating a report. Before, the design created a report and immediately marked it `FAILED`.
- **Daily cap at upload:** the cap is checked at upload too, so users get `429 CAPACITY` right away instead of a report that fails later.
- **New `LLM_UNAVAILABLE` code:** for provider outages after retries.
- **Chat stream changes:** a `meta` event comes first and `done` carries a `finishReason`, so the UI can style declined and "not in your report" answers differently.
- **New `biomarkerSlug` field:** lets the results view link each marker to its curated page.
