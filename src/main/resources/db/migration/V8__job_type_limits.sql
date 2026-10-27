-- At most max_concurrent jobs of a type may be RUNNING at the same time, across all workers.
CREATE TABLE job_type_limits (
    job_type       VARCHAR(100) PRIMARY KEY,
    max_concurrent INT          NOT NULL CHECK (max_concurrent BETWEEN 1 AND 1000),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- counting the running jobs of one type must be cheap
CREATE INDEX idx_jobs_running_type ON jobs (type) WHERE status = 'RUNNING';
