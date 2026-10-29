package com.example.jobqueue.api;

/** A client went over its rate limit or daily quota. Answered with 429 and a Retry-After header. */
public class QuotaExceededException extends RuntimeException {

    private final long retryAfterSeconds;

    public QuotaExceededException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
