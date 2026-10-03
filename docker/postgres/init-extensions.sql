-- Extensions for the local development database.
--
-- Only `vector` is needed: it backs the biomarker-page embeddings that chat RAG
-- retrieves from (docs/plan.md section 7, `vector_store`).
--
-- Primary keys are generated in Java as random UUIDs, so no uuid extension is
-- required. Flyway's V1__baseline.sql repeats this statement so a database
-- created without this init script still ends up correct.
CREATE EXTENSION IF NOT EXISTS vector;
