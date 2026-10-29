package com.example.jobqueue.clients;

import java.time.Instant;
import java.util.UUID;

/**
 * An API client. The secret key itself is never stored, only its hash; keyPrefix helps recognise a key.
 * ratePerMinute / dailyQuota are null when unlimited; usedToday counts jobs submitted since 00:00 UTC.
 */
public record ApiClient(
        UUID id,
        String name,
        String keyPrefix,
        boolean enabled,
        Instant createdAt,
        Instant lastUsedAt,
        Integer ratePerMinute,
        Integer dailyQuota,
        long jobs,
        long usedToday) {
}
