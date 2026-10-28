-- API clients: each has a secret key (only its SHA-256 hash is stored).
CREATE TABLE api_clients (
    id           UUID         PRIMARY KEY,
    name         VARCHAR(100) NOT NULL UNIQUE,
    key_prefix   VARCHAR(16)  NOT NULL,
    key_hash     CHAR(64)     NOT NULL UNIQUE,
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ
);

-- Preserve the older API-key client identifiers while moving job ownership to UUID clients.
ALTER TABLE jobs RENAME COLUMN client_id TO legacy_client_id;
ALTER INDEX idx_jobs_client RENAME TO idx_jobs_legacy_client;
ALTER TABLE jobs ADD COLUMN client_id UUID REFERENCES api_clients(id);
ALTER TABLE jobs ADD COLUMN idempotency_scope UUID
    GENERATED ALWAYS AS (COALESCE(client_id, '00000000-0000-0000-0000-000000000000'::uuid)) STORED;

DROP INDEX uq_jobs_idempotency_key;
CREATE UNIQUE INDEX uq_jobs_idempotency_scope_key
    ON jobs (idempotency_scope, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX idx_jobs_client ON jobs (client_id) WHERE client_id IS NOT NULL;
