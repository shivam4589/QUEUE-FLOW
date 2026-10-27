package com.example.jobqueue.api;

import com.example.jobqueue.clients.ApiClient;
import com.example.jobqueue.clients.ClientService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Access control for the API.
 *
 * Reading is public, except GET /api/clients (it lists the API clients). Changing anything (POST / PUT / PATCH /
 * DELETE under /api) needs one of:
 *  - the admin login (HTTP Basic) when jobqueue.admin.password is set; with no admin password everything is open,
 *    which keeps local development simple; or
 *  - an API key ("Authorization: Bearer jq_..."), which may only submit jobs (POST /api/jobs). The job is then
 *    recorded as submitted by that client.
 * An unknown key is always refused (401), a disabled client's key too (403). The class keeps its old name for
 * history; it now covers both kinds of credentials.
 */
@Component
public class AdminWriteFilter extends OncePerRequestFilter {

    /** Request attribute carrying the id of the authenticated API client. */
    public static final String CLIENT_ATTRIBUTE = "jobqueue.clientId";

    private final ClientService clients;
    private final byte[] expectedAdmin; // null when no admin password is configured

    public AdminWriteFilter(ClientService clients,
                            @Value("${jobqueue.admin.user:admin}") String user,
                            @Value("${jobqueue.admin.password:}") String password) {
        this.clients = clients;
        this.expectedAdmin = (password == null || password.isBlank())
                ? null
                : (user + ":" + password).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (!uri.startsWith("/api/")) {
            return true;
        }
        String method = request.getMethod();
        boolean write = "POST".equals(method) || "PUT".equals(method)
                || "PATCH".equals(method) || "DELETE".equals(method);
        boolean clientList = "GET".equals(method) && uri.startsWith("/api/clients");
        return !(write || clientList);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");

        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            handleApiKey(header.substring(7).trim(), request, response, chain);
            return;
        }
        if (expectedAdmin == null || isAdmin(header)) {
            chain.doFilter(request, response);
            return;
        }
        reject(response, HttpServletResponse.SC_UNAUTHORIZED, "Admin login required to change anything",
                "Basic realm=\"JobQueue admin\", charset=\"UTF-8\"");
    }

    private void handleApiKey(String key, HttpServletRequest request, HttpServletResponse response,
                              FilterChain chain) throws ServletException, IOException {
        Optional<ApiClient> client = clients.authenticate(key);
        if (client.isEmpty()) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid API key", "Bearer realm=\"JobQueue API\"");
            return;
        }
        if (!client.get().enabled()) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "This API key is disabled", null);
            return;
        }
        if (!"POST".equals(request.getMethod()) || !"/api/jobs".equals(request.getRequestURI())) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "API keys can only submit jobs", null);
            return;
        }
        clients.touch(client.get().id());
        request.setAttribute(CLIENT_ATTRIBUTE, client.get().id());
        chain.doFilter(request, response);
    }

    private boolean isAdmin(String header) {
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            return false;
        }
        try {
            byte[] given = Base64.getDecoder().decode(header.substring(6).trim());
            return MessageDigest.isEqual(given, expectedAdmin); // constant-time comparison
        } catch (IllegalArgumentException malformedBase64) {
            return false;
        }
    }

    private static void reject(HttpServletResponse response, int status, String detail, String challenge)
            throws IOException {
        response.setStatus(status);
        if (challenge != null) {
            response.setHeader("WWW-Authenticate", challenge);
        }
        response.setContentType("application/json");
        response.getWriter().write("{\"status\":" + status + ",\"detail\":\"" + detail + "\"}");
    }
}
