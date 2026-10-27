package com.example.jobqueue.worker;

import com.example.jobqueue.domain.Job;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A handler that produces a result. The result is stored with the job when it succeeds and is shown by the API and
 * the dashboard. Implement run() instead of handle(); return null if there is nothing to report.
 */
public interface ResultJobHandler extends JobHandler {

    JsonNode run(Job job) throws Exception;

    @Override
    default void handle(Job job) throws Exception {
        run(job);
    }
}
