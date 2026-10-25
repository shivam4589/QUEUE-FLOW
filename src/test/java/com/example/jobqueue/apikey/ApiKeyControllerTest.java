package com.example.jobqueue.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class ApiKeyControllerTest {

    private FakeApiKeyService service;
    private ApiKeyController controller;

    static class FakeApiKeyService extends ApiKeyService {
        CreateApiKeyResponse nextCreateResponse;
        List<ApiKey> nextListResponse = List.of();
        Optional<ApiKey> nextDisableResponse = Optional.empty();
        boolean nextDeleteResponse;

        FakeApiKeyService() {
            super(null);
        }

        @Override
        public CreateApiKeyResponse create(String clientId, String description) {
            return nextCreateResponse;
        }

        @Override
        public List<ApiKey> list() {
            return nextListResponse;
        }

        @Override
        public Optional<ApiKey> disable(UUID id) {
            return nextDisableResponse;
        }

        @Override
        public boolean delete(UUID id) {
            return nextDeleteResponse;
        }
    }

    @BeforeEach
    void setUp() {
        service = new FakeApiKeyService();
        controller = new ApiKeyController(service);
    }

    @Test
    void createReturns201WithRawKey() {
        UUID id = UUID.randomUUID();
        ApiKey key = new ApiKey(id, "test-client", "hash", "desc", true, Instant.now(), null);
        service.nextCreateResponse = new CreateApiKeyResponse(key, "raw-token-abc");

        ResponseEntity<CreateApiKeyResponse> result = controller.create(new CreateApiKeyRequest("test-client", "desc"));

        assertThat(result.getStatusCode().value()).isEqualTo(201);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().rawKey()).isEqualTo("raw-token-abc");
        assertThat(result.getBody().key().clientId()).isEqualTo("test-client");
    }

    @Test
    void listReturnsAllKeys() {
        ApiKey key1 = new ApiKey(UUID.randomUUID(), "c1", "h1", null, true, Instant.now(), null);
        ApiKey key2 = new ApiKey(UUID.randomUUID(), "c2", "h2", null, false, Instant.now(), null);

        service.nextListResponse = List.of(key1, key2);

        List<ApiKey> list = controller.list();
        assertThat(list).hasSize(2);
    }

    @Test
    void disableReturnsUpdatedKeyOr404() {
        UUID id = UUID.randomUUID();
        ApiKey disabled = new ApiKey(id, "c1", "h1", null, false, Instant.now(), null);

        service.nextDisableResponse = Optional.of(disabled);
        assertThat(controller.disable(id).getStatusCode().value()).isEqualTo(200);

        service.nextDisableResponse = Optional.empty();
        assertThat(controller.disable(UUID.randomUUID()).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void deleteReturns204Or404() {
        UUID id = UUID.randomUUID();
        service.nextDeleteResponse = true;
        assertThat(controller.delete(id).getStatusCode().value()).isEqualTo(204);

        service.nextDeleteResponse = false;
        assertThat(controller.delete(UUID.randomUUID()).getStatusCode().value()).isEqualTo(404);
    }
}
