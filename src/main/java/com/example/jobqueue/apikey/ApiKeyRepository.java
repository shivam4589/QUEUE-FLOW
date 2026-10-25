package com.example.jobqueue.apikey;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ApiKeyRepository {

    private final JdbcClient jdbc;

    public ApiKeyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Stores a new key. The caller is responsible for generating the random key and computing its
     * hash before calling this method.
     */
    public ApiKey insert(UUID id, String clientId, String keyHash, String description) {
        return jdbc.sql("""
                INSERT INTO api_keys (id, client_id, key_hash, description)
                VALUES (:id, :clientId, :keyHash, :description)
                RETURNING *
                """)
                .param("id", id)
                .param("clientId", clientId)
                .param("keyHash", keyHash)
                .param("description", description)
                .query(this::map)
                .single();
    }

    /** Look up a key by its SHA-256 hash. Used on every authenticated request. */
    public Optional<ApiKey> findByHash(String keyHash) {
        return jdbc.sql("SELECT * FROM api_keys WHERE key_hash = :hash")
                .param("hash", keyHash)
                .query(this::map)
                .optional();
    }

    public List<ApiKey> findAll() {
        return jdbc.sql("SELECT * FROM api_keys ORDER BY created_at DESC")
                .query(this::map)
                .list();
    }

    public Optional<ApiKey> findById(UUID id) {
        return jdbc.sql("SELECT * FROM api_keys WHERE id = :id")
                .param("id", id)
                .query(this::map)
                .optional();
    }

    /** Enables or disables a key. Returns the updated row, or empty if not found. */
    public Optional<ApiKey> setEnabled(UUID id, boolean enabled) {
        return jdbc.sql("""
                UPDATE api_keys SET enabled = :enabled
                WHERE id = :id
                RETURNING *
                """)
                .param("id", id)
                .param("enabled", enabled)
                .query(this::map)
                .optional();
    }

    /** Deletes a key. Returns true if a row was deleted. */
    public boolean delete(UUID id) {
        return jdbc.sql("DELETE FROM api_keys WHERE id = :id")
                .param("id", id)
                .update() > 0;
    }

    /**
     * Updates last_used_at asynchronously on each use; intentionally fire-and-forget — a failure
     * here must never fail the actual request.
     */
    public void touchLastUsed(UUID id) {
        try {
            jdbc.sql("UPDATE api_keys SET last_used_at = now() WHERE id = :id")
                    .param("id", id)
                    .update();
        } catch (Exception ignored) {
            // best-effort; do not propagate
        }
    }

    private ApiKey map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp lastUsed = rs.getTimestamp("last_used_at");
        return new ApiKey(
                rs.getObject("id", UUID.class),
                rs.getString("client_id"),
                rs.getString("key_hash"),
                rs.getString("description"),
                rs.getBoolean("enabled"),
                rs.getTimestamp("created_at").toInstant(),
                lastUsed == null ? null : lastUsed.toInstant());
    }
}
