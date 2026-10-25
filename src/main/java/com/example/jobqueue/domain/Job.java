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
        /** Structured JSON result stored by the handler on success. Null if none was returned. */
        JsonNode result,
        /** Structured JSON error detail stored by the handler when the job goes DEAD. */
        JsonNode errorDetail,
        /** Client that submitted this job via an API key. Null for un-keyed submissions. */
        String clientId) {
}
