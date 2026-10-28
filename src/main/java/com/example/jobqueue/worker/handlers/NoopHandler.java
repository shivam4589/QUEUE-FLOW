package com.example.jobqueue.worker.handlers;

import com.example.jobqueue.domain.Job;
import com.example.jobqueue.worker.JobHandler;
import org.springframework.stereotype.Component;

/** Demo handler: does nothing and succeeds immediately. Useful for load and throughput tests. */
@Component
public class NoopHandler implements JobHandler {

    @Override
    public String type() {
        return "NOOP";
    }

    @Override
    public void handle(Job job) {
        // intentionally empty
    }
}
