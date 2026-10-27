package com.example.jobqueue.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record Job(
        UUID id,
        String type,
        JsonNode payload,
        JobStatus status,
        int priority,
        Instant runAt,
        int attempts,
        int maxAttempts,
        String lastError,
        Instant createdAt,
        Instant updatedAt,
        UUID scheduleId,
        String idempotencyKey,
        boolean cancelRequested,
        JsonNode result) {
}
