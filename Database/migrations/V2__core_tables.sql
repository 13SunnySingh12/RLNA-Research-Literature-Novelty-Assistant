-- ---------------------------------------------------------------------------
-- Application users.
--
-- Identity is owned by Neon Auth (Better Auth), which keeps its own tables in
-- the `neon_auth` schema. This table is the *application's* projection of a
-- user: rows are provisioned on first authenticated request, keyed by the
-- verified `sub` claim. Keeping them separate means application data never
-- depends on the auth provider's schema.
-- ---------------------------------------------------------------------------
CREATE TABLE users (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email                 VARCHAR(255) UNIQUE NOT NULL,
    name                  VARCHAR(255),
    auth_provider         VARCHAR(50),
    auth_provider_user_id VARCHAR(255) NOT NULL UNIQUE,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE projects (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name           VARCHAR(255) NOT NULL,
    description    TEXT,
    research_topic VARCHAR(500),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE papers (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    project_id        UUID REFERENCES projects(id) ON DELETE SET NULL,
    title             VARCHAR(1000),
    authors           TEXT[],
    abstract          TEXT,
    publication_year  INTEGER,
    venue             VARCHAR(500),
    doi               VARCHAR(255),
    external_id       VARCHAR(255),
    r2_object_key     VARCHAR(500),
    file_size         BIGINT,
    -- SHA-256 of the uploaded bytes. Cheap exact-duplicate guard (FR-29) and
    -- what makes a retried upload idempotent instead of creating a second row.
    content_hash      VARCHAR(64),
    processing_status VARCHAR(50) NOT NULL DEFAULT 'queued',
    processing_error  TEXT,
    summary           JSONB,
    reading_status    VARCHAR(20) NOT NULL DEFAULT 'to_read',
    tags              TEXT[] NOT NULL DEFAULT '{}',
    notes             TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 'metadata_only' covers records imported from an academic API that have
    -- no PDF yet: they are legitimately un-indexed rather than stuck queued.
    CONSTRAINT papers_processing_status_chk
        CHECK (processing_status IN ('queued','processing','completed','failed','metadata_only')),
    CONSTRAINT papers_reading_status_chk
        CHECK (reading_status IN ('to_read','reading','read'))
);

CREATE TABLE paper_sections (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    paper_id     UUID NOT NULL REFERENCES papers(id) ON DELETE CASCADE,
    section_type VARCHAR(50) NOT NULL,
    heading      VARCHAR(500),
    content      TEXT,
    order_index  INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE paper_chunks (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    paper_id        UUID NOT NULL REFERENCES papers(id) ON DELETE CASCADE,
    section_id      UUID REFERENCES paper_sections(id) ON DELETE CASCADE,
    section_type    VARCHAR(50),            -- denormalized for weighting without a join
    chunk_index     INTEGER NOT NULL,
    content         TEXT NOT NULL,
    token_count     INTEGER,
    embedding       VECTOR(384),
    embedding_model VARCHAR(100),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Re-processing a paper must not be able to double-insert its chunks.
    CONSTRAINT paper_chunks_paper_index_uq UNIQUE (paper_id, chunk_index)
);

CREATE TABLE concepts (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    paper_id     UUID NOT NULL REFERENCES papers(id) ON DELETE CASCADE,
    concept      VARCHAR(255) NOT NULL,
    concept_type VARCHAR(50),
    confidence   REAL,
    CONSTRAINT concepts_paper_concept_uq UNIQUE (paper_id, concept, concept_type)
);

CREATE TABLE analysis_results (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    project_id    UUID REFERENCES projects(id) ON DELETE CASCADE,
    paper_id      UUID REFERENCES papers(id) ON DELETE CASCADE,
    analysis_type VARCHAR(50) NOT NULL,
    input_query   TEXT,
    result        JSONB NOT NULL,
    evidence_refs JSONB,
    provider_used VARCHAR(50),
    model_id      VARCHAR(100),
    confidence    VARCHAR(20),
    cache_key     VARCHAR(64) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- The cache key *is* the identity of a result (Section 25.2). Making it
    -- unique per user turns the cache lookup into an atomic upsert and stops
    -- two concurrent identical requests from both paying for the LLM call.
    CONSTRAINT analysis_results_user_cache_uq UNIQUE (user_id, cache_key)
);

CREATE TABLE processing_jobs (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    paper_id      UUID NOT NULL REFERENCES papers(id) ON DELETE CASCADE,
    job_type      VARCHAR(50) NOT NULL,
    status        VARCHAR(50) NOT NULL DEFAULT 'queued',
    current_step  VARCHAR(100),
    progress      INTEGER NOT NULL DEFAULT 0,
    error_message TEXT,
    attempts      INTEGER NOT NULL DEFAULT 0,
    started_at    TIMESTAMPTZ,
    completed_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT processing_jobs_status_chk
        CHECK (status IN ('queued','running','completed','failed')),
    CONSTRAINT processing_jobs_progress_chk CHECK (progress BETWEEN 0 AND 100)
);
