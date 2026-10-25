-- Handlers may return a structured JSON result or an error detail JSON.
-- result       : set when the job SUCCEEDS (handler returned a non-null value)
-- error_detail : set when the job goes DEAD (structured error information from the handler)
ALTER TABLE jobs ADD COLUMN result       JSONB;
ALTER TABLE jobs ADD COLUMN error_detail JSONB;
