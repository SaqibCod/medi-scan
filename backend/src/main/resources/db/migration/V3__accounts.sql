-- Phase 6a: accounts.
--
-- Adds users, refresh tokens, and a second kind of report owner. Reports
-- stop being guest-only here: V2 created them with session_id NOT NULL, and
-- this migration relaxes that and adds the check constraint that keeps
-- "exactly one owner" true in the database rather than only in code
-- (docs/plan.md section 7, CLAUDE.md rule 7).

-- A signed-in user. Deliberately almost empty: Google's `sub`, a display
-- name for the header, a role, and timestamps. No email address, no photo,
-- no locale, and never the Google ID token (CLAUDE.md rule 13).
CREATE TABLE app_user (
    id             uuid PRIMARY KEY,

    -- Google's stable subject id. UNIQUE is what makes the sign-in upsert
    -- safe: two parallel first sign-ins for the same person contend on this
    -- index and end with one row.
    google_sub     text NOT NULL UNIQUE,

    -- The Google `name` claim, trimmed and cut to 100 characters, or 'User'
    -- when Google sent nothing usable. Never logged.
    display_name   text NOT NULL,

    -- Recomputed from ADMIN_GOOGLE_SUBS on every sign-in and refresh, so a
    -- config change applies within one access token lifetime. No endpoint
    -- writes this (CLAUDE.md rule 17).
    role           text NOT NULL CHECK (role IN ('USER','ADMIN')),

    created_at     timestamptz NOT NULL,
    last_login_at  timestamptz NOT NULL
);

-- Serves: the inactive-account cleanup's DELETE ... WHERE last_login_at < ?
CREATE INDEX app_user_last_login_idx ON app_user (last_login_at);

COMMENT ON TABLE app_user IS
    'Signed-in users. Google sub, display name, role and timestamps only - no email, no photo, no tokens.';

-- One refresh token, stored as a hash. Tokens belong to a "family": one
-- sign-in produces one family, and every refresh replaces a member of it.
-- Reusing an already-revoked member means someone has a stolen copy, so the
-- whole family is revoked (docs/plan.md section 4.9).
CREATE TABLE refresh_token (
    id                 uuid PRIMARY KEY,

    -- CASCADE is the retention mechanism: deleting the account must take
    -- every token with it, with nothing left to replay.
    user_id            uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,

    family_id          uuid NOT NULL,

    -- SHA-256 hex, the same shape and the same helper as session.token_hash
    -- in V1. The raw token is returned to the client once and never stored
    -- or logged (CLAUDE.md rule 14).
    token_hash         varchar(64) NOT NULL UNIQUE,

    issued_at          timestamptz NOT NULL,

    -- min(issue + REFRESH_TOKEN_TTL_DAYS, family_started_at +
    -- REFRESH_FAMILY_MAX_DAYS), so a family cannot be kept alive forever by
    -- refreshing it.
    expires_at         timestamptz NOT NULL,

    -- Copied unchanged into every member of the family, which is what caps
    -- the family's total lifetime.
    family_started_at  timestamptz NOT NULL,

    -- Set when the token is rotated away, revoked on logout, or revoked
    -- because reuse was detected. Null means live.
    revoked_at         timestamptz,

    -- The token issued in its place. Audit only; nothing reads it to make a
    -- decision, so it is not a foreign key that could block a delete.
    replaced_by        uuid
);

-- Serves: revoking a whole family on logout or on reuse detection.
CREATE INDEX refresh_token_family_idx ON refresh_token (family_id);

-- Serves: the cleanup job's delete by expiry.
CREATE INDEX refresh_token_expires_idx ON refresh_token (expires_at);

COMMENT ON TABLE refresh_token IS
    'Refresh token hashes, grouped into per-sign-in families. Rotated on every use; reuse revokes the family.';

-- Reports can now belong to a user instead of a guest session.
--
-- Nullable with a check constraint rather than two tables or a polymorphic
-- owner column: every report query filters on one of these two columns, and
-- the constraint is what stops a report from having two owners or none.
ALTER TABLE report ADD COLUMN user_id uuid REFERENCES app_user(id) ON DELETE CASCADE;
ALTER TABLE report ALTER COLUMN session_id DROP NOT NULL;

-- Every phase 2 report has session_id set and user_id null, so this holds
-- for existing rows without a backfill.
ALTER TABLE report ADD CONSTRAINT report_exactly_one_owner
    CHECK ((session_id IS NULL) <> (user_id IS NULL));

-- Serves: owner-scoped lookups for a signed-in caller, and the phase 6b
-- history list's newest-first ordering.
CREATE INDEX report_user_created_idx ON report (user_id, created_at DESC);

COMMENT ON COLUMN report.user_id IS
    'Owner when the report belongs to a signed-in user. Exactly one of this and session_id is set.';
