package com.example.jobqueue.clients;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ClientRepository {

    /** Columns without the job count (the count is only needed when listing). */
    private static final String BASIC_COLUMNS =
            "id, name, key_prefix, enabled, created_at, last_used_at, CAST(0 AS bigint) AS jobs";

    private static final String COUNTED_COLUMNS =
            "c.id, c.name, c.key_prefix, c.enabled, c.created_at, c.last_used_at, "
                    + "(SELECT count(*) FROM jobs j WHERE j.client_id = c.id) AS jobs";

    private final JdbcClient jdbc;

    public ClientRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public ApiClient create(UUID id, String name, String keyPrefix, String keyHash) {
        return jdbc.sql("INSERT INTO api_clients (id, name, key_prefix, key_hash) "
                        + "VALUES (:id, :name, :prefix, :hash) RETURNING " + BASIC_COLUMNS)
                .param("id", id)
                .param("name", name)
                .param("prefix", keyPrefix)
                .param("hash", keyHash)
                .query(this::map)
                .single();
    }

    public Optional<ApiClient> findByKeyHash(String keyHash) {
        return jdbc.sql("SELECT " + BASIC_COLUMNS + " FROM api_clients WHERE key_hash = :hash")
                .param("hash", keyHash)
                .query(this::map)
                .optional();
    }

    public Optional<ApiClient> findById(UUID id) {
        return jdbc.sql("SELECT " + COUNTED_COLUMNS + " FROM api_clients c WHERE c.id = :id")
                .param("id", id)
                .query(this::map)
                .optional();
    }

    public List<ApiClient> list() {
        return jdbc.sql("SELECT " + COUNTED_COLUMNS + " FROM api_clients c ORDER BY c.created_at DESC")
                .query(this::map)
                .list();
    }

    public int setEnabled(UUID id, boolean enabled) {
        return jdbc.sql("UPDATE api_clients SET enabled = :enabled WHERE id = :id")
                .param("id", id)
                .param("enabled", enabled)
                .update();
    }

    /** Records that the key was used, at most once a minute so busy clients do not cause a write per request. */
    public void touch(UUID id) {
        jdbc.sql("""
                UPDATE api_clients SET last_used_at = now()
                WHERE id = :id AND (last_used_at IS NULL OR last_used_at < now() - INTERVAL '1 minute')
                """)
                .param("id", id)
                .update();
    }

    private ApiClient map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp lastUsed = rs.getTimestamp("last_used_at");
        return new ApiClient(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("key_prefix"),
                rs.getBoolean("enabled"),
                rs.getTimestamp("created_at").toInstant(),
                lastUsed == null ? null : lastUsed.toInstant(),
                rs.getLong("jobs"));
    }
}
