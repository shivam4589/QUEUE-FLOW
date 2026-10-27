package com.example.jobqueue.worker.handlers;

import com.example.jobqueue.domain.Job;
import com.example.jobqueue.worker.ResultJobHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Demo handler that takes a while ({"seconds": N}, default 10): handy for killing or cancelling a job mid-run. */
@Component
public class SlowHandler implements ResultJobHandler {

    private static final Logger log = LoggerFactory.getLogger(SlowHandler.class);

    @Override
    public String type() {
        return "SLOW";
    }

    @Override
    public JsonNode run(Job job) throws Exception {
        int seconds = job.payload().path("seconds").asInt(10);
        log.info("Slow job {} started, will take {}s", job.id(), seconds);
        Thread.sleep(seconds * 1000L);
        return JsonNodeFactory.instance.objectNode().put("sleptSeconds", seconds);
    }
}
