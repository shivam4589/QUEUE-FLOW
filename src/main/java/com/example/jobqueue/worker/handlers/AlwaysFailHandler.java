package com.example.jobqueue.worker.handlers;

import com.example.jobqueue.domain.Job;
import com.example.jobqueue.worker.JobHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.stereotype.Component;

/** Demo handler that always fails: used to exercise failure handling (retries arrive in phase 3). */
@Component
public class AlwaysFailHandler implements JobHandler {

    @Override
    public String type() {
        return "ALWAYS_FAIL";
    }

    @Override
    public JsonNode handle(Job job) {
        throw new IllegalStateException("boom: ALWAYS_FAIL jobs always fail");
    }

    @Override
    public JsonNode errorDetail(Job job, Exception lastException) {
        return JsonNodeFactory.instance.objectNode()
                .put("reason", lastException.getMessage())
                .put("attempts", job.attempts());
    }
}
