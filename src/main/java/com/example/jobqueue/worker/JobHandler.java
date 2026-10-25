package com.example.jobqueue.worker;

import com.example.jobqueue.domain.Job;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Implement this and register it as a Spring bean to teach the queue a new job type.
 * Throw any exception to signal failure.
 *
 * <p>The return value of {@link #handle} is stored as the job's {@code result} field when the job
 * succeeds. Return {@code null} (or use the default) if the handler has no structured result to
 * publish. Similarly, override {@link #errorDetail} to attach structured error information when the
 * job goes DEAD.
 */
public interface JobHandler {

    /** The job type this handler processes, e.g. "SEND_EMAIL". */
    String type();

    /**
     * Runs the job. Return a {@link JsonNode} to store as the job result, or {@code null} for none.
     * Throw any exception to signal failure.
     */
    JsonNode handle(Job job) throws Exception;

    /**
     * Called when the job goes DEAD (exhausted all attempts). Override to return structured error
     * metadata that is stored alongside the plain-text {@code lastError}. Default returns null.
     */
    default JsonNode errorDetail(Job job, Exception lastException) {
        return null;
    }
}
