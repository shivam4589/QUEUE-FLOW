package com.example.jobqueue;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.jobqueue.domain.Job;
import com.example.jobqueue.domain.JobStatus;
import com.example.jobqueue.repo.JobRepository;
import com.example.jobqueue.worker.JobHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The lock timeout is 3 seconds but the job takes 6. Without the heartbeat the reaper would reclaim the job after
 * about 3 seconds and it would run twice; with the heartbeat it runs exactly once.
 */
@Testcontainers
@SpringBootTest(properties = {
        "jobqueue.worker.enabled=true",
        "jobqueue.worker.concurrency=2",
        "jobqueue.worker.poll-interval-ms=100",
        "jobqueue.worker.lock-timeout-seconds=3",
        "jobqueue.worker.reaper-interval-ms=500",
        "jobqueue.worker.heartbeat-interval-ms=500"
})
class LockHeartbeatEndToEndTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @TestConfiguration
    static class Config {
        @Bean
        LongRunningHandler longRunningHandler() {
            return new LongRunningHandler();
        }
    }

    static class LongRunningHandler implements JobHandler {
        final AtomicInteger runs = new AtomicInteger();

        @Override
        public String type() {
            return "LONG";
        }

        @Override
        public void handle(Job job) throws Exception {
            runs.incrementAndGet();
            Thread.sleep(6000);
        }
    }

    @Autowired
    JobRepository repository;

    @Autowired
    LongRunningHandler handler;

    @Autowired
    ObjectMapper mapper;

    @Test
    void aJobLongerThanTheLockTimeoutIsNotReclaimed() {
        UUID id = repository.insert(UUID.randomUUID(), "LONG", mapper.createObjectNode(), 5, 0, 5).id();

        Awaitility.await().atMost(Duration.ofSeconds(40)).until(
                () -> repository.findById(id).map(job -> job.status() == JobStatus.SUCCEEDED).orElse(false));

        assertThat(handler.runs.get()).isEqualTo(1);
        assertThat(repository.findById(id).orElseThrow().attempts()).isEqualTo(1);
        assertThat(repository.attemptsFor(id)).hasSize(1);
    }
}
