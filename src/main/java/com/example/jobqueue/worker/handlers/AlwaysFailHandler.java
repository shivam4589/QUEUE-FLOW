package com.example.jobqueue.worker.handlers;

import com.example.jobqueue.domain.Job;
import com.example.jobqueue.worker.JobHandler;
import org.springframework.stereotype.Component;

/** Demo handler that always fails: used to exercise retries and dead-lettering. */
@Component
public class AlwaysFailHandler implements JobHandler {

    @Override
    public String type() {
        return "ALWAYS_FAIL";
    }

    @Override
    public void handle(Job job) {
        throw new IllegalStateException("boom: ALWAYS_FAIL jobs always fail");
    }
}
