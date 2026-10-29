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

    /** Columns without the job counts (the counts are only needed when listing). */
    private static final String BASIC_COLUMNS =
            "id, name, key_prefix, enabled, created_at, last_used_at, rate_limit_per_minute, daily_quota, "
                    + "CAST(0 AS bigint) AS jobs, CAST(0 AS bigint) AS used_today";

    private static final String COUNTED_COLUMNS =
            "c.id, c.name, c.key_prefix, c.enabled, c.created_at, c.last_used_at, "
                    + "c.rate_limit_per_minute, c.daily_quota, "
                    + "(SELECT count(*) FROM jobs j WHERE j.client_id = c.id) AS jobs, "
                    + "(SELECT count(*) FROM jobs j WHERE j.client_id = c.id "
                    + "AND j.created_at >= (date_trunc('day', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC')) "
                    + "AS used_today";

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

    /** Sets or clears (null) the client's limits. */
    public int setLimits(UUID id, Integer ratePerMinute, Integer dailyQuota) {
        return jdbc.sql("UPDATE api_clients SET rate_limit_per_minute = :rate, daily_quota = :daily WHERE id = :id")
                .param("id", id)
                .param("rate", ratePerMinute)
                .param("daily", dailyQuota)
                .update();
    }

    public Optional<ClientLimits> findLimits(UUID id) {
        return jdbc.sql("SELECT rate_limit_per_minute, daily_quota FROM api_clients WHERE id = :id")
                .param("id", id)
                .query((rs, i) -> new ClientLimits(nullableInt(rs, "rate_limit_per_minute"), nullableInt(rs, "daily_quota")))
                .optional();
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

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
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
                nullableInt(rs, "rate_limit_per_minute"),
                nullableInt(rs, "daily_quota"),
                rs.getLong("jobs"),
                rs.getLong("used_today"));
    }
}
