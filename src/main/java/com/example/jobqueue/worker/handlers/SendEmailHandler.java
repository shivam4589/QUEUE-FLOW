package com.example.jobqueue.worker.handlers;

import com.example.jobqueue.domain.Job;
import com.example.jobqueue.worker.JobHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Demo handler: pretends to send an email. Replace with real logic later. */
@Component
public class SendEmailHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(SendEmailHandler.class);

    @Override
    public String type() {
        return "SEND_EMAIL";
    }

    @Override
    public JsonNode handle(Job job) throws Exception {
        String to = job.payload().path("to").asText("unknown");
        log.info("Sending email to {} (job {})", to, job.id());
        Thread.sleep(300); // simulate work
        // Return a simple result showing which address was targeted.
        return JsonNodeFactory.instance.objectNode().put("sentTo", to);
    }
}
