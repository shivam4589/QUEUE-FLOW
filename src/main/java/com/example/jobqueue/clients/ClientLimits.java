package com.example.jobqueue.clients;

/** A client's limits; null means unlimited. */
public record ClientLimits(Integer ratePerMinute, Integer dailyQuota) {

    public static final ClientLimits NONE = new ClientLimits(null, null);

    public boolean any() {
        return ratePerMinute != null || dailyQuota != null;
    }
}
