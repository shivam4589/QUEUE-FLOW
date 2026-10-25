-- Per-client API keys. Raw keys are never stored; only their SHA-256 hash is kept.
-- The plaintext key is returned once at creation time and never again.
CREATE TABLE api_keys (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id    VARCHAR(100) NOT NULL,
    key_hash     CHAR(64)     NOT NULL UNIQUE,   -- SHA-256(raw_key) as lowercase hex
    description  TEXT,
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ
);

-- Track which client submitted each job (NULL for jobs submitted without a key).
ALTER TABLE jobs ADD COLUMN client_id VARCHAR(100);
CREATE INDEX idx_jobs_client ON jobs (client_id) WHERE client_id IS NOT NULL;
