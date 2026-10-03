-- Phase 1 baseline.
--
-- Only what phase 1 uses. Later phases add their own migrations rather than
-- editing this file: once a migration is committed it is immutable, because
-- Flyway records its checksum.
--
-- The full target data model is in docs/plan.md section 7.

-- Backs the biomarker reference-page embeddings that chat RAG retrieves from.
-- Created here as well as in docker/postgres/init-extensions.sql, because that
-- script only runs when a brand-new volume is initialised, and managed
-- Postgres (Neon) is provisioned without it.
CREATE EXTENSION IF NOT EXISTS vector;

-- A guest session. Guests are the default: every core feature works without
-- signing in (docs/plan.md section 4.9).
CREATE TABLE session (
    id         uuid        PRIMARY KEY,

    -- SHA-256 of the token, hex encoded. The raw token is returned to the
    -- client exactly once and is never stored or logged, so a dump of this
    -- table cannot be replayed as a credential.
    token_hash varchar(64) NOT NULL UNIQUE,

    created_at timestamptz NOT NULL,

    -- 24 hours after creation. Reports owned by this session expire with it,
    -- and reads filter on expires_at > now() so expired data is never served
    -- even between runs of the cleanup job (docs/dataflow.md section 9.2).
    expires_at timestamptz NOT NULL
);

-- The retention job deletes by expiry, and it is the only query that scans
-- rather than looking a single row up by hash.
CREATE INDEX idx_session_expires_at ON session (expires_at);

COMMENT ON TABLE session IS
    'Guest sessions. Token hashes only, never raw tokens. Reports cascade from here.';
