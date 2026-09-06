-- Semantic duplicate detection (Section 9.6). The check runs once the abstract
-- has been embedded, so the result needs somewhere durable to live: a warning
-- discovered during background processing has no request left to return it on.
ALTER TABLE papers
    ADD COLUMN duplicate_of UUID REFERENCES papers(id) ON DELETE SET NULL,
    ADD COLUMN duplicate_similarity REAL;

CREATE INDEX idx_papers_duplicate_of ON papers(duplicate_of) WHERE duplicate_of IS NOT NULL;
