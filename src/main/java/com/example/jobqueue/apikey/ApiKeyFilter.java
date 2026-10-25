package com.example.jobqueue.apikey;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Validates the {@code X-Api-Key} header on every request when API key auth is enabled
 * ({@code jobqueue.apikeys.enabled=true}).
 *
 * <p>On success: stores the resolved {@link ApiKey} as a request attribute under the name
 * {@value #ATTR_API_KEY}. Downstream code (e.g. {@code JobController}) can read the
 * {@code clientId} from there without touching the database again.
 *
 * <p>On failure: responds with {@code 401 Unauthorized} and a JSON body.
 *
 * <p>{@code last_used_at} is updated asynchronously so the response is never delayed by it.
 *
 * <p>This filter runs <em>after</em> {@code AdminWriteFilter} (which uses the existing HTTP Basic
 * admin password). The two are independent — you can enable either or both.
 */
@Component
@ConditionalOnProperty(name = "jobqueue.apikeys.enabled", havingValue = "true")
@Order(10)
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String ATTR_API_KEY = "jobqueue.apiKey";
    public static final String HEADER = "X-Api-Key";

    private final ApiKeyService service;

    public ApiKeyFilter(ApiKeyService service) {
        this.service = service;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        // Only enforce API key authentication on /api/ endpoints that are NOT under /api/admin/
        return !uri.startsWith("/api/") || uri.startsWith("/api/admin/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String raw = request.getHeader(HEADER);
        Optional<ApiKey> key = service.authenticate(raw);

        if (key.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"status\":401,\"detail\":\"Missing or invalid API key. Supply a valid X-Api-Key header.\"}");
            return;
        }

        ApiKey apiKey = key.get();
        request.setAttribute(ATTR_API_KEY, apiKey);

        // Update last_used_at asynchronously — do not block the request on it.
        CompletableFuture.runAsync(() -> service.touchLastUsed(apiKey.id()));

        chain.doFilter(request, response);
    }
}
