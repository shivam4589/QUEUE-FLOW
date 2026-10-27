-- A handler can return a result; it is stored with the job when the job succeeds.
ALTER TABLE jobs ADD COLUMN result JSONB;
