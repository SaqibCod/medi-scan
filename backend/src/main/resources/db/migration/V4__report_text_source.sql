-- Phase 4: OCR.
--
-- Two columns, both about text the pipeline did not simply read: one on the
-- report saying how its text was obtained, one counter for the values OCR was
-- not sure about (docs/phase-4-lld.md sections 4 and 8).

-- How this report's text was obtained.
--
-- Needed because source_type cannot answer it: a scanned PDF is source_type
-- 'PDF' and text_source 'OCR', and the client has to know the difference to
-- decide whether to ask the reader to check the values against the paper
-- report (contract 4.2).
--
-- Null for every report created before this migration, and for any report that
-- failed: it is written in the same conditional update that sets DONE
-- (ReportResultSaver), so a report that never finished has none. The API maps a
-- null on a DONE report to 'TEXT_LAYER', which is what those reports were -
-- there was no OCR path before this phase.
--
-- Constrained to a list, unlike error_code: these three values are the whole
-- domain, they appear in the contract as a closed union, and a fourth would be
-- a breaking API change rather than something to discover in production.
ALTER TABLE report ADD COLUMN text_source text
    CHECK (text_source IN ('TEXT_LAYER', 'OCR', 'MIXED'));

-- How many values the low-confidence rule turned into UNKNOWN today.
--
-- A count, nothing else: no report id, no value, no confidence score. It exists
-- to answer "is the confidence threshold costing more than it should" from the
-- aggregate, which is the only question worth asking without looking at report
-- content (CLAUDE.md rule 3).
--
-- NOT NULL DEFAULT 0 so the existing upsert in StatsRecorder keeps working
-- unchanged on rows written before this column existed. The admin endpoint does
-- not expose it - that would change the contract, and phase 7d can add it.
ALTER TABLE daily_stats ADD COLUMN ocr_low_confidence_values integer NOT NULL DEFAULT 0;
