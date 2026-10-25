package com.example.jobqueue.worker;

import java.util.Collections;
import java.util.Map;

/**
 * Encapsulates the per-job-type concurrency limits read from
 * {@code jobqueue.worker.type-concurrency} and serialises them for the claim query.
 *
 * <p>A type not present in the map has no limit (unlimited concurrency).
 */
public final class ConcurrencyLimits {

    private final Map<String, Integer> limits;

    public ConcurrencyLimits(Map<String, Integer> limits) {
        this.limits = limits == null ? Collections.emptyMap() : Map.copyOf(limits);
    }

    public boolean isEmpty() {
        return limits.isEmpty();
    }

    public Map<String, Integer> asMap() {
        return limits;
    }

    /**
     * Returns the limit for a type, or -1 if there is no limit configured.
     */
    public int limitFor(String type) {
        return limits.getOrDefault(type, -1);
    }

    /**
     * Serialises limits as a simple JSON object string suitable for passing to PostgreSQL as a
     * JSONB parameter: {@code {"SEND_EMAIL":3,"SLOW":1}}.
     * Returns {@code "{}"} when no limits are configured.
     */
    public String toJsonb() {
        if (limits.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        limits.forEach((type, max) -> {
            if (sb.length() > 1) sb.append(",");
            sb.append("\"").append(type.replace("\"", "\\\"")).append("\":").append(max);
        });
        return sb.append("}").toString();
    }
}
