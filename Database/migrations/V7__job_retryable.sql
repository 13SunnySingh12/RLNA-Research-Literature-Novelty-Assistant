-- Whether retrying a failed indexing job could plausibly succeed.
--
-- The status endpoint previously derived this from status = 'failed', so every
-- failure was reported as retryable. A password-protected PDF or a scan with no
-- text layer will fail identically on every attempt, and telling the reader to
-- retry is advice that cannot work. The worker already distinguishes the two
-- cases; this column persists that judgement.
--
-- Existing rows default to true, which matches the behaviour they were created
-- under and keeps the column non-null.
ALTER TABLE processing_jobs
    ADD COLUMN retryable BOOLEAN NOT NULL DEFAULT true;
