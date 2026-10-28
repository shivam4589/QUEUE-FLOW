package com.example.jobqueue.worker;

import com.example.jobqueue.domain.Job;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A handler that returns a JSON result stored with the job on success.
 * Implement run() and leave handle() alone; WorkerLoop calls run() directly
 * via instanceof, so handle() is only a fallback.
 */
public interface ResultJobHandler extends JobHandler {

    /** Run the job and return a result (null = no result to store). */
    JsonNode run(Job job) throws Exception;

    @Override
    default void handle(Job job) throws Exception {
        run(job);
    }
}
