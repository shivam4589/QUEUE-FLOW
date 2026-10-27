package com.example.jobqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Per-client API keys: creation, attribution, what a key may do, disabling, and per-client idempotency. */
@Testcontainers
@SpringBootTest(properties = {"jobqueue.admin.user=admin", "jobqueue.admin.password=s3cret"})
@AutoConfigureMockMvc
class ApiKeyIntegrationTest {

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

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM jobs").update();
        jdbc.sql("DELETE FROM api_clients").update();
    }

    private static String admin() {
        return "Basic " + Base64.getEncoder().encodeToString("admin:s3cret".getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private JsonNode createClient(String name) throws Exception {
        return body(mvc.perform(post("/api/clients").header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()));
    }

    private ResultActions submitWithKey(String apiKey, String idempotencyKey) throws Exception {
        var request = post("/api/jobs").contentType(MediaType.APPLICATION_JSON).content(JOB)
                .header("Authorization", "Bearer " + apiKey);
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request);
    }

    @Test
    void aNewClientGetsAKeyThatIsShownOnlyOnce() throws Exception {
        JsonNode created = createClient("billing");
        String apiKey = created.get("apiKey").asText();

        assertThat(apiKey).startsWith("jq_").hasSizeGreaterThan(30);
        assertThat(created.get("client").get("name").asText()).isEqualTo("billing");
        assertThat(apiKey).startsWith(created.get("client").get("keyPrefix").asText());

        String list = mvc.perform(get("/api/clients").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContain(apiKey); // the key can never be read back
    }

    @Test
    void duplicateClientNamesConflict() throws Exception {
        createClient("billing");

        mvc.perform(post("/api/clients").header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"billing\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void jobsSubmittedWithAKeyAreAttributedToTheClient() throws Exception {
        JsonNode created = createClient("billing");

        submitWithKey(created.get("apiKey").asText(), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.clientId").value(created.get("client").get("id").asText()));

        // the admin login still works and records no client
        mvc.perform(post("/api/jobs").header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON).content(JOB))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.clientId").doesNotExist());
    }

    @Test
    void unknownKeysAndMissingCredentialsAreRefused() throws Exception {
        submitWithKey("jq_not-a-real-key", null).andExpect(status().isUnauthorized());

        mvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON).content(JOB))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("WWW-Authenticate"));
    }

    @Test
    void aDisabledClientIsRefusedUntilItIsEnabledAgain() throws Exception {
        JsonNode created = createClient("billing");
        String apiKey = created.get("apiKey").asText();
        String id = created.get("client").get("id").asText();

        mvc.perform(post("/api/clients/" + id + "/disable").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        submitWithKey(apiKey, null).andExpect(status().isForbidden());

        mvc.perform(post("/api/clients/" + id + "/enable").header("Authorization", admin()))
                .andExpect(status().isOk());
        submitWithKey(apiKey, null).andExpect(status().isCreated());

        mvc.perform(post("/api/clients/" + UUID.randomUUID() + "/disable").header("Authorization", admin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void anApiKeyCanOnlySubmitJobs() throws Exception {
        String apiKey = createClient("billing").get("apiKey").asText();
        String bearer = "Bearer " + apiKey;

        mvc.perform(post("/api/schedules").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/clients").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/clients").header("Authorization", bearer)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/jobs/" + UUID.randomUUID()).header("Authorization", bearer))
                .andExpect(status().isForbidden());
    }

    @Test
    void theClientListNeedsTheAdminLogin() throws Exception {
        mvc.perform(get("/api/clients")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/clients").header("Authorization", admin())).andExpect(status().isOk());
        mvc.perform(get("/api/jobs")).andExpect(status().isOk()); // reading jobs stays public
    }

    @Test
    void idempotencyKeysBelongToTheClient() throws Exception {
        String keyA = createClient("client-a").get("apiKey").asText();
        String keyB = createClient("client-b").get("apiKey").asText();

        String firstA = body(submitWithKey(keyA, "order-1").andExpect(status().isCreated())).get("id").asText();
        // the same idempotency key from a different client is a different key: a new job
        String firstB = body(submitWithKey(keyB, "order-1").andExpect(status().isCreated())).get("id").asText();
        assertThat(firstB).isNotEqualTo(firstA);

        // the same client repeating its key gets its own job back
        String again = body(submitWithKey(keyA, "order-1").andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))).get("id").asText();
        assertThat(again).isEqualTo(firstA);
    }
}
