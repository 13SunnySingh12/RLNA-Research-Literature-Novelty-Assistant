-- Which indexing strategy a paper's chunks were built with.
--
-- Chunk size is clamped to the embedding model's real input window, so chunks
-- created before that clamp are larger than the model reads and their back half
-- never reached the vector that retrieves them. Nothing recorded which strategy
-- produced a given paper's chunks, so there was no way to find those papers
-- short of inspecting token counts and guessing.
--
-- 0 means "indexed before this column existed, strategy unknown". Papers are
-- stamped with the current version when indexing completes, and anything below
-- the current version is a re-index candidate.
ALTER TABLE papers
    ADD COLUMN index_version INTEGER NOT NULL DEFAULT 0;

CREATE INDEX idx_papers_index_version
    ON papers (user_id, index_version)
    WHERE processing_status = 'completed';
