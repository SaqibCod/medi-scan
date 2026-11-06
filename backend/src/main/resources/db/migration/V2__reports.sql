-- Phase 2: the report pipeline.
--
-- Reports belong to a guest session here. Phase 6 adds user_id, makes
-- session_id nullable, and adds a check that exactly one of the two is set
-- (docs/plan.md section 7). Doing it that way round keeps this migration
-- honest about what phase 2 can actually own.
--
-- session.id is uuid (V1__baseline.sql), so every foreign key below matches.

-- One uploaded, pasted or sampled report.
CREATE TABLE report (
    id            uuid        PRIMARY KEY,

    -- ON DELETE CASCADE is the retention mechanism, not a convenience: deleting
    -- an expired session must take its reports and all their child rows with it
    -- (CLAUDE.md "Retention", docs/dataflow.md section 9.2).
    session_id    uuid        NOT NULL REFERENCES session(id) ON DELETE CASCADE,

    source_type   text        NOT NULL CHECK (source_type IN ('PDF', 'IMAGE', 'TEXT', 'SAMPLE')),

    -- The four states in docs/dataflow.md section 4.3. Checked in the database
    -- as well as the enum, so a bad write fails loudly instead of producing a
    -- report the API cannot describe.
    status        text        NOT NULL CHECK (status IN ('PENDING', 'PROCESSING', 'DONE', 'FAILED')),

    -- One of contract section 8.2's report failure codes, set only when
    -- status = 'FAILED'. Not constrained to a list: adding a code must not
    -- require a migration, and ReportErrorCode is the enforcement point.
    error_code    text,

    -- The specimen collection date, kept only when CollectedDateParser could
    -- read it unambiguously from the masked text. Null is the normal case for a
    -- report that does not print one, not an error.
    collected_on  date,

    created_at    timestamptz NOT NULL,

    -- Bound the job run and feed daily_processing. Null until the job reaches
    -- each point, so a PENDING report has neither.
    started_at    timestamptz,
    finished_at   timestamptz,

    -- Copied from the owning session's expires_at at creation. Reads filter on
    -- this directly so an expired report is never served, even in the window
    -- before the retention job removes it.
    expires_at    timestamptz NOT NULL
);

-- Serves: the owner-scoped lookup in GET /api/reports/{id}, and the report list
-- that arrives in phase 6.
CREATE INDEX report_session_created_idx ON report (session_id, created_at DESC);

-- Serves: the retention sweep's DELETE ... WHERE expires_at < now() safety net.
CREATE INDEX report_expires_idx ON report (expires_at);

-- Serves: StartupRecovery, which scans for jobs a restart interrupted. Partial,
-- because the rows it wants are a tiny fraction of the table and only ever the
-- two live states.
CREATE INDEX report_active_idx ON report (status) WHERE status IN ('PENDING', 'PROCESSING');

COMMENT ON TABLE report IS
    'One report per upload, paste or sample. Owned by a guest session in phase 2.';

-- The masked text, and only ever the masked text. Raw extracted text never
-- reaches the database (CLAUDE.md rule 1).
--
-- A row appears here only when the report reaches DONE: the final transaction
-- writes it alongside the biomarkers and summary, so a report that failed
-- stores no text at all (docs/dataflow.md section 4.2).
CREATE TABLE report_text (
    report_id   uuid PRIMARY KEY REFERENCES report(id) ON DELETE CASCADE,
    masked_text text NOT NULL
);

COMMENT ON TABLE report_text IS
    'Masked report text, written only on success. Never raw text.';

-- One validated lab value. Every numeric field here was parsed in code from the
-- model's strings, and every flag was computed in code (CLAUDE.md rule 4).
CREATE TABLE biomarker (
    id                   uuid             PRIMARY KEY,
    report_id            uuid             NOT NULL REFERENCES report(id) ON DELETE CASCADE,

    -- The order the value had in the report, so results are shown the way they
    -- were printed rather than in whatever order the database returns.
    position             integer          NOT NULL,

    test_name            text             NOT NULL,

    -- Lowercased, punctuation removed, spaces collapsed. Used to match the same
    -- marker across reports for phase 6 trends, and to look up a slug.
    test_name_norm       text             NOT NULL,

    -- The curated biomarker page this row links to, or null when the name did
    -- not match the catalogue.
    biomarker_slug       text,

    -- Exactly as printed on the report, and verified to appear in the masked
    -- text before this row was kept.
    raw_value            text             NOT NULL,

    -- Set only when the value is a plain number. A comparator value such as
    -- "<0.5" stores null, because it is not a point on a trend line.
    numeric_value        double precision,

    unit                 text,
    reference_range_text text,

    -- Parsed bounds, null when the range had no such bound or could not be read.
    ref_low              double precision,
    ref_high             double precision,

    -- No CRITICAL: critical values need separate cutoffs that cannot be derived
    -- from a reference range (docs/plan.md section 4.4).
    flag                 text             NOT NULL CHECK (flag IN ('LOW', 'NORMAL', 'HIGH', 'UNKNOWN'))
);

-- Serves: loading one report's results in printed order.
CREATE INDEX biomarker_report_idx ON biomarker (report_id, position);

-- Serves: matching the same marker across a user's reports, for phase 6 trends.
CREATE INDEX biomarker_norm_idx ON biomarker (test_name_norm);

COMMENT ON TABLE biomarker IS
    'Validated lab values. Numbers re-parsed and flags recomputed in code, never taken from the model.';

-- The step-2 summary, written from the validated biomarkers only.
CREATE TABLE report_summary (
    report_id       uuid  PRIMARY KEY REFERENCES report(id) ON DELETE CASCADE,
    patient_summary text  NOT NULL,

    -- One short sentence per LOW or HIGH marker. jsonb rather than a child
    -- table: they are read and written as a whole list, never queried into.
    highlights_json jsonb NOT NULL DEFAULT '[]'
);

-- The global daily LLM budget. One row per UTC day, incremented once per
-- attempt by an atomic conditional UPDATE (CLAUDE.md rule 8).
CREATE TABLE llm_usage (
    day   date    PRIMARY KEY,
    calls integer NOT NULL DEFAULT 0
);

COMMENT ON TABLE llm_usage IS
    'Daily LLM call counter. The conditional UPDATE on this table is the cap.';

-- The three tables below back the admin dashboard. Counters only: no report
-- ids, no session ids, no user ids, no content. They outlive the reports they
-- counted, which is why they are not cascaded from anything.
CREATE TABLE daily_stats (
    day                   date    PRIMARY KEY,
    input_tokens          bigint  NOT NULL DEFAULT 0,
    output_tokens         bigint  NOT NULL DEFAULT 0,
    reports_created       integer NOT NULL DEFAULT 0,
    reports_failed        integer NOT NULL DEFAULT 0,
    rate_limit_rejections integer NOT NULL DEFAULT 0,
    masking_conflicts     integer NOT NULL DEFAULT 0
);

CREATE TABLE daily_failure (
    day   date    NOT NULL,
    code  text    NOT NULL,
    count integer NOT NULL DEFAULT 0,
    PRIMARY KEY (day, code)
);

CREATE TABLE daily_processing (
    day         date    NOT NULL,
    source_type text    NOT NULL,
    total_ms    bigint  NOT NULL DEFAULT 0,
    count       integer NOT NULL DEFAULT 0,
    PRIMARY KEY (day, source_type)
);

COMMENT ON TABLE daily_stats IS
    'Aggregated daily counters for the admin dashboard. Never holds ids or content.';
