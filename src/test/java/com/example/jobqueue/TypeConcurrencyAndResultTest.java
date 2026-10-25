package com.example.jobqueue;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.jobqueue.domain.Job;
import com.example.jobqueue.domain.JobStatus;
import com.example.jobqueue.repo.JobRepository;
import com.example.jobqueue.worker.ConcurrencyLimits;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
class TypeConcurrencyAndResultTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    JobRepository repository;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ObjectMapper mapper;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM jobs").update();
        jdbc.sql("DELETE FROM api_keys").update();
    }

    @Test
    void jobResultIsPersistedOnSuccess() {
        Job job = repository.insert(UUID.randomUUID(), "NOOP", mapper.createObjectNode(), 5, 0, 3);
        repository.claimNext("w1");

        ObjectNode result = mapper.createObjectNode().put("itemsProcessed", 42).put("status", "OK");
        int updated = repository.markSucceeded(job.id(), "w1", result);
        assertThat(updated).isEqualTo(1);

        Job fetched = repository.findById(job.id()).orElseThrow();
        assertThat(fetched.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(fetched.result()).isNotNull();
        assertThat(fetched.result().path("itemsProcessed").asInt()).isEqualTo(42);
        assertThat(fetched.result().path("status").asText()).isEqualTo("OK");
    }

    @Test
    void errorDetailIsPersistedOnDead() {
        Job job = repository.insert(UUID.randomUUID(), "ALWAYS_FAIL", mapper.createObjectNode(), 5, 0, 1);
        repository.claimNext("w1");

        ObjectNode errorDetail = mapper.createObjectNode().put("errorCode", "ERR_FATAL").put("code", 500);
        int updated = repository.markDead(job.id(), "w1", "Fatal boom", errorDetail);
        assertThat(updated).isEqualTo(1);

        Job fetched = repository.findById(job.id()).orElseThrow();
        assertThat(fetched.status()).isEqualTo(JobStatus.DEAD);
        assertThat(fetched.lastError()).isEqualTo("Fatal boom");
        assertThat(fetched.errorDetail()).isNotNull();
        assertThat(fetched.errorDetail().path("errorCode").asText()).isEqualTo("ERR_FATAL");
    }

    @Test
    void perJobTypeConcurrencyLimitInClaimNext() {
        ConcurrencyLimits limits = new ConcurrencyLimits(Map.of("SLOW", 2));

        Job slow1 = repository.insert(UUID.randomUUID(), "SLOW", mapper.createObjectNode(), 5, 0, 3);
        Job slow2 = repository.insert(UUID.randomUUID(), "SLOW", mapper.createObjectNode(), 5, 0, 3);
        Job slow3 = repository.insert(UUID.randomUUID(), "SLOW", mapper.createObjectNode(), 5, 0, 3);
        Job noop1 = repository.insert(UUID.randomUUID(), "NOOP", mapper.createObjectNode(), 1, 0, 3); // lower priority

        // Worker 1 claims slow1
        Optional<Job> claim1 = repository.claimNext("w1", limits);
        assertThat(claim1).isPresent();
        assertThat(claim1.get().type()).isEqualTo("SLOW");

        // Worker 2 claims slow2 (now 2 SLOW jobs are RUNNING)
        Optional<Job> claim2 = repository.claimNext("w2", limits);
        assertThat(claim2).isPresent();
        assertThat(claim2.get().type()).isEqualTo("SLOW");

        // Worker 3 attempts to claim: SLOW limit of 2 is saturated, so it skips slow3 and claims noop1
        Optional<Job> claim3 = repository.claimNext("w3", limits);
        assertThat(claim3).isPresent();
        assertThat(claim3.get().id()).isEqualTo(noop1.id());
        assertThat(claim3.get().type()).isEqualTo("NOOP");

        // Worker 4 attempts to claim: no other runnable jobs
        Optional<Job> claim4 = repository.claimNext("w4", limits);
        assertThat(claim4).isEmpty();

        // Worker 1 finishes slow1
        repository.markSucceeded(claim1.get().id(), "w1", null);

        // Now concurrency for SLOW is 1/2, so slow3 can be claimed!
        Optional<Job> claim5 = repository.claimNext("w5", limits);
        assertThat(claim5).isPresent();
        assertThat(claim5.get().id()).isEqualTo(slow3.id());
    }

    @Test
    void clientIdIsStoredAndFilterable() {
        Job j1 = repository.insert(UUID.randomUUID(), "T1", mapper.createObjectNode(), 5, 0, 3, "client-A");
        Job j2 = repository.insert(UUID.randomUUID(), "T1", mapper.createObjectNode(), 5, 0, 3, "client-B");
        Job j3 = repository.insert(UUID.randomUUID(), "T1", mapper.createObjectNode(), 5, 0, 3, null);

        assertThat(repository.findById(j1.id()).orElseThrow().clientId()).isEqualTo("client-A");
        assertThat(repository.findById(j2.id()).orElseThrow().clientId()).isEqualTo("client-B");
        assertThat(repository.findById(j3.id()).orElseThrow().clientId()).isNull();

        List<Job> clientAJobs = repository.list(null, null, "client-A", 10, 0);
        assertThat(clientAJobs).hasSize(1);
        assertThat(clientAJobs.get(0).id()).isEqualTo(j1.id());

        long countA = repository.count(null, null, "client-A");
        assertThat(countA).isEqualTo(1);

        long countAll = repository.count(null, null, null);
        assertThat(countAll).isEqualTo(3);
    }
}
