package com.example.jobqueue.worker;

import com.example.jobqueue.domain.Job;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Implement this and register it as a Spring bean to teach the queue a new job type.
 * Return a JsonNode from handle() to store structured results on success, or null.
 * Throw any exception to signal failure.
 */
public interface JobHandler {

    /** The job type this handler processes, e.g. "SEND_EMAIL". */
    String type();

    JsonNode handle(Job job) throws Exception;

    /** Optional structured error metadata saved if the job exhausts all attempts. */
    default JsonNode errorDetail(Job job, Exception lastException) {
        return null;
    }
}
