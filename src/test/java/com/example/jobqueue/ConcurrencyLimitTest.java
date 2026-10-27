package com.example.jobqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.jobqueue.domain.Job;
import com.example.jobqueue.limits.LimitRepository;
import com.example.jobqueue.repo.JobRepository;
import com.example.jobqueue.worker.ClaimService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** A job type with a limit never has more jobs running than allowed, even when many workers claim at once. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ConcurrencyLimitTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    ClaimService claims;

    @Autowired
    LimitRepository limits;

    @Autowired
    JobRepository jobs;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    MockMvc mvc;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM jobs").update();
        jdbc.sql("DELETE FROM job_type_limits").update();
        claims.invalidateCache();
    }

    private void addJobs(String type, int count) {
        for (int i = 0; i < count; i++) {
            jobs.insert(UUID.randomUUID(), type, mapper.createObjectNode(), 5, 0, 5);
        }
    }

    private long count(String sql, String type) {
        return jdbc.sql(sql).param("type", type).query(Long.class).single();
    }

    @Test
    void withoutLimitsEverythingCanBeClaimed() {
        addJobs("ANY", 5);

        int claimed = 0;
        while (claims.claimNext("w1").isPresent()) {
            claimed++;
        }
        assertThat(claimed).isEqualTo(5);
    }

    @Test
    void aLimitedTypeNeverExceedsItsLimitEvenWithManyWorkers() throws Exception {
        limits.upsert("LIMITED", 3);
        claims.invalidateCache();
        addJobs("LIMITED", 20);
        addJobs("FREE", 5);

        ConcurrentLinkedQueue<Job> claimed = new ConcurrentLinkedQueue<>();
        CountDownLatch startGun = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new ArrayList<>();
        for (int w = 0; w < 8; w++) {
            String workerId = "w" + w;
            futures.add(pool.submit(() -> {
                startGun.await();
                Optional<Job> job;
                while ((job = claims.claimNext(workerId)).isPresent()) {
                    claimed.add(job.get());
                }
                return null;
            }));
        }
        startGun.countDown();
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(claimed).hasSize(8); // 3 limited + 5 free
        assertThat(claimed.stream().filter(job -> job.type().equals("LIMITED")).count()).isEqualTo(3);
        assertThat(claimed.stream().filter(job -> job.type().equals("FREE")).count()).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM jobs WHERE type = :type AND status = 'RUNNING'", "LIMITED")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM jobs WHERE type = :type AND status = 'PENDING'", "LIMITED")).isEqualTo(17);
    }

    @Test
    void aFinishedJobFreesItsSlot() {
        limits.upsert("ONE", 1);
        claims.invalidateCache();
        addJobs("ONE", 2);

        Job first = claims.claimNext("w1").orElseThrow();
        assertThat(claims.claimNext("w2")).isEmpty(); // the only slot is taken

        jobs.markSucceeded(first.id(), "w1");

        assertThat(claims.claimNext("w2")).isPresent();
    }

    @Test
    void removingTheLimitUnblocksTheType() {
        limits.upsert("ONE", 1);
        claims.invalidateCache();
        addJobs("ONE", 2);
        claims.claimNext("w1").orElseThrow();
        assertThat(claims.claimNext("w2")).isEmpty();

        limits.delete("ONE");
        claims.invalidateCache();

        assertThat(claims.claimNext("w2")).isPresent();
    }

    @Test
    void theLimitsApiCreatesListsAndDeletesLimits() throws Exception {
        mvc.perform(post("/api/limits").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"SLOW\",\"maxConcurrent\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("SLOW"))
                .andExpect(jsonPath("$.maxConcurrent").value(2));

        mvc.perform(get("/api/limits"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].running").value(0));

        mvc.perform(post("/api/limits").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"SLOW\",\"maxConcurrent\":0}"))
                .andExpect(status().isBadRequest());

        mvc.perform(delete("/api/limits/SLOW")).andExpect(status().isOk());
        mvc.perform(delete("/api/limits/SLOW")).andExpect(status().isNotFound());
    }
}
