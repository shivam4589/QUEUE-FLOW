package com.example.jobqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.jobqueue.api.JobService;
import com.example.jobqueue.api.QuotaExceededException;
import com.example.jobqueue.api.SubmitJobRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Per-client rate limits and daily quotas: enforced exactly, answered with 429, never counting replays. */
@Testcontainers
@SpringBootTest(properties = {"jobqueue.admin.user=admin", "jobqueue.admin.password=s3cret"})
@AutoConfigureMockMvc
class ClientQuotaTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String JOB = "{\"type\":\"T\",\"payload\":{}}";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    JobService jobService;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM jobs").update();
        jdbc.sql("DELETE FROM api_clients").update();
    }

    private static String admin() {
        return "Basic " + Base64.getEncoder().encodeToString("admin:s3cret".getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode createClient(String name) throws Exception {
        String json = mvc.perform(post("/api/clients").header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(json);
    }

    private ResultActions setLimits(String clientId, String body) throws Exception {
        return mvc.perform(post("/api/clients/" + clientId + "/limits").header("Authorization", admin())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions submit(String apiKey, String idempotencyKey) throws Exception {
        var request = post("/api/jobs").contentType(MediaType.APPLICATION_JSON).content(JOB)
                .header("Authorization", "Bearer " + apiKey);
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request);
    }

    private long jobsOf(String clientId) {
        return jdbc.sql("SELECT count(*) FROM jobs WHERE client_id = :c")
                .param("c", UUID.fromString(clientId)).query(Long.class).single();
    }

    @Test
    void theRateLimitRejectsTheExcessJobAndTellsWhenToRetry() throws Exception {
        JsonNode created = createClient("billing");
        String key = created.get("apiKey").asText();
        String id = created.get("client").get("id").asText();
        setLimits(id, "{\"ratePerMinute\":2}").andExpect(status().isOk())
                .andExpect(jsonPath("$.ratePerMinute").value(2));

        submit(key, null).andExpect(status().isCreated());
        submit(key, null).andExpect(status().isCreated());
        submit(key, null).andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.detail").value(containsString("Rate limit")));

        assertThat(jobsOf(id)).isEqualTo(2); // the rejected job was rolled back, not kept
    }

    @Test
    void theDailyQuotaRejectsTheExcessJob() throws Exception {
        JsonNode created = createClient("reports");
        String key = created.get("apiKey").asText();
        String id = created.get("client").get("id").asText();
        setLimits(id, "{\"dailyQuota\":1}").andExpect(status().isOk());

        submit(key, null).andExpect(status().isCreated());
        submit(key, null).andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.detail").value(containsString("Daily quota")));
    }

    @Test
    void aReplayOfAnEarlierRequestDoesNotCountAgainstTheLimit() throws Exception {
        JsonNode created = createClient("billing");
        String key = created.get("apiKey").asText();
        setLimits(created.get("client").get("id").asText(), "{\"ratePerMinute\":1}").andExpect(status().isOk());

        submit(key, "order-1").andExpect(status().isCreated());
        submit(key, "order-1").andExpect(status().isOk()); // same request again: the original job, not a new one
    }

    @Test
    void clientsWithoutLimitsAreUnrestricted() throws Exception {
        String key = createClient("free").get("apiKey").asText();

        for (int i = 0; i < 5; i++) {
            submit(key, null).andExpect(status().isCreated());
        }
    }

    @Test
    void limitsCanBeRemovedAgainAndAreShownInTheClientList() throws Exception {
        JsonNode created = createClient("billing");
        String key = created.get("apiKey").asText();
        String id = created.get("client").get("id").asText();
        setLimits(id, "{\"ratePerMinute\":1,\"dailyQuota\":50}").andExpect(status().isOk());
        submit(key, null).andExpect(status().isCreated());

        mvc.perform(get("/api/clients").header("Authorization", admin()))
                .andExpect(jsonPath("$[0].ratePerMinute").value(1))
                .andExpect(jsonPath("$[0].dailyQuota").value(50))
                .andExpect(jsonPath("$[0].usedToday").value(1));

        setLimits(id, "{}").andExpect(status().isOk()).andExpect(jsonPath("$.ratePerMinute").doesNotExist());
        submit(key, null).andExpect(status().isCreated());
        submit(key, null).andExpect(status().isCreated());
    }

    @Test
    void invalidLimitsAndUnknownClientsAreRejected() throws Exception {
        String id = createClient("billing").get("client").get("id").asText();

        setLimits(id, "{\"ratePerMinute\":0}").andExpect(status().isBadRequest());
        setLimits(UUID.randomUUID().toString(), "{\"ratePerMinute\":5}").andExpect(status().isNotFound());
    }

    @Test
    void anApiKeyCannotChangeItsOwnLimits() throws Exception {
        JsonNode created = createClient("billing");
        mvc.perform(post("/api/clients/" + created.get("client").get("id").asText() + "/limits")
                        .header("Authorization", "Bearer " + created.get("apiKey").asText())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void concurrentSubmissionsNeverExceedTheLimit() throws Exception {
        JsonNode created = createClient("burst");
        String id = created.get("client").get("id").asText();
        UUID clientId = UUID.fromString(id);
        setLimits(id, "{\"ratePerMinute\":5}").andExpect(status().isOk());

        SubmitJobRequest request = new SubmitJobRequest("T", mapper.createObjectNode(), null, null, null);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            futures.add(pool.submit(() -> {
                try {
                    jobService.submit(request, null, clientId);
                    return true;
                } catch (QuotaExceededException e) {
                    return false;
                }
            }));
        }
        int accepted = 0;
        for (Future<Boolean> future : futures) {
            if (future.get(60, TimeUnit.SECONDS)) {
                accepted++;
            }
        }
        pool.shutdown();

        assertThat(accepted).isEqualTo(5);
        assertThat(jobsOf(id)).isEqualTo(5);
    }
}
