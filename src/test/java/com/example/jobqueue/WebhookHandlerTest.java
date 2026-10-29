package com.example.jobqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.jobqueue.domain.Job;
import com.example.jobqueue.domain.JobStatus;
import com.example.jobqueue.worker.handlers.WebhookHandler;
import com.example.jobqueue.worker.handlers.WebhookProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The webhook job calls a real local HTTP server; unsafe targets are refused before any connection is made. */
class WebhookHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private HttpServer server;
    private String base;
    private volatile String receivedMethod;
    private volatile String receivedBody;
    private volatile String receivedContentType;
    private volatile String receivedToken;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/ok", exchange -> {
            receivedMethod = exchange.getRequestMethod();
            receivedBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            receivedContentType = exchange.getRequestHeaders().getFirst("Content-Type");
            receivedToken = exchange.getRequestHeaders().getFirst("X-Token");
            reply(exchange, 200, "{\"received\":true}");
        });
        server.createContext("/fail", exchange -> reply(exchange, 500, "boom"));
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/ok");
            reply(exchange, 302, "");
        });
        server.start();
        base = "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }

    private WebhookHandler handler(boolean allowPrivate, boolean allowHttp) {
        return new WebhookHandler(new WebhookProperties(allowPrivate, allowHttp, 5, 65536));
    }

    private Job job(ObjectNode payload) {
        Instant now = Instant.now();
        return new Job(UUID.randomUUID(), "WEBHOOK", payload, JobStatus.RUNNING, 5, now, 1, 5, null, now, now,
                null, null, false, null, null);
    }

    private ObjectNode payload(String url) {
        return mapper.createObjectNode().put("url", url);
    }

    @Test
    void postsTheBodyAndReturnsTheResponseAsTheResult() throws Exception {
        ObjectNode payload = payload(base + "/ok");
        payload.putObject("body").put("hello", "world");
        payload.putObject("headers").put("X-Token", "abc");

        JsonNode result = handler(true, true).run(job(payload));

        assertThat(result.get("status").asInt()).isEqualTo(200);
        assertThat(result.get("bodyPreview").asText()).contains("received");
        assertThat(receivedMethod).isEqualTo("POST");
        assertThat(receivedBody).isEqualTo("{\"hello\":\"world\"}");
        assertThat(receivedContentType).startsWith("application/json");
        assertThat(receivedToken).isEqualTo("abc");
    }

    @Test
    void getRequestsSendNoBody() throws Exception {
        ObjectNode payload = payload(base + "/ok").put("method", "get");
        payload.putObject("body").put("ignored", true);

        handler(true, true).run(job(payload));

        assertThat(receivedMethod).isEqualTo("GET");
        assertThat(receivedBody).isEmpty();
    }

    @Test
    void aNon2xxResponseFailsTheAttempt() {
        assertThatThrownBy(() -> handler(true, true).run(job(payload(base + "/fail"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 500");
    }

    @Test
    void redirectsAreNotFollowed() {
        assertThatThrownBy(() -> handler(true, true).run(job(payload(base + "/redirect"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 302");
    }

    @Test
    void plainHttpIsRefusedByDefault() {
        assertThatThrownBy(() -> handler(true, false).run(job(payload(base + "/ok"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https");
    }

    @Test
    void internalAddressesAreRefusedByDefault() {
        WebhookHandler strict = handler(false, true);
        for (String url : new String[] {
                base + "/ok", "https://127.0.0.1/", "https://169.254.169.254/latest/meta-data/",
                "https://10.0.0.5/", "https://192.168.1.1/", "https://172.16.0.1/", "https://100.64.0.1/",
                "https://0.0.0.0/", "https://[::1]/", "https://[fd00::1]/"}) {
            assertThatThrownBy(() -> strict.run(job(payload(url))))
                    .as(url)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("internal");
        }
    }

    @Test
    void unsafeOrMalformedPayloadsAreRejected() {
        WebhookHandler handler = handler(true, true);

        assertThatThrownBy(() -> handler.run(job(mapper.createObjectNode()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> handler.run(job(payload("https://user:pw@example.com/"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("credentials");
        assertThatThrownBy(() -> handler.run(job(payload(base + "/ok").put("method", "TRACE"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("method");

        ObjectNode badValue = payload(base + "/ok");
        badValue.putObject("headers").put("X-Token", "a\r\nInjected: yes");
        assertThatThrownBy(() -> handler.run(job(badValue))).isInstanceOf(IllegalArgumentException.class);

        ObjectNode badName = payload(base + "/ok");
        badName.putObject("headers").put("Host", "evil.example");
        assertThatThrownBy(() -> handler.run(job(badName))).isInstanceOf(IllegalArgumentException.class);
    }
}
