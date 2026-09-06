-- pgvector powers all similarity search (Section 19). Installed first so that
-- V2 can declare VECTOR columns.
CREATE EXTENSION IF NOT EXISTS vector;

-- Trigram index support for the fuzzy title matching used by duplicate detection.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
