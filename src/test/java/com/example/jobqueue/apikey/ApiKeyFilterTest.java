package com.example.jobqueue.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiKeyFilterTest {

    private FakeApiKeyService service;
    private ApiKeyFilter filter;

    static class FakeApiKeyService extends ApiKeyService {
        String lastAuthenticatedKey;
        Optional<ApiKey> nextAuthResult = Optional.empty();

        FakeApiKeyService() {
            super(null);
        }

        @Override
        public Optional<ApiKey> authenticate(String rawKey) {
            this.lastAuthenticatedKey = rawKey;
            return nextAuthResult;
        }

        @Override
        public void touchLastUsed(UUID id) {
            // no-op
        }
    }

    @BeforeEach
    void setUp() {
        service = new FakeApiKeyService();
        filter = new ApiKeyFilter(service);
    }

    @Test
    void nonApiRequestsAreNotFiltered() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(service.lastAuthenticatedKey).isNull();
    }

    @Test
    void adminKeyEndpointsAreNotFilteredByApiKeyFilter() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/keys");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(service.lastAuthenticatedKey).isNull();
    }

    @Test
    void missingApiKeyReturns401() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/jobs");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Missing or invalid API key");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void invalidApiKeyReturns401() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/jobs");
        request.addHeader("X-Api-Key", "invalid-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        service.nextAuthResult = Optional.empty();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
        assertThat(service.lastAuthenticatedKey).isEqualTo("invalid-key");
    }

    @Test
    void validApiKeyPassesAndSetsRequestAttribute() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/jobs");
        request.addHeader("X-Api-Key", "secret-test-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        UUID keyId = UUID.randomUUID();
        ApiKey key = new ApiKey(keyId, "client-123", "hash123", "test key", true, Instant.now(), null);
        service.nextAuthResult = Optional.of(key);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(request.getAttribute(ApiKeyFilter.ATTR_API_KEY)).isEqualTo(key);
    }
}
