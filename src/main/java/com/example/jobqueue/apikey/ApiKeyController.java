package com.example.jobqueue.apikey;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin endpoints for managing per-client API keys.
 * These routes are already protected by {@code AdminWriteFilter} (HTTP Basic) for write operations
 * when {@code jobqueue.admin.password} is set — no additional auth is needed here.
 */
@RestController
@RequestMapping("/api/admin/keys")
public class ApiKeyController {

    private final ApiKeyService service;

    public ApiKeyController(ApiKeyService service) {
        this.service = service;
    }

    /**
     * Creates a new API key.
     * <strong>The raw key is returned once in this response and cannot be retrieved again.</strong>
     */
    @PostMapping
    public ResponseEntity<CreateApiKeyResponse> create(@Valid @RequestBody CreateApiKeyRequest request) {
        CreateApiKeyResponse response = service.create(request.clientId(), request.description());
        return ResponseEntity.status(201).body(response);
    }

    /** Lists all API keys (hashes and metadata only — never raw keys). */
    @GetMapping
    public List<ApiKey> list() {
        return service.list();
    }

    /**
     * Disables an API key. The key record is kept for audit; it will simply stop authenticating.
     * Returns 404 if the key does not exist.
     */
    @PostMapping("/{id}/disable")
    public ResponseEntity<ApiKey> disable(@PathVariable UUID id) {
        return service.disable(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Permanently deletes an API key.
     * Returns 404 if the key does not exist.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        return service.delete(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
