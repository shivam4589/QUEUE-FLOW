package com.example.jobqueue.worker.handlers;

import com.example.jobqueue.domain.Job;
import com.example.jobqueue.worker.ResultJobHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * A real job type: calls a URL.
 *
 * Payload: {"url": "https://...", "method": "POST" (optional: GET, POST, PUT, PATCH, DELETE),
 *           "headers": {"X-Token": "..."} (optional), "body": {...} (optional, sent as JSON)}
 * Result: the HTTP status, how long it took, and the start of the response body. A response that is not 2xx fails the
 * attempt, so the usual retries with backoff apply.
 *
 * Because anyone who can submit a job chooses the URL, the target is checked: https only, no credentials in the URL,
 * and no private / loopback / link-local addresses unless explicitly allowed. Redirects are never followed (a redirect
 * could point at an internal address). Known limit: the address is checked and then connected to separately, so a
 * hostile DNS server could in theory answer differently the second time (DNS rebinding).
 */
@Component
public class WebhookHandler implements ResultJobHandler {

    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> FORBIDDEN_HEADERS =
            Set.of("host", "content-length", "connection", "transfer-encoding", "upgrade", "expect");
    private static final Pattern HEADER_NAME = Pattern.compile("[A-Za-z0-9-]{1,50}");
    private static final int MAX_HEADERS = 10;
    private static final int MAX_HEADER_VALUE = 500;

    private final WebhookProperties props;
    private final HttpClient client;

    public WebhookHandler(WebhookProperties props) {
        this.props = props;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, props.timeoutSeconds())))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public String type() {
        return "WEBHOOK";
    }

    @Override
    public JsonNode run(Job job) throws Exception {
        JsonNode payload = job.payload();
        URI uri = validateTarget(payload.path("url").asText(""));

        String method = payload.path("method").asText("POST").toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) {
            throw new IllegalArgumentException("Webhook method must be one of " + METHODS);
        }
        Map<String, String> headers = readHeaders(payload.get("headers"));

        JsonNode body = payload.get("body");
        boolean sendsBody = body != null && !body.isNull() && !method.equals("GET") && !method.equals("DELETE");

        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(Math.max(1, props.timeoutSeconds())))
                .header("User-Agent", "JobQueue-Webhook/1.0");
        headers.forEach(request::header);
        if (sendsBody && headers.keySet().stream().noneMatch(name -> name.equalsIgnoreCase("Content-Type"))) {
            request.header("Content-Type", "application/json");
        }
        String text = !sendsBody ? "" : (body.isTextual() ? body.asText() : body.toString());
        request.method(method, sendsBody
                ? HttpRequest.BodyPublishers.ofString(text, StandardCharsets.UTF_8)
                : HttpRequest.BodyPublishers.noBody());

        long started = System.nanoTime();
        HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        byte[] bytes;
        try (InputStream in = response.body()) {
            bytes = in.readNBytes(Math.max(1, props.maxResponseBytes()));
        }
        long durationMs = (System.nanoTime() - started) / 1_000_000;
        String preview = new String(bytes, StandardCharsets.UTF_8);

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            String detail = preview.isBlank() ? "" : ": " + shorten(preview, 200);
            throw new IllegalStateException("Webhook returned HTTP " + status + detail);
        }
        return JsonNodeFactory.instance.objectNode()
                .put("status", status)
                .put("durationMs", durationMs)
                .put("responseBytes", bytes.length)
                .put("bodyPreview", shorten(preview, 500));
    }

    private URI validateTarget(String raw) throws IOException {
        URI uri;
        try {
            uri = new URI(raw.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Webhook URL is not valid");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("https") || (props.allowHttp() && scheme.equals("http")))) {
            throw new IllegalArgumentException("Webhook URL must use https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("Webhook URL needs a host name");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Webhook URL must not contain credentials");
        }
        if (!props.allowPrivateNetworks()) {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (isInternal(address)) {
                    throw new IllegalArgumentException(
                            "Webhook URL not allowed: " + uri.getHost() + " is an internal address");
                }
            }
        }
        return uri;
    }

    private static boolean isInternal(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            return first == 0 || (first == 100 && second >= 64 && second <= 127); // 0.0.0.0/8, carrier-grade NAT
        }
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC; // fc00::/7 unique local addresses
    }

    private static Map<String, String> readHeaders(JsonNode node) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (node == null || node.isNull() || node.isMissingNode()) {
            return headers;
        }
        if (!node.isObject() || node.size() > MAX_HEADERS) {
            throw new IllegalArgumentException("Webhook headers must be an object with at most " + MAX_HEADERS + " entries");
        }
        node.fields().forEachRemaining(entry -> {
            String name = entry.getKey();
            String value = entry.getValue().asText("");
            if (!HEADER_NAME.matcher(name).matches() || FORBIDDEN_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Webhook header not allowed: " + name);
            }
            if (value.length() > MAX_HEADER_VALUE || value.chars().anyMatch(c -> (c < 32 && c != '\t') || c == 127)) {
                throw new IllegalArgumentException("Webhook header value not allowed for " + name);
            }
            headers.put(name, value);
        });
        return headers;
    }

    private static String shorten(String text, int max) {
        String clean = text.replaceAll("\\p{Cntrl}", " ").trim();
        return clean.length() <= max ? clean : clean.substring(0, max) + "...";
    }
}
