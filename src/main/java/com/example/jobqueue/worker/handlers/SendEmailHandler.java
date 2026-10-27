package com.example.jobqueue.worker.handlers;

import com.example.jobqueue.domain.Job;
import com.example.jobqueue.worker.ResultJobHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Demo handler: pretends to send an email (no real email is sent) and returns a delivery receipt as its result. */
@Component
public class SendEmailHandler implements ResultJobHandler {

    private static final Logger log = LoggerFactory.getLogger(SendEmailHandler.class);

    @Override
    public String type() {
        return "SEND_EMAIL";
    }

    @Override
    public JsonNode run(Job job) throws Exception {
        String to = job.payload().path("to").asText("unknown");
        log.info("Sending email to {} (job {})", to, job.id());
        Thread.sleep(300); // simulate work
        return JsonNodeFactory.instance.objectNode()
                .put("delivered", true)
                .put("to", to)
                .put("note", "simulated: no real email is sent");
    }
}
