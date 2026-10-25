package com.example.jobqueue.worker.handlers;

import com.example.jobqueue.domain.Job;
import com.example.jobqueue.worker.JobHandler;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/** Does nothing. Used by the load test to measure the queue's own overhead (claim + bookkeeping). */
@Component
public class NoopHandler implements JobHandler {

    @Override
    public String type() {
        return "NOOP";
    }

    @Override
    public JsonNode handle(Job job) {
        // intentionally empty
        return null;
    }
}
