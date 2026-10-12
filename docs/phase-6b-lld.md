# Phase 6b: History, Trends, and Admin Stats (low-level design)

Backend only. No frontend work. Branch: `feat/phase-6b-history-trends-admin`, created from `dev`.

This phase finishes the read endpoints that the API contract already describes. Accounts (Phase 6a) are done, so the ownership and role rules already exist. This phase adds handlers and queries, and almost nothing else.

Contract references: `docs/api-contract.md` section 4.5 (history), section 5 (trends), section 6 (admin), section 8 (errors).

---

## 1. Scope

**In scope**
1. `GET /api/reports`: the caller's report list, paged.
2. `GET /api/trends/markers` and `GET /api/trends`: signed-in users only.
3. `GET /api/admin/stats`: admins only.
4. One migration for indexes, only if a query plan needs one (section 3).
5. Docs and tests.

**Out of scope**
- Frontend, chat, OCR, evaluation set.
- Any new error code. Every error here already exists in contract section 8.
- Any change to who owns a report, or to how tokens work.
- `IMAGE` statistics. OCR doesn't exist until Phase 4, so no image rows exist. Do not hard-code `IMAGE` and do not return a fake zero for it.

---

## 2. Decisions (already made)

| # | Decision | Reason |
|---|---|---|
| D1 | Guests can list reports, but only their own session's. | The contract (4.5) already says so. No sign-in wall for a basic feature. |
| D2 | Each trend series returns at most the **latest 100 points**, sorted oldest first. | Keeps responses small and fast. |
| D3 | `IMAGE` stats are not shown until Phase 4. | No data exists. The stats code just reads rows, so `IMAGE` appears on its own once rows exist. |
| D4 | A biomarker with no unit gets its own series with `unit: null`. | Never mix values with and without units. Never guess a unit. |
| D5 | Admin stats are not cached. | Few admins, cheap queries on small tables. Add a cache later only if needed. |
| D6 | Invalid query parameters return `400 VALIDATION_ERROR`. They are never silently clamped. | Predictable API. |
| D7 | The markers list counts a marker only if it has a numeric value in at least two `DONE` reports. | A trend needs two points. |

---

## 3. Database

**No new table.** Everything is read from tables that exist (Phases 2 and 6a).

Indexes that already exist and serve these queries:
- `report_session_created_idx (session_id, created_at DESC)`: guest history.
- `report_user_created_idx (user_id, created_at DESC)`: user history and trends.
- `biomarker_report_idx (report_id, position)`: counts per report.
- `biomarker_norm_idx (test_name_norm)`: trend matching.

**Rule:** run `EXPLAIN` on the trend and markers queries with a few thousand generated rows. If either does a sequential scan on `biomarker` because of the join, add `V4__trend_indexes.sql` with the index and a comment saying which query it serves. For example `(biomarker_slug)` partial where `numeric_value IS NOT NULL`. If the existing indexes are enough, add no migration. Report the result either way.

---

## 4. Class map

Feature packages, as in `backend/CLAUDE.md`.

| Package | New classes |
|---|---|
| `report` | `ReportListController`, `ReportListService`, `ReportListQuery` (a `JdbcClient` read repository), `ReportSummaryItem` and `ReportPage` (response records) |
| `trends` (new) | `TrendsController`, `TrendsService`, `TrendsQuery`, `MarkerNormalizer` (only if Phase 2's normalizer is not reusable), `TrendResponse`, `MarkerItem` records |
| `stats` | `AdminStatsController`, `AdminStatsService`, `AdminStatsQuery`, `AdminStatsResponse` records |
| `auth` | route rules only (section 7) |

**Reuse the Phase 2 normalizer.** `test_name_norm` was written by Phase 2 code. Trend matching must call that same function on the `marker` parameter. Do not write a second version of the rules. If it's private, make it a shared utility in `report` and update the Phase 2 caller to use it.

**Dependency direction** (from `backend/CLAUDE.md`): `trends` and `stats` read tables through their own query classes. They don't call `analysis`, `llm`, or `upload`. They may use `OwnerRef`, `CurrentOwner`, and `BiomarkerCatalog` (for display names).

DTO records must match the contract field by field, including `null` handling. Records here hold no report text, but still keep `toString()` safe by default: biomarker names are not secrets, but values and dates are health data, so override `toString()` on `TrendResponse` and its point record to print only counts.

---

## 5. `GET /api/reports` (history)

**Auth:** guest `X-Session-Token` or `Authorization: Bearer`. The owner comes from `CurrentOwner`, never from a parameter.

**Parameters:** `page` (default 0, at least 0), `size` (default 20, 1 to 50), `status` (optional: `PENDING`, `PROCESSING`, `DONE`, `FAILED`). Anything else is `400 VALIDATION_ERROR`, with the field named in `errors`.

**Query rules**
- Owner filter: `session_id = :id` for a guest, `user_id = :id` for a user. The owner column is chosen in code from `OwnerRef`, never built from input.
- Only reports with `expires_at > now` (use the injected `Clock`). The retention job also deletes them, but the query must not depend on it.
- Order: `created_at DESC, id DESC` (the `id` makes paging stable when two reports share a timestamp).
- `status` filter is bound as a parameter.
- Page data and `totalItems` come from two queries. `totalPages = ceil(totalItems / size)`; it is 0 when there are no items.
- A page past the end returns `200` with `items: []`, not an error.

**Counts without N+1.** For `DONE` reports on the page, load counts with one grouped query:
`SELECT report_id, flag, count(*) FROM biomarker WHERE report_id IN (:ids) GROUP BY report_id, flag`. Build `counts` in code (`total` = sum; `unknown` includes flags `UNKNOWN`). For reports that aren't `DONE`, `counts` and `collectedOn` are `null`. `errorCode` is set only for `FAILED`.

**Fields:** `id`, `status`, `sourceType`, `createdAt`, `expiresAt`, `collectedOn`, `counts`, `errorCode`. No summary text, no biomarker names, no filenames.

**Errors:** `400 VALIDATION_ERROR`, `401 SESSION_INVALID` (a guest with a missing or unknown session token), `401 TOKEN_EXPIRED` / `TOKEN_INVALID` for a bad bearer token. These already come from the Phase 6a chain.

**Important:** a request with no credential at all gets `SESSION_INVALID`, not an empty list. A guest session is created only by the upload flow, so a caller with no session has nothing to list.

---

## 6. Trends

Both endpoints: signed-in users only (`USER` or `ADMIN`). Owner is the user id from `CurrentOwner`. A guest gets `401 AUTH_REQUIRED`.

### 6.1 `GET /api/trends/markers`

For the user's `DONE`, not-expired reports, group biomarker rows that have `numeric_value IS NOT NULL` by key `coalesce(biomarker_slug, test_name_norm)`. Keep groups that appear in at least 2 distinct reports (D7).

Response item: `marker` (the key), `displayName`, `reportCount`.
- `displayName`: the catalog display name when the key is a slug; otherwise the `test_name` from the user's most recent report containing it.
- Sort by `reportCount` desc, then `displayName` asc. Maximum 200 items.

### 6.2 `GET /api/trends?marker=…`

**Validation:** `marker` required, trimmed, 1 to 100 characters, else `400 VALIDATION_ERROR`.

**Matching:** a row matches when `biomarker_slug = :marker` OR `test_name_norm = normalize(:marker)`. Only the user's `DONE`, not-expired reports. Only rows with `numeric_value IS NOT NULL`. Qualitative values are left out.

**Points**
- `reportId`, `date`, `dateSource`, `value`, `refLow`, `refHigh`, `flag`.
- `date` = `report.collected_on` if set (`dateSource` = the contract's "collected" value), otherwise the UTC date of `report.created_at` (the contract's "uploaded" value). Use the exact enum names in contract section 5 and `client/lib/api-types.ts`.
- If one report has several rows for the same marker (a repeated test), keep **all** of them. Don't merge or average. (Same date, ordered by `position`.)

**Series**
- Group by `unit` exactly as stored. Do not convert, trim, lowercase, or merge units (`mg/dL` and `mmol/L` stay apart). A null unit is its own series with `unit: null` (D4).
- Within a series, sort by `date` asc, then `created_at` asc, then `position` asc.
- **Cap (D2):** if a series has more than 100 points, keep the 100 latest, still sorted oldest first.
- Series order: most points first, then unit alphabetical (null last).
- `displayName`: catalog name when `marker` resolves to a slug, otherwise the most recent `test_name` that matched, otherwise the `marker` parameter as given.
- No match → `200` with `series: []`.

**Response `marker`:** echo the slug if the match used a slug, otherwise the normalized name.

**Errors:** `400 VALIDATION_ERROR`, `401 AUTH_REQUIRED`, `401 TOKEN_EXPIRED`, `401 TOKEN_INVALID`.

**Logging:** log only counts and timing (series count, point count). Never log the `marker` value, values, or dates.

---

## 7. Admin stats: `GET /api/admin/stats`

**Auth:** role `ADMIN` only. `USER` gets `403 FORBIDDEN`, no credential gets `401 AUTH_REQUIRED`.

**Parameter:** `days` (default 14, 1 to 90), else `400 VALIDATION_ERROR`. The range is the last `days` UTC calendar days ending today, so `to` is today and `from = to - (days - 1)`.

**Data sources** (all counts, no content)

| Field | Source |
|---|---|
| `dailyLlmCap` | `LLM_DAILY_CAP` property |
| `days[].llmCalls` | `llm_usage.calls` |
| `days[].inputTokens`, `outputTokens`, `reportsCreated`, `reportsFailed`, `rateLimitRejections`, `maskingConflicts` | `daily_stats` |
| `failuresByCode` | `SUM(count)` from `daily_failure` over the range, grouped by `code` |
| `avgProcessingMsBySource` | `SUM(total_ms) / SUM(count)` from `daily_processing` over the range, per `source_type`, rounded to a whole number. A source with no rows is left out (this is why `IMAGE` is absent until Phase 4). |
| `users.total` | `count(*)` from `app_user` |
| `users.activeLast7Days` | users with `last_login_at >= now - 7 days` |
| `activeReports.guest` / `.user` | reports with `expires_at > now`, counted by owner type (`session_id IS NOT NULL` / `user_id IS NOT NULL`) |

**Fill gaps.** Return exactly `days` entries, oldest first. A day with no row in a table gets zeros for that table's fields. Do the fill in code after reading the rows.

**Privacy.** Aggregates only. No report ids, session ids, user ids, names, or Google subjects. Code review check: grep the response records for any field of those kinds.

**Division by zero:** a source with `count = 0` is skipped.

---

## 8. Security rules (change to `SecurityConfig`)

Phase 6a already set these (per its report). This phase confirms them and adds one:

- `GET /api/reports` gets its **own explicit rule** (guest or bearer allowed). Today it only works because of the wildcard `/api/reports/**`. The explicit rule documents the choice, and a test locks it in.
- `/api/trends/**` requires `hasAnyRole('USER','ADMIN')`.
- `/api/admin/**` requires `hasRole('ADMIN')`.
- No `permitAll` for any of these.

Rule order matters: put the specific rules before the wildcard. Add a test that fails if the order is wrong (a guest calling a trends path must get `401 AUTH_REQUIRED`, never `200`).

---

## 9. Test plan

Follow `backend/CLAUDE.md`: Testcontainers, a real Postgres, synthetic data, status plus `code` asserted. For list and trend tests, insert rows directly with a small test fixture (don't run the pipeline).

**History**
1. Guest sees only their own session's reports. A second guest's reports never appear.
2. User sees only their own reports. Another user's reports never appear.
3. Newest first. Two reports with the same `created_at` keep a stable order across pages.
4. Paging: `page`, `size`, `totalItems`, `totalPages`. A page past the end returns an empty list.
5. `status` filter for each of the four values.
6. Expired reports are not listed even if the retention job hasn't run.
7. `counts` and `collectedOn` are `null` unless `DONE`. `errorCode` only for `FAILED`.
8. Invalid `page`, `size`, and `status` each return `400 VALIDATION_ERROR`.
9. No credential → `401 SESSION_INVALID`. Expired bearer → `TOKEN_EXPIRED`.
10. Query count: listing 20 `DONE` reports runs a fixed small number of queries (assert it, to prevent N+1).
11. After sign-in claims a guest's reports, the user's list contains them.

**Trends**
12. Guest → `401 AUTH_REQUIRED` on both endpoints.
13. Matching by slug, and by normalized name when there is no slug. Name matching ignores case, punctuation, and extra spaces.
14. Qualitative values (null `numeric_value`) are excluded.
15. Two units produce two series, with no conversion.
16. A null unit produces its own series with `unit: null`.
17. `collectedOn` missing → date from upload, with the right `dateSource`.
18. Oldest first. Cap at 100: with 130 points, exactly the latest 100 come back, still oldest first.
19. Only `DONE` reports and not-expired reports count.
20. Another user's data never appears (two users with the same marker).
21. Unknown marker → `200` with `series: []`.
22. Missing, blank, or 101-character `marker` → `400 VALIDATION_ERROR`.
23. Markers list: needs at least two reports, sorted correctly, a marker with a value in only one report is excluded.
24. Repeated test in one report keeps all rows.

**Admin**
25. Guest → `401 AUTH_REQUIRED`. `USER` → `403 FORBIDDEN`. `ADMIN` → `200`.
26. Exactly `days` entries; gap days are zero-filled.
27. `failuresByCode` and `avgProcessingMsBySource` sums and averages are right across several days. A source with no rows is absent. No `IMAGE` key appears.
28. `activeReports` and `users` counts are right, and expired reports are not counted.
29. `days=0`, `days=91`, and `days=abc` → `400 VALIDATION_ERROR`.
30. The response JSON contains no key named like an id, name, or subject (guard test over the field names).

**Cross-cutting**
31. Authorization matrix test (extend Phase 6a's): every endpoint × guest, user, admin, expired token.
32. Contract field-by-field test for all three response shapes.
33. Canary: the existing canary test still passes, and no new log line contains marker values, dates, or user data.
34. `./mvnw verify` passes.

---

## 10. Docs to update (first, before code)

1. **`docs/api-contract.md`**
   - Section 5: add "Each series returns at most the 100 latest points." Say that a marker with no unit is returned as its own series with `unit: null`, and update the TypeScript type in section 9 and `client/lib/api-types.ts` to `unit: string | null`.
   - Section 6: add a note that `avgProcessingMsBySource` only has keys for sources that have data, so `IMAGE` appears only after Phase 4.
   - Section 4.5: add "A page past the last page returns an empty `items` list."
   - Changelog: new minor-version entry listing these three notes, with the version line bumped.
2. **`docs/plan.md` section 14:** mark 6b as the current phase in the build order.
3. **`docs/dataflow.md`:** in the history, trends, and admin stats sections, add: guests can list their own reports; trends cap at 100 points; stats read only aggregate tables.
4. **`backend/CLAUDE.md`:** add the `trends` row to the package map, and say that the `stats` package now also serves the admin read endpoint. This file is gitignored on purpose: edit it on disk, and do not commit or force-add it.
5. **`SECURITY.md`:** one line under "Accounts": history and trends are owner-scoped, the admin stats contain aggregates only.

Edit what is in the repo now. The repo copies may differ from this list.

---

## 11. Manual check

With the `local` profile and Postgres running:
1. Upload two sample reports as a guest. `GET /api/reports` shows both, and nothing else.
2. A second guest sees an empty list.
3. With a test-minted user token (the same helper as Phase 6a, not an endpoint): trends return `200`. Upload samples so one marker appears twice.
4. With a guest token on `/api/trends/markers` → `AUTH_REQUIRED`.
5. With a USER token on `/api/admin/stats` → `FORBIDDEN`. With an ADMIN token → numbers that match the DB.
6. Search the application log: no marker names, values, or dates.

---

## 12. Open items for the final report

- The `EXPLAIN` result and whether `V4` was needed.
- Whether the Phase 2 normalizer had to be moved.
- Anything in the contract that conflicted with the repo.