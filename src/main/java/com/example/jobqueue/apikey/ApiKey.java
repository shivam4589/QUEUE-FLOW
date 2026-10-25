package com.example.jobqueue.apikey;

import java.time.Instant;
import java.util.UUID;

/**
 * A per-client API key. The raw key is never stored; only its SHA-256 hash (lowercase hex) is
 * persisted. The plaintext key is surfaced once, at creation time, in {@link CreateApiKeyResponse}.
 */
public record ApiKey(
        UUID id,
        String clientId,
        String keyHash,
        String description,
        boolean enabled,
        Instant createdAt,
        Instant lastUsedAt) {
}
