package com.example.jobqueue;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.jobqueue.domain.Job;
import com.example.jobqueue.domain.JobAttempt;
import com.example.jobqueue.domain.JobStatus;
import com.example.jobqueue.repo.JobRepository;
import com.example.jobqueue.worker.JobHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = {
        "jobqueue.worker.enabled=true",
        "jobqueue.worker.concurrency=4",
        "jobqueue.worker.poll-interval-ms=50",
        "jobqueue.worker.backoff-base-seconds=1",
        "jobqueue.worker.backoff-max-seconds=2"
})
class WorkerEndToEndTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @TestConfiguration
    static class Config {
        @Bean
        CountingHandler countingHandler() {
            return new CountingHandler();
        }

        @Bean
        FlakyHandler flakyHandler() {
            return new FlakyHandler();
        }
    }

    /** Records how many times each job id was executed. */
    static class CountingHandler implements JobHandler {
        final ConcurrentHashMap<UUID, AtomicInteger> runs = new ConcurrentHashMap<>();

        @Override
        public String type() {
            return "COUNT";
        }

        @Override
        public void handle(Job job) {
            runs.computeIfAbsent(job.id(), k -> new AtomicInteger()).incrementAndGet();
        }
    }

    /** Fails its first two executions of every job, then succeeds. */
    static class FlakyHandler implements JobHandler {
        final ConcurrentHashMap<UUID, AtomicInteger> runs = new ConcurrentHashMap<>();

        @Override
        public String type() {
            return "FLAKY";
        }

        @Override
        public void handle(Job job) {
            int run = runs.computeIfAbsent(job.id(), k -> new AtomicInteger()).incrementAndGet();
            if (run <= 2) {
                throw new IllegalStateException("flaky failure #" + run);
            }
        }
    }

    @Autowired
    JobRepository repository;

    @Autowired
    CountingHandler counting;

    @Autowired
    FlakyHandler flaky;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MeterRegistry meters;

    @BeforeEach
    void reset() {
        jdbc.sql("DELETE FROM jobs").update();
        counting.runs.clear();
        flaky.runs.clear();
    }

    private void awaitStatus(UUID id, JobStatus expected) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(
                () -> repository.findById(id).map(j -> j.status() == expected).orElse(false));
    }

    @Test
    void workersExecuteEveryJobExactlyOnce() {
        int jobCount = 100;
        ObjectNode payload = mapper.createObjectNode();
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < jobCount; i++) {
            ids.add(repository.insert(UUID.randomUUID(), "COUNT", payload, 5, 0, 5).id());
        }

        Awaitility.await().atMost(Duration.ofSeconds(30)).until(
                () -> repository.list(JobStatus.SUCCEEDED, 200).size() == jobCount);

        assertThat(counting.runs.keySet()).isEqualTo(ids);
        assertThat(counting.runs.values()).allMatch(count -> count.get() == 1);
    }

    @Test
    void workerRecordsDurationMetrics() {
        ObjectNode payload = mapper.createObjectNode();
        for (int i = 0; i < 10; i++) {
            repository.insert(UUID.randomUUID(), "COUNT", payload, 5, 0, 5);
        }
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(
                () -> repository.list(JobStatus.SUCCEEDED, 200).size() == 10);

        long recorded = meters.find("jobqueue.job.duration").tag("outcome", "success")
                .timers().stream().mapToLong(Timer::count).sum();
        assertThat(recorded).isGreaterThanOrEqualTo(10);
    }

    @Test
    void flakyJobIsRetriedUntilItSucceeds() {
        UUID id = repository.insert(UUID.randomUUID(), "FLAKY", mapper.createObjectNode(), 5, 0, 5).id();

        awaitStatus(id, JobStatus.SUCCEEDED);

        Job job = repository.findById(id).orElseThrow();
        assertThat(job.attempts()).isEqualTo(3);
        List<JobAttempt> attempts = repository.attemptsFor(id);
        assertThat(attempts).extracting(JobAttempt::success).containsExactly(false, false, true);
        assertThat(attempts.get(0).error()).contains("flaky failure #1");
    }

    @Test
    void jobThatKeepsFailingEndsUpDead() {
        UUID id = repository.insert(UUID.randomUUID(), "ALWAYS_FAIL", mapper.createObjectNode(), 5, 0, 3).id();

        awaitStatus(id, JobStatus.DEAD);

        Job job = repository.findById(id).orElseThrow();
        assertThat(job.attempts()).isEqualTo(3);
        assertThat(job.lastError()).contains("boom");
        assertThat(repository.attemptsFor(id)).hasSize(3).allMatch(a -> Boolean.FALSE.equals(a.success()));
    }
}
