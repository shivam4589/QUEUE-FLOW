-- Per-job-type concurrency limits are configured in application.yml (jobqueue.worker.type-concurrency).
-- This table is a convenient place to record the limits for observability / audit; the worker reads
-- its limits from properties, not from this table, so changes take effect on next restart.
CREATE TABLE job_type_config (
    type        VARCHAR(100) PRIMARY KEY,
    max_running INT          NOT NULL CHECK (max_running >= 1),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
