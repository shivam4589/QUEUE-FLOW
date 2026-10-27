package com.example.jobqueue;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.jobqueue.domain.Job;
import com.example.jobqueue.domain.JobStatus;
import com.example.jobqueue.repo.JobRepository;
import com.example.jobqueue.worker.ResultJobHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Duration;
import java.util.UUID;
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

/** Handlers that return a result get it stored with the job; others store none; huge results are summarised. */
@Testcontainers
@SpringBootTest(properties = {
        "jobqueue.worker.enabled=true",
        "jobqueue.worker.concurrency=2",
        "jobqueue.worker.poll-interval-ms=100"
})
class JobResultsTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @TestConfiguration
    static class Config {
        @Bean
        BigResultHandler bigResultHandler() {
            return new BigResultHandler();
        }
    }

    static class BigResultHandler implements ResultJobHandler {
        @Override
        public String type() {
            return "BIG";
        }

        @Override
        public JsonNode run(Job job) {
            return JsonNodeFactory.instance.objectNode().put("data", "x".repeat(100_000));
        }
    }

    @Autowired
    JobRepository repository;

    @Autowired
    ObjectMapper mapper;

    private Job runToCompletion(String type, JsonNode payload) {
        UUID id = repository.insert(UUID.randomUUID(), type, payload, 5, 0, 5).id();
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(
                () -> repository.findById(id).map(job -> job.status() == JobStatus.SUCCEEDED).orElse(false));
        return repository.findById(id).orElseThrow();
    }

    @Test
    void aResultIsStoredWithTheJob() {
        Job job = runToCompletion("SEND_EMAIL", mapper.createObjectNode().put("to", "a@example.com"));

        assertThat(job.result()).isNotNull();
        assertThat(job.result().get("to").asText()).isEqualTo("a@example.com");
        assertThat(job.result().get("delivered").asBoolean()).isTrue();
    }

    @Test
    void theReportJobReturnsQueueStatistics() {
        Job job = runToCompletion("GENERATE_REPORT", mapper.createObjectNode());

        assertThat(job.result().get("jobsByStatus")).isNotNull();
        // the report job itself was running while the report was built
        assertThat(job.result().get("jobsByStatus").get("RUNNING").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(job.result().get("totalJobs").asLong()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void jobsWithoutAResultHaveNone() {
        Job job = runToCompletion("NOOP", mapper.createObjectNode());

        assertThat(job.result()).isNull();
    }

    @Test
    void anOversizedResultIsReplacedByASummary() {
        Job job = runToCompletion("BIG", mapper.createObjectNode());

        assertThat(job.result().get("truncated").asBoolean()).isTrue();
        assertThat(job.result().get("sizeCharacters").asInt()).isGreaterThan(100_000);
    }
}
