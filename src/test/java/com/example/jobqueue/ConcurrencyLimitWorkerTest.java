package com.example.jobqueue;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.jobqueue.limits.LimitRepository;
import com.example.jobqueue.repo.JobRepository;
import com.example.jobqueue.worker.ClaimService;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * The limit must hold for the real worker threads, not just for ClaimService on its own. With 4 worker threads and a
 * limit of 1, three 2-second jobs of the same type must run one after another; without the limit all three would run
 * at the same time. (This guards against the workers claiming jobs through a path that ignores the limits.)
 */
@Testcontainers
@SpringBootTest(properties = {
        "jobqueue.worker.enabled=true",
        "jobqueue.worker.concurrency=4",
        "jobqueue.worker.poll-interval-ms=100"
})
class ConcurrencyLimitWorkerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    LimitRepository limits;

    @Autowired
    JobRepository jobs;

    @Autowired
    ClaimService claims;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ObjectMapper mapper;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM jobs").update();
        jdbc.sql("DELETE FROM job_type_limits").update();
        claims.invalidateCache();
    }

    private long count(String status) {
        return jdbc.sql("SELECT count(*) FROM jobs WHERE type = 'SLOW' AND status = :status")
                .param("status", status).query(Long.class).single();
    }

    @Test
    void workersNeverRunMoreJobsOfALimitedTypeThanAllowed() throws Exception {
        limits.upsert("SLOW", 1);
        claims.invalidateCache();
        for (int i = 0; i < 3; i++) {
            jobs.insert(UUID.randomUUID(), "SLOW", mapper.createObjectNode().put("seconds", 2), 5, 0, 5);
        }

        long maxRunning = 0;
        long deadline = System.currentTimeMillis() + 40_000;
        while (System.currentTimeMillis() < deadline && count("SUCCEEDED") < 3) {
            maxRunning = Math.max(maxRunning, count("RUNNING"));
            Thread.sleep(50);
        }

        assertThat(count("SUCCEEDED")).isEqualTo(3);
        assertThat(maxRunning).isEqualTo(1);
    }
}
