package com.example.jobqueue.apikey;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ApiKeyService {

    private static final int KEY_BYTES = 32; // 256-bit random key → 43 Base64URL chars
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiKeyRepository repository;

    public ApiKeyService(ApiKeyRepository repository) {
        this.repository = repository;
    }

    /**
     * Generates a new cryptographically random API key, stores its hash, and returns the raw key
     * alongside the stored record. The raw key is never stored and cannot be recovered later.
     */
    public CreateApiKeyResponse create(String clientId, String description) {
        byte[] bytes = new byte[KEY_BYTES];
        RANDOM.nextBytes(bytes);
        String rawKey = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String keyHash = sha256Hex(rawKey);
        UUID id = UUID.randomUUID();
        ApiKey stored = repository.insert(id, clientId, keyHash, description);
        return new CreateApiKeyResponse(stored, rawKey);
    }

    /**
     * Validates an incoming raw key: hashes it and looks it up in the database.
     * Returns the matching enabled {@link ApiKey}, or empty if the key is unknown or disabled.
     */
    public Optional<ApiKey> authenticate(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return Optional.empty();
        }
        String hash = sha256Hex(rawKey.trim());
        return repository.findByHash(hash)
                .filter(ApiKey::enabled);
    }

    public void touchLastUsed(UUID id) {
        repository.touchLastUsed(id);
    }

    public List<ApiKey> list() {
        return repository.findAll();
    }

    public Optional<ApiKey> disable(UUID id) {
        return repository.setEnabled(id, false);
    }

    public boolean delete(UUID id) {
        return repository.delete(id);
    }

    private static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes());
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
