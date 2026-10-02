# Medi-Scan: API Contract

This document defines the HTTP API between the Next.js client and the Spring Boot backend. It matches the v2 project plan and the data flow design. If the code and this document disagree, fix one of them before merging.

**Version:** 1.0

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
- **Enums:** uppercase strings. Clients must handle values they don't recognize (see 7.2).

### 1.3 Headers

**Request headers**

| Header | When | Notes |
|---|---|---|
| `X-Session-Token` | All `/api/reports/**` calls | Token from `POST /api/sessions` |
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
- **Allowed request headers:** `Content-Type`, `X-Session-Token`, `Accept`.
- **Exposed response headers:** `X-Request-Id`, `Retry-After`, `Location`.
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

The full list of codes is in section 6.

### 1.6 Rate limits

| Limit | Scope | Default | Exceeded |
|---|---|---|---|
| Session creation | Per IP | 20 per hour | 429 `RATE_LIMITED` |
| Uploads | Per IP | 10 per hour | 429 `RATE_LIMITED` |
| Chat messages | Per IP | 30 per hour | 429 `RATE_LIMITED` |
| LLM calls | Whole app | 500 per day (UTC) | 429 `CAPACITY`, or report fails with `CAPACITY` |

429 responses always include `Retry-After`. For `CAPACITY`, it is the number of seconds until midnight UTC.

---

## 2. Sessions

### `POST /api/sessions`

Starts an anonymous session. The client stores the token in `sessionStorage` and sends it as `X-Session-Token` on every report call.

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

**Session errors on other endpoints.** Any `/api/reports/**` call with a missing, unknown, or expired token returns `401 SESSION_INVALID`. The client should then create a new session and drop any report ids it was holding.

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

### 4.1 `POST /api/reports`

Creates a report and queues it for processing. Accepts exactly one of: a file, pasted text, or a sample id.

**Auth:** `X-Session-Token`

**Option A: file upload** (`multipart/form-data`)

| Part | Type | Required | Rules |
|---|---|---|---|
| `file` | file | yes | PDF, PNG, or JPEG. Max 10 MB. The file signature (magic bytes) must match the type. |
| `consent` | string | yes | Must be `"true"` |

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
| 415 | `UNSUPPORTED_FILE_TYPE` | Anything other than PDF, PNG, or JPEG |
| 429 | `RATE_LIMITED` | Upload limit for this IP reached |
| 429 | `CAPACITY` | Daily LLM cap already reached, so the report is not created |
| 429 | `BUSY` | Job queue is full, so the report is not created |

### 4.2 `GET /api/reports/{id}`

Returns the report's status, and the results once processing is done. The client polls this every 1.5 seconds until `status` is `DONE` or `FAILED`, for up to 2 minutes.

**Auth:** `X-Session-Token`

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

**Errors**

| Status | Code | When |
|---|---|---|
| 400 | `VALIDATION_ERROR` | `id` isn't a valid UUID |
| 401 | `SESSION_INVALID` | Missing, unknown, or expired token |
| 404 | `REPORT_NOT_FOUND` | No such report, it belongs to another session, or it has expired |

The API never says whether a report exists in another session. All of these cases return the same 404.

### 4.3 `DELETE /api/reports/{id}`

Deletes the report and everything derived from it (masked text, biomarkers, summary).

**Auth:** `X-Session-Token`

**Response `204 No Content`** with an empty body.

Deleting a report that is still processing is allowed. The job notices the report is gone and stops without saving.

**Errors:** `401 SESSION_INVALID`, `404 REPORT_NOT_FOUND`

### 4.4 `POST /api/reports/{id}/chat`

Asks a question about a report. The answer streams back as Server-Sent Events.

**Auth:** `X-Session-Token`
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

**Keep-alive.** The server sends an SSE comment line (`: ping`) every 15 seconds while the model is thinking, so proxies don't close the connection.

**Client note.** The browser's built-in `EventSource` only supports GET requests and can't send custom headers. Use `fetch` and read the response body as a stream, or a library such as `@microsoft/fetch-event-source`.

---

## 5. Health

### `GET /actuator/health`

**Auth:** none

**Response `200 OK`**

```json
{ "status": "UP" }
```

Returns `503` with `{ "status": "DOWN" }` if the database is unreachable. Details are hidden in production (`management.endpoint.health.show-details=never`). No other actuator endpoints are exposed publicly.

The client calls this on page load to wake the backend and to show a "starting up" message if it's slow.

---

## 6. Error codes

### 6.1 HTTP error codes (`code` in Problem Details)

| Code | Status | Meaning |
|---|---|---|
| `VALIDATION_ERROR` | 400 | Request body or parameters are invalid. See `errors`. |
| `FILE_SIGNATURE_MISMATCH` | 400 | File content doesn't match its declared type |
| `SESSION_INVALID` | 401 | Token missing, unknown, or expired |
| `REPORT_NOT_FOUND` | 404 | Report doesn't exist in this session |
| `SAMPLE_NOT_FOUND` | 404 | Unknown sample id |
| `REPORT_NOT_READY` | 409 | Chat requested before the report is `DONE` |
| `FILE_TOO_LARGE` | 413 | File over 10 MB |
| `UNSUPPORTED_FILE_TYPE` | 415 | Not a PDF, PNG, or JPEG |
| `RATE_LIMITED` | 429 | Per-IP limit reached |
| `CAPACITY` | 429 | Daily LLM cap reached |
| `BUSY` | 429 | Processing queue full |
| `INTERNAL_ERROR` | 500 | Unexpected server error. Quote `requestId` when reporting. |

### 6.2 Report processing codes (`error.code` on a `FAILED` report)

| Code | Meaning | Suggested UI message |
|---|---|---|
| `UNREADABLE` | No usable text, even after OCR | "We couldn't read text from this file. Try a clearer image." |
| `NO_RESULTS_FOUND` | No lab values survived validation | "We didn't find lab results in this document." |
| `EXTRACTION_FAILED` | The model returned invalid output twice | "Something went wrong reading the results. Please try again." |
| `CAPACITY` | Daily LLM cap was reached mid-job | "The demo has hit today's limit. Try again tomorrow." |
| `LLM_UNAVAILABLE` | The LLM provider failed after retries | "The AI service is unavailable right now. Please try again later." |
| `INTERRUPTED` | The server restarted during processing | "Processing was interrupted. Please upload again." |

The server sends `error.message` with this text, but the client may use its own wording keyed on `code`.

---

## 7. Shared types

### 7.1 Type definitions

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

### 7.2 Compatibility rules

- **Adding** a new optional field, endpoint, enum value, or error code is a compatible change.
- **Removing or renaming** a field, changing a type, or changing what a status code means is a breaking change. Bump the version in this document and update the client in the same commit.
- **Unknown enum values:** the client treats an unknown `flag` as `UNKNOWN`, an unknown `status` as `PROCESSING` (keep polling), and an unknown error `code` as a generic error.

---

## 8. Example session

A full run from the client's point of view:

1. `GET /actuator/health` → `200`. The backend is awake.
2. `POST /api/sessions` → `201` with a token. Save it.
3. `GET /api/samples` → `200` with three samples. Show the buttons.
4. User clicks "Lipid panel": `POST /api/reports` with `{ "sampleId": "lipid-panel", "consent": true }` → `202` with `id`.
5. Poll `GET /api/reports/{id}` → `PENDING`, `PROCESSING`, then `DONE` with results.
6. User asks "Why is my LDL flagged?": `POST /api/reports/{id}/chat` → SSE stream with `meta`, `token` events, `sources`, `done`.
7. User clicks "Delete my report": `DELETE /api/reports/{id}` → `204`.

---

## 9. Changes from the data flow design

The contract settles a few points the data flow document left open:

- **Full queue:** returns `429 BUSY` without creating a report. Before, the design created a report and immediately marked it `FAILED`.
- **Daily cap at upload:** the cap is checked at upload too, so users get `429 CAPACITY` right away instead of a report that fails later.
- **New `LLM_UNAVAILABLE` code:** for provider outages after retries.
- **Chat stream changes:** a `meta` event comes first and `done` carries a `finishReason`, so the UI can style declined and "not in your report" answers differently.
- **New `biomarkerSlug` field:** lets the results view link each marker to its curated page.
