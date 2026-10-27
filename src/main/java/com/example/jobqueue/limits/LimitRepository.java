package com.example.jobqueue.limits;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class LimitRepository {

    private static final String SELECT = """
            SELECT l.job_type, l.max_concurrent,
                   (SELECT count(*) FROM jobs j WHERE j.type = l.job_type AND j.status = 'RUNNING') AS running
            FROM job_type_limits l
            """;

    private final JdbcClient jdbc;

    public LimitRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void upsert(String type, int maxConcurrent) {
        jdbc.sql("""
                INSERT INTO job_type_limits (job_type, max_concurrent) VALUES (:type, :max)
                ON CONFLICT (job_type) DO UPDATE SET max_concurrent = EXCLUDED.max_concurrent, updated_at = now()
                """)
                .param("type", type)
                .param("max", maxConcurrent)
                .update();
    }

    public int delete(String type) {
        return jdbc.sql("DELETE FROM job_type_limits WHERE job_type = :type")
                .param("type", type)
                .update();
    }

    public List<TypeLimit> list() {
        return jdbc.sql(SELECT + " ORDER BY l.job_type")
                .query((rs, i) -> new TypeLimit(rs.getString("job_type"), rs.getInt("max_concurrent"), rs.getLong("running")))
                .list();
    }

    public Optional<TypeLimit> find(String type) {
        return jdbc.sql(SELECT + " WHERE l.job_type = :type")
                .param("type", type)
                .query((rs, i) -> new TypeLimit(rs.getString("job_type"), rs.getInt("max_concurrent"), rs.getLong("running")))
                .optional();
    }

    public long count() {
        return jdbc.sql("SELECT count(*) FROM job_type_limits").query(Long.class).single();
    }
}
