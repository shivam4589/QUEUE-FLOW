-- Optional per-client limits (NULL = unlimited).
ALTER TABLE api_clients ADD COLUMN rate_limit_per_minute INT
    CHECK (rate_limit_per_minute IS NULL OR rate_limit_per_minute BETWEEN 1 AND 100000);
ALTER TABLE api_clients ADD COLUMN daily_quota INT
    CHECK (daily_quota IS NULL OR daily_quota BETWEEN 1 AND 10000000);

-- counting one client's recent submissions must be cheap
CREATE INDEX idx_jobs_client_created ON jobs (client_id, created_at) WHERE client_id IS NOT NULL;
