package com.example.jobqueue.limits;

public class LimitNotFoundException extends RuntimeException {
    public LimitNotFoundException(String type) {
        super("No concurrency limit is set for job type " + type);
    }
}
