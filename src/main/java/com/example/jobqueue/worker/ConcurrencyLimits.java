package com.example.jobqueue.worker;

import java.util.Collections;
import java.util.Map;

/** Per-job-type concurrency limits passed into the claim query. */
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

    public int limitFor(String type) {
        return limits.getOrDefault(type, -1);
    }

    /** Formats limits as JSONB string, e.g. {"SEND_EMAIL":3,"SLOW":1}. */
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
