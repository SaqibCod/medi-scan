# Phase 6a: Low-Level Design (Accounts, backend only)

This phase adds Google sign-in to the backend: users, the app's own access and refresh tokens, report ownership by user, claiming guest reports, account deletion, and the admin role. No frontend work. It builds on `docs/plan.md` (sections 4.9 and 4.10), `docs/dataflow.md` (section 3), and `docs/api-contract.md` (section 2). Where it differs from them, section 2 lists the doc changes to make first.

---

## 1. Scope

### 1.1 In Phase 6a

| Area | What gets built |
|---|---|
| Endpoints | `POST /api/auth/google`, `POST /api/auth/refresh`, `POST /api/auth/logout`, `GET /api/me`, `DELETE /api/me` |
| Tokens | Google ID token verification; the app's access JWT (HS256, `kid`, 15 minutes); refresh tokens with rotation, families, and reuse detection |
| Security | Bearer authentication next to the guest filter, exact 401/403 codes, role-based access for `/api/admin/**` |
| Ownership | Reports owned by a guest session or a user (exactly one); signed-in users use all existing report endpoints; user reports kept 30 days |
| Claim | A guest's reports move to their account at sign-in, in one transaction |
| Limits | Auth endpoints limited per IP; upload limit keyed by user id when signed in |
| Retention | Refresh token cleanup, inactive account cleanup |
| Dev tool | A throwaway local page that prints a Google ID token, for manual testing without the real UI |
| Tests | Authorization matrix, token tests, rotation and reuse, key rotation, claim, account deletion, contract checks, no-tokens-in-logs |

### 1.2 Not in Phase 6a

- **Frontend:** the sign-in button, auto sign-in, cross-tab logout, `BroadcastChannel`, CSP changes for Google. These are already specified in the contract and data flow docs and come with the frontend.
- **Phase 6b endpoints:** `GET /api/reports` (history list), `GET /api/trends`, `GET /api/trends/markers`, `GET /api/admin/stats`. The `/api/admin/**` rule is in place, but no admin endpoint ships yet.
- **Chat:** Phase 5 builds on the ownership model from this phase.

---

## 2. Doc changes to make first

1. **`docs/plan.md` section 14 (build order):** the new order is:
   1. Foundation (done)
   2. Text and PDF pipeline (done)
   3. Accounts backend, Phase 6a (this phase)
   4. Evaluation set (old Phase 3)
   5. OCR (old Phase 4)
   6. Chat (old Phase 5)
   7. History, trends, admin stats (Phase 6b)
   8. Frontend
   9. Deploy

   Keep the old phase names as labels (code comments and docs refer to "Phase 6"). Add a line saying the numbers are labels and the list order is the build order.
2. **`docs/dataflow.md` section 8.1 (account deletion):** after deletion, a still-valid access token gets `401 AUTH_REQUIRED` on every endpoint right away, because each authenticated request checks that the user still exists (section 6.3 of this document). Replace the text that says queries just "find nothing".
3. **`docs/dataflow.md` section 3.2, "Admin role" bullet:** the role is recomputed from `ADMIN_GOOGLE_SUBS` on every sign-in and every refresh, so a config change applies within 15 minutes without the user signing in again.
4. **`docs/dataflow.md` section 3.6:** add the user-existence check to the bearer branch of the diagram notes.
5. **`docs/plan.md` section 10 and `.env.example`:** make sure these exist: `GOOGLE_CLIENT_ID`, `JWT_SIGNING_KEYS`, `JWT_CURRENT_KID`, `JWT_ISSUER`, `JWT_AUDIENCE`, `ACCESS_TOKEN_TTL_MINUTES`, `REFRESH_TOKEN_TTL_DAYS`, `REFRESH_FAMILY_MAX_DAYS`, `USER_RETENTION_DAYS`, `INACTIVE_ACCOUNT_DAYS`, `ADMIN_GOOGLE_SUBS`.
6. **`backend/CLAUDE.md` package map:** update the `auth` row to "Google ID token verification, access token issue and validation, signing keys, refresh tokens, the auth endpoints" and add a `user` row ("Users, `/api/me`, account deletion"). Add the security filter chain and its 401/403 problem handlers to the `config` row, because that is where Phase 1 put them and this phase extends them in place (section 3.1).
7. **`docs/api-contract.md`:** no shape changes. Add a changelog line under 2.2 or 2.3 saying the auth endpoints are implemented in Phase 6a and the 6b endpoints are not yet available.

---

## 3. Classes by package

`<base>` is the base package from `pom.xml`.

### 3.1 `auth`

The filter chain and the two problem-detail handlers are **not** in this package. Phase 1 put `SecurityConfig`, `ProblemAuthenticationEntryPoint`, and `ProblemAccessDeniedHandler` in `config`, where the rest of the cross-cutting wiring lives, and `SecurityConfig` already carries the placeholder comment for this phase. They are extended in place (section 3.3) rather than moved here or duplicated under new names, so there is still exactly one filter chain and one 401 mapping in the codebase.

| Class | Responsibility |
|---|---|
| `JwtProperties` | Issuer, audience, TTL, clock skew, and the parsed signing keys |
| `SigningKeyRing` | Parses `JWT_SIGNING_KEYS`, validates them, exposes the JWK set and the current key id |
| `AccessTokenIssuer` | Builds and signs access tokens with the current key |
| `AccessTokenDecoderConfig` | The `JwtDecoder` bean: verifies by `kid` against all listed keys, plus validators |
| `ExpiryValidator` | Replaces the default timestamp validator so an expired token carries a distinct error code |
| `GoogleIdTokenVerifier` | Verifies Google ID tokens with Google's JWKS (section 5) |
| `GoogleIdentity` (record) | `sub`, `displayName`. Never email or picture |
| `AuthController` | The three `/api/auth/*` endpoints |
| `AuthService` | Sign-in, refresh, logout orchestration |
| `RefreshTokenService`, `RefreshTokenRepository` | Issue, rotate, revoke family (via `JdbcClient` for the conditional updates) |
| `AuthRateLimitFilter` | 10 per minute per IP on `/api/auth/google` and `/api/auth/refresh` |

### 3.2 `user`

| Class | Responsibility |
|---|---|
| `UserEntity`, `UserRepository` | `app_user` persistence; upsert by `google_sub` via `JdbcClient` |
| `UserService` | Upsert on sign-in, role computation, account deletion, report count |
| `AdminSubjects` | The parsed `ADMIN_GOOGLE_SUBS` set |
| `MeController` | `GET /api/me`, `DELETE /api/me` |

### 3.3 Changes in existing packages

| Package | Change |
|---|---|
| `session` | `GuestSessionFilter` skips when an `Authorization` header is present. `OwnerRef.User(userId)` gets used |
| `config` | `SecurityConfig` gains the resource server, the bearer filter, and the new route rules (6.4). `ProblemAuthenticationEntryPoint` gains the bearer-failure branch that tells `TOKEN_EXPIRED` from `TOKEN_INVALID`; `ProblemAccessDeniedHandler` already maps 403 to `FORBIDDEN`. A new `CurrentOwner` argument resolver turns the security principal into an `OwnerRef`, with the user-existence check |
| `report` | Repository queries handle both owner kinds; `ReportOwnershipService.claimGuestReports`; `CreateReportService` sets `expires_at` by owner kind |
| `ratelimit` | Upload limit keyed by user id when signed in |
| `retention` | Refresh token cleanup, inactive account cleanup |

---

## 4. Database: migration `V3__accounts.sql`

The token hash column matches `session.token_hash` in V1, which is `varchar(64)`: SHA-256 hex, a fixed 64 characters, so the length is a real constraint rather than decoration.

```sql
CREATE TABLE app_user (
    id             uuid PRIMARY KEY,
    google_sub     text NOT NULL UNIQUE,
    display_name   text NOT NULL,
    role           text NOT NULL CHECK (role IN ('USER','ADMIN')),
    created_at     timestamptz NOT NULL DEFAULT now(),
    last_login_at  timestamptz NOT NULL
);
-- Serves: the inactive-account cleanup.
CREATE INDEX app_user_last_login_idx ON app_user (last_login_at);

CREATE TABLE refresh_token (
    id                 uuid PRIMARY KEY,
    user_id            uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    family_id          uuid NOT NULL,
    token_hash         varchar(64) NOT NULL UNIQUE,   -- SHA-256 hex, as session.token_hash in V1
    issued_at          timestamptz NOT NULL,
    expires_at         timestamptz NOT NULL,
    family_started_at  timestamptz NOT NULL,
    revoked_at         timestamptz,
    replaced_by        uuid
);
-- Serves: revoking a whole family on logout or reuse.
CREATE INDEX refresh_token_family_idx ON refresh_token (family_id);
-- Serves: the cleanup job.
CREATE INDEX refresh_token_expires_idx ON refresh_token (expires_at);

-- Reports can now belong to a user instead of a guest session.
ALTER TABLE report ADD COLUMN user_id uuid REFERENCES app_user(id) ON DELETE CASCADE;
ALTER TABLE report ALTER COLUMN session_id DROP NOT NULL;
ALTER TABLE report ADD CONSTRAINT report_exactly_one_owner
    CHECK ((session_id IS NULL) <> (user_id IS NULL));
-- Serves: owner-scoped lookups for users, and the Phase 6b history list.
CREATE INDEX report_user_created_idx ON report (user_id, created_at DESC);
```

**Notes**
- `display_name` comes from the Google `name` claim, trimmed and cut to 100 characters. If it's missing or blank, store `"User"`.
- No email, picture, locale, or Google token is stored anywhere.
- Existing Phase 2 reports all have `session_id` set, so the new constraint holds for existing rows.

---

## 5. Google ID token verification (`GoogleIdTokenVerifier`)

- A dedicated `JwtDecoder` (Nimbus, built with Google's JWK set URI `https://www.googleapis.com/oauth2/v3/certs`). Spring and Nimbus cache the keys. Check the current caching behavior and configuration in Context7.
- **Algorithm:** RS256 only.
- **Validators:**
  - **Issuer:** `accounts.google.com` or `https://accounts.google.com` (both are valid for Google).
  - **Audience:** contains `GOOGLE_CLIENT_ID`.
  - **Expiry and not-before:** with 30 seconds of clock skew.
  - **Subject:** `sub` is present and not blank.
- **Output:** `GoogleIdentity(sub, displayName)`. Every other claim is ignored and dropped.
- **Failures:** every verification failure becomes `401 GOOGLE_TOKEN_INVALID`. The log line names only the category (`signature`, `issuer`, `audience`, `expired`, `malformed`, `jwks_unavailable`). The token itself is never logged.
- **Google's keys unreachable:** if the JWKS fetch fails and nothing is cached, return `401 GOOGLE_TOKEN_INVALID` and log `jwks_unavailable` at WARN. Don't add a new error code.
- **Config:** `GOOGLE_CLIENT_ID` is required in the `prod` profile (startup fails without it). In `local`, a blank value disables Google sign-in: the endpoint always returns `GOOGLE_TOKEN_INVALID`, and startup logs one WARN line saying sign-in is disabled.
- **Tests** inject a decoder built from a local test key (section 13). One test also serves a JWK set from a local HTTP server to cover the URI-based configuration.

---

## 6. Access tokens and the security chain

### 6.1 Signing keys (`SigningKeyRing`)

- **Format:** `JWT_SIGNING_KEYS=kid1:base64key,kid2:base64key`, with `JWT_CURRENT_KID=kid2`.
- **Startup validation:**
  - each `kid` is non-blank and unique
  - each key decodes from base64 to at least 32 bytes
  - `JWT_CURRENT_KID` is in the list
  - Startup fails with a clear message otherwise. The message names the problem and the `kid`, never the key.
- **Shape:** keys become `OctetSequenceKey` JWKs with their `kid` and algorithm HS256, in one immutable JWK set.

### 6.2 Issuing (`AccessTokenIssuer`)

- **Encoder:** Spring's `NimbusJwtEncoder` over the JWK set. The JWS header sets `alg=HS256` and `kid=<current>` so the encoder picks the current key. Verify the exact key-selection behavior in Context7; when several keys are in the set, the header must name one.
- **Claims:**

| Claim | Value |
|---|---|
| `iss` | `JWT_ISSUER` (default `medi-scan`) |
| `aud` | `JWT_AUDIENCE` (default `medi-scan-api`) |
| `sub` | the user's id (UUID), not the Google `sub` |
| `role` | `USER` or `ADMIN` |
| `iat` | now |
| `exp` | now + `ACCESS_TOKEN_TTL_MINUTES` (15) |
| `jti` | random UUID |

### 6.3 Validating (`AccessTokenDecoderConfig`, `ExpiryValidator`)

- **Decoder:** `NimbusJwtDecoder` built from a JWT processor whose key selector looks up keys by `kid` in the same JWK set. A token without a `kid`, or with an unknown one, fails.
- **Validators:**
  - **Issuer:** equals `JWT_ISSUER`.
  - **Audience:** contains `JWT_AUDIENCE`.
  - **Expiry:** `ExpiryValidator` replaces the default timestamp validator. It checks `exp` and `nbf` with 30 seconds of skew, and returns an `OAuth2Error` with the custom error code `token_expired` when `exp` has passed. The entry point uses that code to choose `TOKEN_EXPIRED` over `TOKEN_INVALID`. Don't match on error description text.
  - **Required claims:** `sub` (UUID) and `role` (`USER` or `ADMIN`).
- **Authorities:** a `JwtAuthenticationConverter` maps `role` to `ROLE_USER` or `ROLE_ADMIN`. The principal name is the user id.

### 6.4 Filter chain (`config.SecurityConfig`)

Stateless, CSRF disabled (with the existing comment), CORS from Phase 1.

**Filter order:**
1. Request id filter (Phase 1)
2. `AuthRateLimitFilter` and the other rate limit filters
3. `BearerTokenAuthenticationFilter` (from the OAuth2 resource server)
4. `GuestSessionFilter`, which runs only when no `Authorization` header is present

A request with an `Authorization` header is authenticated by the bearer filter or rejected. It never falls through to the guest filter.

**Route rules:**

| Route | Rule |
|---|---|
| `OPTIONS /**` | permit (CORS preflight) |
| `POST /api/sessions`, `GET /api/samples`, `GET /actuator/health` | permit |
| `POST /api/auth/google`, `POST /api/auth/refresh`, `POST /api/auth/logout` | permit |
| `/api/admin/**` | `hasRole('ADMIN')`. Controllers there also use `@PreAuthorize("hasRole('ADMIN')")` |
| `/api/me/**`, `/api/trends/**` | an authenticated user (bearer) |
| `/api/reports/**` | an authenticated user or guest |
| anything else | keep the Phase 1 rule |

**Entry point mapping (`config.ProblemAuthenticationEntryPoint`, extended):**

| Situation | Response |
|---|---|
| Bearer token present, expired (`token_expired`) | 401 `TOKEN_EXPIRED` + `WWW-Authenticate: Bearer error="invalid_token"` |
| Bearer token present, any other failure | 401 `TOKEN_INVALID` + same header |
| No valid credential on `/api/reports/**` | 401 `SESSION_INVALID` |
| No bearer on `/api/me/**`, `/api/trends/**`, `/api/admin/**` | 401 `AUTH_REQUIRED` |

**Access denied mapping (`config.ProblemAccessDeniedHandler`):**

| Situation | Response |
|---|---|
| Guest on a signed-in-only route | 401 `AUTH_REQUIRED` |
| User without `ADMIN` on `/api/admin/**` | 403 `FORBIDDEN` |

All of these are Problem Details with `code` and `requestId`, as in Phase 1.

### 6.5 Owner resolution (`config.CurrentOwner`)

A `HandlerMethodArgumentResolver` gives controllers an `OwnerRef`:

- **Guest principal:** `OwnerRef.Guest(sessionId)`.
- **Bearer principal:** `OwnerRef.User(userId)`, after a primary-key existence check on `app_user`. If the user no longer exists (account deleted within the last 15 minutes), the request fails with `401 AUTH_REQUIRED`. This makes deleted accounts stop working immediately and prevents foreign-key errors on upload.

Report code never reads the security context directly. It takes the `OwnerRef`.

---

## 7. Flows

### 7.1 Sign-in (`AuthService.signIn`)

```text
signIn(idToken, guestSessionToken):
  identity = googleVerifier.verify(idToken)              # 401 GOOGLE_TOKEN_INVALID on failure
  role = adminSubjects.contains(identity.sub) ? ADMIN : USER
  transaction:
    (user, newUser) = users.upsert(identity.sub, identity.displayName, role, now)
    claimed = 0
    if guestSessionToken is present:
        session = sessions.findValid(hash(guestSessionToken))      # missing or expired: skip, not an error
        if session:
            claimed = reports.claimGuestReports(session.id, user.id, userRetention)
            sessions.delete(session.id)
    refresh = refreshTokens.issueNewFamily(user.id, now)
  access = accessTokenIssuer.issue(user, now)
  return { access, refresh, user, claimed, newUser }
```

**User upsert** (one statement):

```sql
INSERT INTO app_user (id, google_sub, display_name, role, created_at, last_login_at)
VALUES (:newId, :sub, :name, :role, :now, :now)
ON CONFLICT (google_sub) DO UPDATE
    SET display_name = EXCLUDED.display_name,
        role = EXCLUDED.role,
        last_login_at = EXCLUDED.last_login_at
RETURNING id, display_name, role, created_at, (xmax = 0) AS inserted;
```

`inserted` is `newUser`. Two parallel first sign-ins for the same `sub` both end with one row.

**Claiming guest reports** (one statement, then delete the session):

```sql
UPDATE report
   SET user_id = :userId,
       session_id = NULL,
       expires_at = created_at + make_interval(days => :userRetentionDays)
 WHERE session_id = :sessionId;
```

- Reports that are still `PENDING` or `PROCESSING` move too. The background job updates by report id, so it doesn't care about the owner.
- The update must come before the session delete, otherwise the cascade would delete the reports.
- If anything in the transaction fails, nothing changes and the guest keeps their session and reports.

### 7.2 Refresh (`AuthService.refresh`)

The reuse branch revokes the family and must commit even though the request fails. So the transactional method returns a result instead of throwing, and the controller maps the result to the error.

```text
refresh(rawToken) -> RefreshOutcome (sealed: Rotated | Invalid | ReuseDetected)
  transaction:
    row = SELECT ... FROM refresh_token WHERE token_hash = :hash FOR UPDATE
    if row is null: return Invalid
    if row.revoked_at is not null:
        UPDATE refresh_token SET revoked_at = :now WHERE family_id = :family AND revoked_at IS NULL
        log WARN "auth reuse-detected family=… user=…"
        return ReuseDetected
    if row.expires_at <= now: return Invalid
    newExpiry = min(now + refreshTtl, row.family_started_at + familyMaxAge)
    if newExpiry <= now: return Invalid                      # family is past its 30-day limit
    UPDATE refresh_token SET revoked_at = :now, replaced_by = :newId WHERE id = :id AND revoked_at IS NULL
        -> must update 1 row (FOR UPDATE makes this certain)
    INSERT new refresh_token (same family_id, same family_started_at, expires_at = newExpiry)
    user = users.find(row.user_id)
    role = adminSubjects.contains(user.google_sub) ? ADMIN : USER
    if role != user.role: UPDATE app_user SET role = :role WHERE id = :id
  return Rotated(accessTokenIssuer.issue(user with role), newRawToken, user)

controller: Rotated -> 200, Invalid or ReuseDetected -> 401 REFRESH_TOKEN_INVALID
```

Two concurrent refreshes with the same token: `FOR UPDATE` serializes them. The second one sees `revoked_at` set and is treated as reuse, which revokes the family. That matches the design: within one tab, the client must share a single refresh call.

### 7.3 Logout

```text
logout(rawToken):
  row = find by hash
  if row: UPDATE refresh_token SET revoked_at = :now WHERE family_id = :family AND revoked_at IS NULL
  always 204
```

### 7.4 `GET /api/me`

Load the user by id from the `OwnerRef`. Return `id`, `displayName`, `role`, `createdAt`, and `reportCount` (count of the user's reports with `expires_at > now()`).

### 7.5 `DELETE /api/me`

`DELETE FROM app_user WHERE id = :userId`. The cascade removes reports, report text, biomarkers, summaries, and refresh tokens. Return 204. Jobs still running for those reports stop at their next conditional update (Phase 2 behavior). Log `account deleted user=…`.

### 7.6 Token generation and hashing

- Refresh tokens: 32 bytes from `SecureRandom`, base64url without padding (43 characters), the same way guest session tokens are made in Phase 1.
- Hash with SHA-256, the same helper as guest sessions. Reuse the Phase 1 helper instead of writing a second one.

---

## 8. Report ownership changes

- **Create:** `CreateReportService` sets `user_id` or `session_id` from the `OwnerRef`.
  - **Expiry:** a user's report gets `expires_at = now + USER_RETENTION_DAYS`. A guest's report keeps the session's expiry.
- **Read, delete:** every repository method switches on the `OwnerRef`:
  - **Guest:** `WHERE id = :id AND session_id = :owner AND expires_at > now()`
  - **User:** `WHERE id = :id AND user_id = :owner AND expires_at > now()`
- **Wrong owner:** another owner's report is still `404 REPORT_NOT_FOUND`, for every combination (guest A and guest B, user A and user B, guest and user in both directions).
- **Rate limits:** the upload bucket key is `user:<id>` when signed in and `ip:<address>` otherwise.

---

## 9. Rate limits

| Limit | Key | Default | Exceeded |
|---|---|---|---|
| `POST /api/auth/google` | IP | 10 per minute | 429 `RATE_LIMITED` + `Retry-After` |
| `POST /api/auth/refresh` | IP | 10 per minute (separate bucket) | same |
| `POST /api/auth/logout` | none | | |
| Uploads | user id or IP | 10 per hour | same (Phase 2 behavior) |

Rejections count in `daily_stats.rate_limit_rejections`, like Phase 2.

---

## 10. Retention additions

Added to the existing `RetentionJob`:

- **Every 15 minutes:** delete refresh tokens where `expires_at < now()`, or where `revoked_at < now() - 1 day`. Revoked tokens are kept for a day so reuse can still be detected.
- **Daily:** delete users with `last_login_at < now() - INACTIVE_ACCOUNT_DAYS` (180). The cascade removes their reports and tokens.
- The existing expired-report safety net already covers user reports, because it filters on `expires_at`.

---

## 11. Configuration

| Property | Env var | Default |
|---|---|---|
| `auth.google.client-id` | `GOOGLE_CLIENT_ID` | none (required in `prod`) |
| `auth.google.jwk-set-uri` | none | `https://www.googleapis.com/oauth2/v3/certs` |
| `auth.jwt.signing-keys` | `JWT_SIGNING_KEYS` | none (required) |
| `auth.jwt.current-kid` | `JWT_CURRENT_KID` | none (required) |
| `auth.jwt.issuer` | `JWT_ISSUER` | `medi-scan` |
| `auth.jwt.audience` | `JWT_AUDIENCE` | `medi-scan-api` |
| `auth.jwt.access-ttl` | `ACCESS_TOKEN_TTL_MINUTES` | 15 minutes |
| `auth.jwt.clock-skew` | none | 30 seconds |
| `auth.refresh.ttl` | `REFRESH_TOKEN_TTL_DAYS` | 7 days |
| `auth.refresh.family-max-age` | `REFRESH_FAMILY_MAX_DAYS` | 30 days |
| `auth.admin-subjects` | `ADMIN_GOOGLE_SUBS` | empty |
| `retention.user-report-days` | `USER_RETENTION_DAYS` | 30 |
| `retention.inactive-account-days` | `INACTIVE_ACCOUNT_DAYS` | 180 |
| `ratelimit.auth-per-minute` | none | 10 |

The `local` profile's `.env` gets a generated development key: `openssl rand -base64 32`. Local and production keys are never the same.

---

## 12. Logging

| Event | Level | Fields |
|---|---|---|
| Sign-in | INFO | `user`, `newUser`, `claimed` |
| Google token rejected | INFO | `reason` category only |
| Refresh rotated | DEBUG | `user`, `family` |
| Reuse detected | WARN | `user`, `family` |
| Logout | INFO | `family` (if found) |
| Account deleted | INFO | `user` |
| Bearer rejected | DEBUG | `reason` (`expired` or `invalid`) |

**Never logged:**
- any raw token (ID token, access token, refresh token, session token)
- the Google `sub`
- the display name
- request bodies of the auth endpoints

Check that the framework's own logging doesn't print the `Authorization` header or the request body at the configured levels.

---

## 13. Tests

### 13.1 Test helpers

- **`TestGoogleIssuer`** creates an RSA key pair in the test, mints Google-style ID tokens (with chosen `iss`, `aud`, `exp`, `sub`, `name`), and provides a `JwtDecoder` built from its public key. It replaces the Google decoder bean in tests.
- **`TestTokens`** mints the app's own access tokens with any `kid`, role, expiry, issuer, or audience, including deliberately wrong ones.
- **No test calls Google.** The one URI-based JWKS test serves the test key set from a local HTTP server (for example the JDK's `HttpServer`).
- **Time:** tests that depend on time use the injected `Clock`.

### 13.2 Unit tests

| Class | Cases |
|---|---|
| `SigningKeyRing` | Valid list; duplicate `kid`; key under 32 bytes; bad base64; current `kid` missing; error messages contain no key material |
| `ExpiryValidator` | Expired beyond skew → `token_expired`; within skew → valid; `nbf` in the future → invalid |
| `GoogleIdTokenVerifier` | Valid token; each issuer variant; wrong audience; expired; bad signature; missing `sub`; blank `name` → `"User"`; long `name` → cut to 100 |
| `AdminSubjects` | Parsing with spaces and empty entries |

### 13.3 Integration tests (Testcontainers)

**Sign-in**
1. New user: 200, `newUser: true`, tokens returned, a user row with the right role, one refresh token row.
2. Same user again: `newUser: false`, `last_login_at` updated, a second family created.
3. A `sub` in `ADMIN_GOOGLE_SUBS` gets `ADMIN`. Removing it and refreshing changes the role to `USER` in the new access token.
4. Each bad ID token → 401 `GOOGLE_TOKEN_INVALID`.
5. Claim: a guest uploads two samples, then signs in with `guestSessionToken` → `claimedReportCount: 2`. Both reports are reachable with the bearer token, unreachable with the old guest token (401 `SESSION_INVALID`, since the session is gone), and their `expires_at` is 30 days after creation.
6. Claim during processing: a report still `PROCESSING` (fake LLM blocked) is claimed, the job then finishes, and the report is `DONE` under the user.
7. Invalid or expired `guestSessionToken`: sign-in succeeds with `claimedReportCount: 0`.
8. Claim rollback: force a failure after the claim update (for example a failing refresh-token insert in a test configuration) → the guest still owns the reports and the session still exists.

**Refresh and logout**
9. Rotation: the refresh returns new tokens; the old refresh token then fails with 401 `REFRESH_TOKEN_INVALID`.
10. Reuse: using the old token again revokes the whole family. The newest token in the family also stops working.
11. Concurrent refresh with the same token (two threads): exactly one succeeds, and the family ends up revoked.
12. Expired refresh token → 401. Family past 30 days → 401 even if the token itself is fresh.
13. Logout revokes the family; logout with an unknown token still returns 204.

**Access tokens and the chain**
14. Expired access token → 401 `TOKEN_EXPIRED` with the `WWW-Authenticate` header.
15. Wrong issuer, wrong audience, bad signature, unknown `kid`, missing `kid`, missing `role` → 401 `TOKEN_INVALID`.
16. Key rotation: a token signed with an old key that is still listed is accepted; after removing that key it gets `TOKEN_INVALID`; a refresh token still works after rotation.
17. Bad bearer token plus a valid guest token on `/api/reports/{id}` → 401 `TOKEN_INVALID` (no fallback to guest).

**Authorization matrix**
18. For `GET` and `DELETE /api/reports/{id}`, every combination of owner and caller (guest A, guest B, user A, user B, anonymous) gives either 200/204 for the owner or the right error (404 for other owners, 401 for anonymous).
19. Guest on `/api/me` → 401 `AUTH_REQUIRED`. User on `/api/me` → 200 with the right `reportCount`.
20. `/api/admin/**` via a controller that exists only in test sources: anonymous and guest → 401 `AUTH_REQUIRED`, user → 403 `FORBIDDEN`, admin → 200.
21. A user uploads a report → it has `user_id` set, `session_id` null, and a 30-day expiry.

**Account deletion**
22. `DELETE /api/me` → 204, and the user's reports, report text, biomarkers, summaries, and refresh tokens are gone.
23. The same, still-valid access token afterwards → 401 `AUTH_REQUIRED` on `/api/me` and on `/api/reports/{id}`.

**Limits and retention**
24. The 11th sign-in attempt in a minute from one IP → 429 `RATE_LIMITED` with `Retry-After`. Same for refresh.
25. Upload limit for a user is keyed by user id: two users behind the same IP each get their own 10.
26. Retention removes expired and day-old revoked refresh tokens, keeps active ones, and removes users inactive for 180 days together with their data.

**Contract and privacy**
27. Contract checks: the JSON of every new endpoint and error matches `docs/api-contract.md` sections 2.3 to 2.7 field by field.
28. No tokens in logs: run sign-in, refresh, reuse, logout, and account deletion while capturing logs at all levels; assert none of the raw tokens, the Google `sub`, or the display name appear.
29. The database has no column that holds an email, a picture URL, or a raw token (a schema test listing the `app_user` and `refresh_token` columns).

---

## 14. Manual check without the real UI

A throwaway page prints a real Google ID token so you can test the backend with `curl`.

- **Location:** `tools/dev-signin/index.html`. It is outside `client/` and is never deployed. A README next to it explains this.
- **What it does:** loads Google Identity Services, has an input for the client ID (typed in, not committed), renders the "Sign in with Google" button, and shows the returned ID token in a text box with a copy button. It makes no calls to the backend.
- **How to serve it:** `python3 -m http.server 5500 --directory tools/dev-signin`. Add `http://localhost:5500` as an authorized JavaScript origin on the Google OAuth client (development only).

**Manual run:**
1. `POST /api/sessions`, then upload a sample as that guest.
2. Get an ID token from the dev page.
3. `POST /api/auth/google` with `idToken` and `guestSessionToken` → `claimedReportCount: 1`.
4. `GET /api/me` with the access token → your display name and `reportCount: 1`.
5. Refresh, then try the old refresh token again → 401, and the new one is also revoked.
6. Sign in again, then `DELETE /api/me` → 204; the old access token now gets 401 `AUTH_REQUIRED`.
7. To make yourself admin: after the first sign-in, read your `google_sub` from the `app_user` table, put it in `ADMIN_GOOGLE_SUBS`, restart, and refresh. The new access token has `role: ADMIN`.

---

## 15. Done checklist

1. `./mvnw verify` passes, including all tests in section 13.
2. The doc changes in section 2 are made.
3. The manual run in section 14 works if `GOOGLE_CLIENT_ID` is set. If it isn't, say so, and the automated tests stand in.
4. The application log from the manual run contains no tokens, no Google `sub`, and no display name.
5. The Docker image still builds for `linux/arm64`.
6. `SECURITY.md` gets an "Accounts" section: what is stored about users, token lifetimes, where the client keeps tokens and the XSS trade-off, key rotation, and account deletion.

---

## 16. Decisions to confirm before building

1. **HS256 with key ids** for the app's own tokens (one service signs and verifies, so an asymmetric algorithm isn't needed).
2. **A user-existence check on every bearer request** (one primary-key lookup). This makes account deletion take effect immediately and avoids foreign-key errors.
3. **Role recomputed from config on every refresh,** so admin changes apply within 15 minutes.
4. **`GOOGLE_CLIENT_ID` optional in `local`** (sign-in disabled with a warning) and required in `prod`.
5. **The dev sign-in page is committed under `tools/`** and never deployed.
6. **Google's `name` claim stored as the display name,** with `"User"` as the fallback. No email.