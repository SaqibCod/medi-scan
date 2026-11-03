-- Phase 5: chat with RAG.
--
-- Two new tables and five counters (docs/phase-5-lld.md sections 4 and 8).
-- Neither table holds report data, so neither joins the retention cascade:
-- vector_store is curated reference content shipped in the image, and
-- content_index records what has been embedded from it. Nothing a user typed
-- or uploaded reaches either one (CLAUDE.md rules 1 and 9).

-- Chunks of the curated biomarker pages, with their embeddings.
--
-- Named vector_store because docs/plan.md section 7 and docs/dataflow.md 9.1
-- both call it that, but the columns are ours rather than Spring AI's
-- PgVectorStore layout. That store embeds inside add() and similaritySearch(),
-- which would route model calls around the daily cap and the usage log
-- (CLAUDE.md rule 8), and it offers no way to ask for RETRIEVAL_QUERY on a
-- search and RETRIEVAL_DOCUMENT on an insert - a distinction the embedding API
-- does support and retrieval quality depends on. So EmbeddingGateway owns every
-- call and a small JdbcClient repository owns this table, which LLD C3 allows
-- in as many words.
--
-- Real columns instead of a metadata jsonb blob, for the same reason: slug is
-- what re-indexing deletes by and what the report boost looks up, and a btree
-- index on a column beats a JSON path expression on both clarity and cost.
CREATE TABLE vector_store (
    id          uuid    PRIMARY KEY,

    -- The page this chunk came from, matching the markdown filename and the
    -- slug in biomarkers/index.json. Not a foreign key: the pages are files on
    -- the classpath, not rows.
    slug        text    NOT NULL,

    -- Page title and H2 heading, stored rather than recomputed because the
    -- `sources` SSE event sends both to the client (contract 4.4) and the
    -- retriever must not have to re-read the markdown to answer.
    title       text    NOT NULL,
    heading     text    NOT NULL,

    -- Position within the page, so a long section split across several chunks
    -- can be reassembled and ordered without guessing.
    chunk_index integer NOT NULL,

    -- The chunk text, prefixed with "{title} - {heading}" by the chunker so it
    -- still makes sense when retrieved on its own.
    content     text    NOT NULL,

    -- 768 dimensions, fixed here. gemini-embedding-001 is asked for exactly
    -- this size via outputDimensionality. Changing EMBEDDING_DIMENSIONS without
    -- a new migration cannot work, so the application refuses to start when the
    -- configured size and this column disagree.
    embedding   vector(768) NOT NULL,

    -- One row per chunk of a page. Lets re-indexing upsert rather than needing
    -- a delete-then-insert to stay correct under a retry.
    UNIQUE (slug, chunk_index)
);

-- Serves: the similarity search behind every uncached chat answer.
--
-- Cosine, matching how the vectors are compared in ChunkRetriever. It has to be
-- cosine rather than inner product: gemini-embedding-001 normalises its output
-- at the full 3072 dimensions but not when truncated to 768 (measured norm
-- 0.59), so inner product would rank by vector length as much as by meaning.
CREATE INDEX vector_store_embedding_idx
    ON vector_store USING hnsw (embedding vector_cosine_ops);

-- Serves: replacing one page's chunks when its content hash changes, and the
-- report-boost lookup that pulls a named page's first chunk in.
CREATE INDEX vector_store_slug_idx ON vector_store (slug);

-- What has been embedded, so a restart costs no quota.
--
-- The hash alone is not enough. Vectors from two different models, or two
-- different sizes, are not comparable with each other, so a change to either
-- one has to re-embed every page rather than only the edited ones - which means
-- both have to be stored next to the hash to be compared against.
CREATE TABLE content_index (
    slug            text    PRIMARY KEY,

    -- SHA-256 of the page file, hex encoded.
    content_hash    text    NOT NULL,

    embedding_model text    NOT NULL,
    dimensions      integer NOT NULL,
    chunk_count     integer NOT NULL,
    indexed_at      timestamptz NOT NULL
);

-- Five counters for chat (LLD 8). Counts only: no report id, no question, no
-- answer, no slug. They answer "how is chat behaving in aggregate" - how often
-- the cache pays off, how often the model declines, and how often the number
-- guard had to stop an answer - which is the only question worth asking without
-- looking at content that is deliberately never stored (CLAUDE.md rules 3, 9
-- and 19).
--
-- NOT NULL DEFAULT 0 so StatsRecorder's existing upsert keeps working unchanged
-- on rows written before these columns existed. The admin endpoint does not
-- expose them yet: that is a contract change, and phase 7d makes it.
ALTER TABLE daily_stats
    ADD COLUMN chat_answers        integer NOT NULL DEFAULT 0,
    ADD COLUMN chat_cache_hits     integer NOT NULL DEFAULT 0,
    ADD COLUMN chat_declined       integer NOT NULL DEFAULT 0,
    ADD COLUMN chat_not_in_context integer NOT NULL DEFAULT 0,
    ADD COLUMN chat_number_stops   integer NOT NULL DEFAULT 0;

COMMENT ON TABLE vector_store IS
    'Curated biomarker page chunks and their embeddings. Reference content only - never report data, never anything a user typed. Synced from client/content/biomarkers/ by scripts/sync-biomarker-content.sh.';

COMMENT ON TABLE content_index IS
    'Per-page record of what has been embedded, so an unchanged page costs no embedding quota on restart.';
