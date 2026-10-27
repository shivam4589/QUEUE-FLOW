package com.example.jobqueue.clients;

import com.example.jobqueue.api.JobStateConflictException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class ClientService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String KEY_PREFIX = "jq_";

    private final ClientRepository repository;

    public ClientService(ClientRepository repository) {
        this.repository = repository;
    }

    /** Creates a client and a random 256-bit key. Only the key's hash is stored, so this is the only time it is shown. */
    public NewClientResponse create(String name) {
        String trimmed = name.trim();
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String key = KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        try {
            ApiClient client = repository.create(UUID.randomUUID(), trimmed, key.substring(0, 11), hash(key));
            return new NewClientResponse(client, key);
        } catch (DuplicateKeyException e) {
            throw new JobStateConflictException("An API client named '" + trimmed + "' already exists");
        }
    }

    /** The client that owns this key (enabled or not), or empty if the key is unknown. */
    public Optional<ApiClient> authenticate(String presentedKey) {
        if (presentedKey == null || presentedKey.isBlank() || presentedKey.length() > 200) {
            return Optional.empty();
        }
        return repository.findByKeyHash(hash(presentedKey));
    }

    public void touch(UUID id) {
        repository.touch(id);
    }

    public List<ApiClient> list() {
        return repository.list();
    }

    public ApiClient setEnabled(UUID id, boolean enabled) {
        if (repository.setEnabled(id, enabled) == 0) {
            throw new ClientNotFoundException(id);
        }
        return repository.findById(id).orElseThrow(() -> new ClientNotFoundException(id));
    }

    // Keys are long random values, so a plain SHA-256 is enough (no salt or slow hash needed).
    private static String hash(String key) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
