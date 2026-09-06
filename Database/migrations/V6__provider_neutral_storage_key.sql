-- Object storage moved to Backblaze B2.
--
-- The column is renamed rather than replaced: it holds an S3 object key, which
-- is the same value whichever S3-compatible provider stores it. Naming a column
-- after a vendor is what made this migration necessary at all, so the new name
-- is provider-neutral and a future move costs no schema change.
--
-- RENAME preserves the existing keys, so papers already indexed keep pointing at
-- their objects.
ALTER TABLE papers RENAME COLUMN r2_object_key TO storage_object_key;
