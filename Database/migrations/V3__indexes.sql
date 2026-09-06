-- Ownership / filtering paths (Section 18.2)
CREATE INDEX idx_projects_user       ON projects(user_id);
CREATE INDEX idx_papers_user         ON papers(user_id);
CREATE INDEX idx_papers_project      ON papers(project_id);
CREATE INDEX idx_papers_status       ON papers(processing_status);
CREATE INDEX idx_papers_year         ON papers(publication_year);
CREATE INDEX idx_papers_tags         ON papers USING gin(tags);
CREATE INDEX idx_sections_paper      ON paper_sections(paper_id);
CREATE INDEX idx_chunks_paper        ON paper_chunks(paper_id);
CREATE INDEX idx_chunks_section      ON paper_chunks(section_type);
CREATE INDEX idx_concepts_paper      ON concepts(paper_id);
CREATE INDEX idx_concepts_concept    ON concepts(concept);
CREATE INDEX idx_analysis_cache      ON analysis_results(cache_key);
CREATE INDEX idx_analysis_user_type  ON analysis_results(user_id, analysis_type, created_at DESC);
CREATE INDEX idx_jobs_paper          ON processing_jobs(paper_id);

-- Startup recovery scans for jobs left mid-flight by a restart; only the
-- unfinished ones matter, so the index is partial and stays tiny.
CREATE INDEX idx_jobs_unfinished ON processing_jobs(status, created_at)
    WHERE status IN ('queued','running');

-- Exact-duplicate guard: the same bytes cannot enter one library twice.
CREATE UNIQUE INDEX idx_papers_user_content_hash
    ON papers(user_id, content_hash) WHERE content_hash IS NOT NULL;

-- Fuzzy title matching for the duplicate warning shown on upload.
CREATE INDEX idx_papers_title_trgm ON papers USING gin(title gin_trgm_ops);
