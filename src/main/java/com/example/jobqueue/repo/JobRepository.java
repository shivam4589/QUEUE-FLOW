package com.example.jobqueue.repo;

import com.example.jobqueue.domain.Job;
import com.example.jobqueue.domain.JobAttempt;
import com.example.jobqueue.domain.JobStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JobRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public JobRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------ API side

    public Job insert(UUID id, String type, JsonNode payload,
                      int priority, int delaySeconds, int maxAttempts) {
        return jdbc.sql("""
                INSERT INTO jobs (id, type, payload, status, priority, run_at, max_attempts)
                VALUES (:id, :type, CAST(:payload AS jsonb), 'PENDING', :priority,
                        now() + (:delay * INTERVAL '1 second'), :maxAttempts)
                RETURNING *
                """)
                .param("id", id)
                .param("type", type)
                .param("payload", payload.toString())
                .param("priority", priority)
                .param("delay", delaySeconds)
                .param("maxAttempts", maxAttempts)
                .query(this::map)
                .single();
    }

    /** Inserts a job created by a recurring schedule; it is due immediately. */
    public Job insertForSchedule(UUID id, UUID scheduleId, String type, JsonNode payload,
                                 int priority, int maxAttempts) {
        return jdbc.sql("""
                INSERT INTO jobs (id, type, payload, status, priority, run_at, max_attempts, schedule_id)
                VALUES (:id, :type, CAST(:payload AS jsonb), 'PENDING', :priority, now(),
                        :maxAttempts, :scheduleId)
                RETURNING *
                """)
                .param("id", id)
                .param("type", type)
                .param("payload", payload.toString())
                .param("priority", priority)
                .param("maxAttempts", maxAttempts)
                .param("scheduleId", scheduleId)
                .query(this::map)
                .single();
    }

    /** A job together with the fingerprint of the request that created it (used for idempotency checks). */
    public record StoredJob(Job job, String requestHash) {
    }

    /**
     * Inserts a job under an idempotency key. If the key already exists, nothing is inserted and the result
     * is empty. The unique index makes this safe when several requests with one key arrive at the same time:
     * exactly one inserts, the others see the conflict.
     */
    public Optional<Job> insertIdempotent(UUID id, String idempotencyKey, String requestHash, String type,
                                          JsonNode payload, int priority, int delaySeconds, int maxAttempts) {
        return jdbc.sql("""
                INSERT INTO jobs (id, type, payload, status, priority, run_at, max_attempts,
                                  idempotency_key, request_hash)
                VALUES (:id, :type, CAST(:payload AS jsonb), 'PENDING', :priority,
                        now() + (:delay * INTERVAL '1 second'), :maxAttempts, :key, :hash)
                ON CONFLICT (idempotency_key) WHERE idempotency_key IS NOT NULL DO NOTHING
                RETURNING *
                """)
                .param("id", id)
                .param("type", type)
                .param("payload", payload.toString())
                .param("priority", priority)
                .param("delay", delaySeconds)
                .param("maxAttempts", maxAttempts)
                .param("key", idempotencyKey)
                .param("hash", requestHash)
                .query(this::map)
                .optional();
    }

    public Optional<StoredJob> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.sql("SELECT * FROM jobs WHERE idempotency_key = :key")
                .param("key", idempotencyKey)
                .query((rs, i) -> new StoredJob(map(rs, i), rs.getString("request_hash")))
                .optional();
    }

    public Optional<Job> findById(UUID id) {
        return jdbc.sql("SELECT * FROM jobs WHERE id = :id")
                .param("id", id)
                .query(this::map)
                .optional();
    }

    public List<Job> list(JobStatus status, int limit) {
        if (status == null) {
            return jdbc.sql("SELECT * FROM jobs ORDER BY created_at DESC LIMIT :limit")
                    .param("limit", limit)
                    .query(this::map)
                    .list();
        }
        return jdbc.sql("""
                SELECT * FROM jobs WHERE status = :status
                ORDER BY created_at DESC LIMIT :limit
                """)
                .param("status", status.name())
                .param("limit", limit)
                .query(this::map)
                .list();
    }

    public List<JobAttempt> attemptsFor(UUID jobId) {
        return jdbc.sql("SELECT * FROM job_attempts WHERE job_id = :id ORDER BY id")
                .param("id", jobId)
                .query(this::mapAttempt)
                .list();
    }

    // --------------------------------------------------------------- worker side

    /**
     * Atomically claims the highest-priority due job for this worker.
     * FOR UPDATE SKIP LOCKED lets many workers poll at once: a row locked by another
     * worker is skipped instead of waited on, so no job is ever handed out twice.
     * Retries re-enter here too: a job waiting out its backoff has run_at in the future.
     */
    public Optional<Job> claimNext(String workerId) {
        return jdbc.sql("""
                UPDATE jobs
                SET status = 'RUNNING', locked_by = :worker, locked_at = now(),
                    attempts = attempts + 1, updated_at = now()
                WHERE id = (
                    SELECT id FROM jobs
                    WHERE status = 'PENDING' AND run_at <= now()
                    ORDER BY priority DESC, run_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                RETURNING *
                """)
                .param("worker", workerId)
                .query(this::map)
                .optional();
    }

    public long startAttempt(UUID jobId, String workerId) {
        return jdbc.sql("""
                INSERT INTO job_attempts (job_id, worker_id) VALUES (:jobId, :worker)
                RETURNING id
                """)
                .param("jobId", jobId)
                .param("worker", workerId)
                .query(Long.class)
                .single();
    }

    public void finishAttempt(long attemptId, boolean success, String error) {
        jdbc.sql("""
                UPDATE job_attempts SET finished_at = now(), success = :success, error = :error
                WHERE id = :id
                """)
                .param("success", success)
                .param("error", error)
                .param("id", attemptId)
                .update();
    }

    /**
     * The three transitions below only apply if this worker still holds the lock
     * (status RUNNING and locked_by = worker). If the reaper reclaimed the job in the
     * meantime, a late result from a slow worker is ignored and 0 rows are updated.
     */
    public int markSucceeded(UUID id, String workerId) {
        return markSucceeded(id, workerId, null);
    }

    /** Marks the job SUCCEEDED and stores the handler's result (null = no result). */
    public int markSucceeded(UUID id, String workerId, JsonNode result) {
        return jdbc.sql("""
                UPDATE jobs SET status = 'SUCCEEDED', last_error = NULL,
                    result = CAST(:result AS jsonb),
                    locked_by = NULL, locked_at = NULL, updated_at = now()
                WHERE id = :id AND status = 'RUNNING' AND locked_by = :worker
                """)
                .param("id", id)
                .param("worker", workerId)
                .param("result", result == null ? null : result.toString())
                .update();
    }

    /** Failure with attempts left: back to PENDING, but not due until the backoff has passed. */
    public int scheduleRetry(UUID id, String workerId, String error, double delaySeconds) {
        return jdbc.sql("""
                UPDATE jobs SET status = 'PENDING', last_error = :error,
                    run_at = now() + (:delay * INTERVAL '1 second'),
                    locked_by = NULL, locked_at = NULL, updated_at = now()
                WHERE id = :id AND status = 'RUNNING' AND locked_by = :worker
                """)
                .param("id", id)
                .param("worker", workerId)
                .param("error", error)
                .param("delay", delaySeconds)
                .update();
    }

    /** Failure with no attempts left: dead-letter state. The job stays in the table for inspection. */
    public int markDead(UUID id, String workerId, String error) {
        return jdbc.sql("""
                UPDATE jobs SET status = 'DEAD', last_error = :error,
                    locked_by = NULL, locked_at = NULL, updated_at = now()
                WHERE id = :id AND status = 'RUNNING' AND locked_by = :worker
                """)
                .param("id", id)
                .param("worker", workerId)
                .param("error", error)
                .update();
    }

    /**
     * Recovers jobs whose worker vanished (crash, kill, network partition): RUNNING jobs whose
     * lock is older than the timeout go back to PENDING, or to DEAD if they used all attempts.
     * One atomic statement; SKIP LOCKED makes it safe for several reapers to run at once.
     * Returns how many jobs were reclaimed.
     */
    public int reapStuckJobs(double lockTimeoutSeconds) {
        return jdbc.sql("""
                WITH stuck AS (
                    SELECT id FROM jobs
                    WHERE status = 'RUNNING'
                      AND locked_at < now() - (:timeout * INTERVAL '1 second')
                    FOR UPDATE SKIP LOCKED
                ), closed AS (
                    UPDATE job_attempts
                    SET finished_at = now(), success = FALSE, error = 'Worker lost: lock expired'
                    WHERE finished_at IS NULL AND job_id IN (SELECT id FROM stuck)
                )
                UPDATE jobs
                SET status = CASE WHEN cancel_requested THEN 'CANCELLED'
                                  WHEN attempts >= max_attempts THEN 'DEAD' ELSE 'PENDING' END,
                    last_error = 'Worker lost: lock expired (locked by '
                                 || COALESCE(locked_by, 'unknown') || ')',
                    run_at = now(), locked_by = NULL, locked_at = NULL, updated_at = now()
                WHERE id IN (SELECT id FROM stuck)
                """)
                .param("timeout", lockTimeoutSeconds)
                .update();
    }

    // ------------------------------------------------------ dashboard / admin side

    public record RecentStats(long succeededLastMinute, Double oldestDuePendingSeconds) {
    }

    public List<Job> list(JobStatus status, String type, int limit, int offset) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = buildWhere(status, type, params);
        return jdbc.sql("SELECT * FROM jobs" + where
                        + " ORDER BY created_at DESC, id LIMIT :limit OFFSET :offset")
                .params(params)
                .param("limit", limit)
                .param("offset", offset)
                .query(this::map)
                .list();
    }

    public long count(JobStatus status, String type) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = buildWhere(status, type, params);
        return jdbc.sql("SELECT count(*) FROM jobs" + where)
                .params(params)
                .query(Long.class)
                .single();
    }

    // only constant SQL fragments are appended here; every value goes through a bind parameter
    private static String buildWhere(JobStatus status, String type, Map<String, Object> params) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (status != null) {
            where.append(" AND status = :status");
            params.put("status", status.name());
        }
        if (type != null && !type.isBlank()) {
            where.append(" AND type = :type");
            params.put("type", type);
        }
        return where.toString();
    }

    /**
     * Cancels a job only while it is still PENDING. If a worker claims it at the same moment,
     * the row lock makes this UPDATE re-check the status and match nothing (the caller gets a 409).
     */
    public Optional<Job> cancelIfPending(UUID id) {
        return jdbc.sql("""
                UPDATE jobs SET status = 'CANCELLED', updated_at = now()
                WHERE id = :id AND status = 'PENDING'
                RETURNING *
                """)
                .param("id", id)
                .query(this::map)
                .optional();
    }

    /** DEAD (or legacy FAILED) job back to PENDING with a fresh attempt budget; history stays in job_attempts. */
    public Optional<Job> requeueIfDead(UUID id) {
        return jdbc.sql("""
                UPDATE jobs SET status = 'PENDING', attempts = 0, cancel_requested = FALSE, run_at = now(),
                    locked_by = NULL, locked_at = NULL, updated_at = now()
                WHERE id = :id AND status IN ('DEAD', 'FAILED')
                RETURNING *
                """)
                .param("id", id)
                .query(this::map)
                .optional();
    }

    /** Job count per status, with 0 for statuses that currently have no jobs. */
    public Map<String, Long> statusCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (JobStatus s : JobStatus.values()) {
            counts.put(s.name(), 0L);
        }
        jdbc.sql("SELECT status, count(*) AS n FROM jobs GROUP BY status")
                .query((rs, i) -> Map.entry(rs.getString("status"), rs.getLong("n")))
                .list()
                .forEach(e -> counts.put(e.getKey(), e.getValue()));
        return counts;
    }

    public RecentStats recentStats() {
        return jdbc.sql("""
                SELECT count(*) FILTER (WHERE status = 'SUCCEEDED'
                                          AND updated_at > now() - INTERVAL '1 minute') AS succeeded_last_minute,
                       EXTRACT(EPOCH FROM (now() - min(run_at)
                                 FILTER (WHERE status = 'PENDING' AND run_at <= now()))) AS oldest_due_pending_seconds
                FROM jobs
                """)
                .query((rs, i) -> {
                    long succeeded = rs.getLong("succeeded_last_minute");
                    double oldest = rs.getDouble("oldest_due_pending_seconds");
                    boolean nothingWaiting = rs.wasNull(); // wasNull() only refers to the column read just before it
                    return new RecentStats(succeeded, nothingWaiting ? null : oldest);
                })
                .single();
    }

    // ------------------------------------------------ heartbeat, wake-ups, retention

    /**
     * Heartbeat: the worker that is running this job proves it is still alive by refreshing the lock time.
     * Returns 0 if the job is no longer this worker's (for example the reaper already reclaimed it).
     */
    public int touchLock(UUID id, String workerId) {
        return jdbc.sql("""
                UPDATE jobs SET locked_at = now()
                WHERE id = :id AND status = 'RUNNING' AND locked_by = :worker
                """)
                .param("id", id)
                .param("worker", workerId)
                .update();
    }

    /** When the earliest waiting job becomes due, or empty if nothing is waiting. */
    public Optional<Instant> nextPendingRunAt() {
        List<Instant> result = jdbc.sql("SELECT min(run_at) FROM jobs WHERE status = 'PENDING'")
                .query((rs, i) -> {
                    Timestamp ts = rs.getTimestamp(1);
                    return ts == null ? null : ts.toInstant();
                })
                .list();
        return result.isEmpty() ? Optional.empty() : Optional.ofNullable(result.get(0));
    }

    /**
     * Deletes up to batchSize finished jobs that are older than the retention limits (their attempt history goes
     * with them). SUCCEEDED jobs expire after succeededDays; DEAD / CANCELLED jobs after otherDays.
     * Jobs that are still PENDING or RUNNING are never touched.
     */
    public int deleteExpired(int succeededDays, int otherDays, int batchSize) {
        return jdbc.sql("""
                DELETE FROM jobs
                WHERE id IN (
                    SELECT id FROM jobs
                    WHERE (status = 'SUCCEEDED' AND updated_at < now() - (:succeededDays * INTERVAL '1 day'))
                       OR (status IN ('DEAD', 'CANCELLED', 'FAILED')
                           AND updated_at < now() - (:otherDays * INTERVAL '1 day'))
                    ORDER BY updated_at
                    LIMIT :batch
                    FOR UPDATE SKIP LOCKED
                )
                """)
                .param("succeededDays", succeededDays)
                .param("otherDays", otherDays)
                .param("batch", batchSize)
                .update();
    }

    // ------------------------------------------------ cancelling running jobs

    /** Asks for a RUNNING job to be stopped. The owning worker notices on its next heartbeat. */
    public Optional<Job> requestCancelIfRunning(UUID id) {
        return jdbc.sql("""
                UPDATE jobs SET cancel_requested = TRUE, updated_at = now()
                WHERE id = :id AND status = 'RUNNING'
                RETURNING *
                """)
                .param("id", id)
                .query(this::map)
                .optional();
    }

    /**
     * Heartbeat that also reports whether cancellation was requested. Empty means the worker no longer owns the
     * job; otherwise the value is the cancel_requested flag.
     */
    public Optional<Boolean> heartbeat(UUID id, String workerId) {
        List<Boolean> result = jdbc.sql("""
                UPDATE jobs SET locked_at = now()
                WHERE id = :id AND status = 'RUNNING' AND locked_by = :worker
                RETURNING cancel_requested
                """)
                .param("id", id)
                .param("worker", workerId)
                .query(Boolean.class)
                .list();
        return result.isEmpty() ? Optional.empty() : Optional.of(result.get(0));
    }

    /** The worker stopped the job because cancellation was requested. */
    public int markCancelled(UUID id, String workerId, String note) {
        return jdbc.sql("""
                UPDATE jobs SET status = 'CANCELLED', last_error = :note,
                    locked_by = NULL, locked_at = NULL, updated_at = now()
                WHERE id = :id AND status = 'RUNNING' AND locked_by = :worker
                """)
                .param("id", id)
                .param("worker", workerId)
                .param("note", note)
                .update();
    }

    /** Jobs per type, most common first (used by the report job). */
    public Map<String, Long> countsByType() {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.sql("SELECT type, count(*) AS n FROM jobs GROUP BY type ORDER BY n DESC, type LIMIT 20")
                .query((rs, i) -> Map.entry(rs.getString("type"), rs.getLong("n")))
                .list()
                .forEach(e -> counts.put(e.getKey(), e.getValue()));
        return counts;
    }

    // ------------------------------------------------------------------- mapping

    private Job map(ResultSet rs, int rowNum) throws SQLException {
        try {
            String resultJson = rs.getString("result");
            JsonNode result = resultJson == null ? null : mapper.readTree(resultJson);
            return new Job(
                    rs.getObject("id", UUID.class),
                    rs.getString("type"),
                    mapper.readTree(rs.getString("payload")),
                    JobStatus.valueOf(rs.getString("status")),
                    rs.getInt("priority"),
                    rs.getTimestamp("run_at").toInstant(),
                    rs.getInt("attempts"),
                    rs.getInt("max_attempts"),
                    rs.getString("last_error"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant(),
                    rs.getObject("schedule_id", UUID.class),
                    rs.getString("idempotency_key"),
                    rs.getBoolean("cancel_requested"),
                    result);
        } catch (JsonProcessingException e) {
            throw new SQLException("Invalid JSON payload in database", e);
        }
    }

    private JobAttempt mapAttempt(ResultSet rs, int rowNum) throws SQLException {
        Timestamp finished = rs.getTimestamp("finished_at");
        return new JobAttempt(
                rs.getLong("id"),
                rs.getObject("job_id", UUID.class),
                rs.getString("worker_id"),
                rs.getTimestamp("started_at").toInstant(),
                finished == null ? null : finished.toInstant(),
                rs.getObject("success", Boolean.class),
                rs.getString("error"));
    }
}
