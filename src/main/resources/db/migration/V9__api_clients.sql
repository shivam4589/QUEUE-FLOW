-- API clients: each has a secret key (only its SHA-256 hash is stored) that lets it submit jobs.
CREATE TABLE api_clients (
    id           UUID         PRIMARY KEY,
    name         VARCHAR(100) NOT NULL UNIQUE,
    key_prefix   VARCHAR(16)  NOT NULL,            -- first characters of the key, so a key can be recognised
    key_hash     CHAR(64)     NOT NULL UNIQUE,     -- SHA-256 (hex) of the full key
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ
);

ALTER TABLE jobs ADD COLUMN client_id UUID REFERENCES api_clients(id);

-- Idempotency keys now belong to a client: the same key from two clients is two different keys.
-- Anonymous submissions (no client) share the all-zero scope, exactly as before.
ALTER TABLE jobs ADD COLUMN idempotency_scope UUID
    GENERATED ALWAYS AS (COALESCE(client_id, '00000000-0000-0000-0000-000000000000'::uuid)) STORED;
DROP INDEX uq_jobs_idempotency_key;
CREATE UNIQUE INDEX uq_jobs_idempotency_scope_key
    ON jobs (idempotency_scope, idempotency_key) WHERE idempotency_key IS NOT NULL;

CREATE INDEX idx_jobs_client ON jobs (client_id) WHERE client_id IS NOT NULL;
