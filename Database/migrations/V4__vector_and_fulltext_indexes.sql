-- ---------------------------------------------------------------------------
-- Vector index (Section 19.3). Cosine distance to match the `<=>` operator
-- used by every retrieval query. HNSW builds incrementally, so creating it on
-- an empty table is safe and avoids a later rebuild against a live corpus.
-- ---------------------------------------------------------------------------
CREATE INDEX idx_chunks_embedding
    ON paper_chunks USING hnsw (embedding vector_cosine_ops);

-- ---------------------------------------------------------------------------
-- Keyword search (GET /api/search/keyword). A stored generated column keeps
-- the lexemes in sync with `content` automatically -- no trigger to maintain
-- and no way for the index to drift from the text it describes.
-- ---------------------------------------------------------------------------
ALTER TABLE paper_chunks
    ADD COLUMN content_tsv tsvector
    GENERATED ALWAYS AS (to_tsvector('english', content)) STORED;

CREATE INDEX idx_chunks_content_tsv ON paper_chunks USING gin(content_tsv);
