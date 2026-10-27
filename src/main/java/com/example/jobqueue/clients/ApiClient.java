package com.example.jobqueue.clients;

import java.time.Instant;
import java.util.UUID;

/** An API client. The secret key itself is never stored, only its hash; keyPrefix helps recognise a key. */
public record ApiClient(
        UUID id,
        String name,
        String keyPrefix,
        boolean enabled,
        Instant createdAt,
        Instant lastUsedAt,
        long jobs) {
}
