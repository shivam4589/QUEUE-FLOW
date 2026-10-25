package com.example.jobqueue.api;

import com.example.jobqueue.domain.Job;
import com.example.jobqueue.domain.JobAttempt;
import com.example.jobqueue.domain.JobStats;
import com.example.jobqueue.domain.JobStatus;
import com.example.jobqueue.repo.JobRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class JobService {

    private static final int DEFAULT_PRIORITY = 5;
    private static final int DEFAULT_MAX_ATTEMPTS = 5;
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:~-]{1,200}");

    private final JobRepository repository;
    /** Writes JSON with object keys sorted, so the same request always produces the same bytes. */
    private final ObjectMapper canonicalMapper;

    public JobService(JobRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.canonicalMapper = mapper.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public record PagedJobs(List<Job> items, long total) {
    }

    /** created = false means an earlier request with the same idempotency key already made this job. */
    public record SubmitResult(Job job, boolean created) {
    }

    /** immediate = the job was waiting and is cancelled now; otherwise cancellation was requested from its worker. */
    public record CancelResult(Job job, boolean immediate) {
    }

    public SubmitResult submit(SubmitJobRequest req, String idempotencyKey, String clientId) {
        int priority = req.priority() != null ? req.priority() : DEFAULT_PRIORITY;
        int delaySeconds = req.delaySeconds() != null ? req.delaySeconds() : 0;
        int maxAttempts = req.maxAttempts() != null ? req.maxAttempts() : DEFAULT_MAX_ATTEMPTS;
        UUID id = UUID.randomUUID();

        String key = normalizeKey(idempotencyKey);
        if (key == null) {
            return new SubmitResult(
                    repository.insert(id, req.type(), req.payload(), priority, delaySeconds, maxAttempts, clientId), true);
        }

        String hash = fingerprint(req.type(), req.payload(), priority, delaySeconds, maxAttempts);
        Optional<Job> created = repository.insertIdempotent(
                id, key, hash, req.type(), req.payload(), priority, delaySeconds, maxAttempts, clientId);
        if (created.isPresent()) {
            return new SubmitResult(created.get(), true);
        }

        // The key exists already: same request -> hand back the original job, different request -> refuse.
        JobRepository.StoredJob existing = repository.findByIdempotencyKey(key)
                .orElseThrow(() -> new IllegalStateException("Idempotency key disappeared: " + key));
        if (!existing.requestHash().equals(hash)) {
            throw new IdempotencyKeyReuseException(key);
        }
        return new SubmitResult(existing.job(), false);
    }

    /** Backwards-compatible overload for internal callers (e.g. schedule runner) that have no clientId. */
    public SubmitResult submit(SubmitJobRequest req, String idempotencyKey) {
        return submit(req, idempotencyKey, null);
    }

    public Job get(UUID id) {
        return repository.findById(id).orElseThrow(() -> new JobNotFoundException(id));
    }

    public PagedJobs list(JobStatus status, String type, String clientId, int limit, int offset) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        int safeOffset = Math.max(offset, 0);
        List<Job> items = repository.list(status, type, clientId, safeLimit, safeOffset);
        return new PagedJobs(items, repository.count(status, type, clientId));
    }

    /** Backwards-compatible overload without clientId filter. */
    public PagedJobs list(JobStatus status, String type, int limit, int offset) {
        return list(status, type, null, limit, offset);
    }

    public List<JobAttempt> attempts(UUID id) {
        get(id); // 404 if the job does not exist
        return repository.attemptsFor(id);
    }

    /**
     * A waiting (PENDING) job is cancelled at once. A RUNNING job is flagged, and its worker stops it at the next
     * heartbeat (cooperative cancellation). Finished jobs cannot be cancelled.
     */
    public CancelResult cancel(UUID id) {
        Optional<Job> cancelled = repository.cancelIfPending(id);
        if (cancelled.isPresent()) {
            return new CancelResult(cancelled.get(), true);
        }
        Optional<Job> requested = repository.requestCancelIfRunning(id);
        if (requested.isPresent()) {
            return new CancelResult(requested.get(), false);
        }
        throw notFoundOrConflict(id, "cancelled", "PENDING or RUNNING");
    }

    /** Puts a DEAD job back in the queue with a fresh attempt budget. Its attempt history is kept. */
    public Job retry(UUID id) {
        return repository.requeueIfDead(id)
                .orElseThrow(() -> notFoundOrConflict(id, "retried", "DEAD"));
    }

    public JobStats stats() {
        Map<String, Long> byStatus = repository.statusCounts();
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        JobRepository.RecentStats recent = repository.recentStats();
        return new JobStats(new LinkedHashMap<>(byStatus), total,
                recent.succeededLastMinute(), recent.oldestDuePendingSeconds());
    }

    /** A blank or missing header means "no idempotency"; a malformed one is rejected. */
    private static String normalizeKey(String raw) {
        if (raw == null) {
            return null;
        }
        String key = raw.trim();
        if (key.isEmpty()) {
            return null;
        }
        if (!IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new InvalidIdempotencyKeyException();
        }
        return key;
    }

    /**
     * SHA-256 of the request after defaults are applied and object keys are sorted, so
     * {"a":1,"b":2} and {"b":2,"a":1} count as the same request, and so does omitting priority versus sending 5.
     */
    private String fingerprint(String type, JsonNode payload, int priority, int delaySeconds, int maxAttempts) {
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("type", type);
            request.put("payload", canonicalMapper.treeToValue(payload, Object.class));
            request.put("priority", priority);
            request.put("delaySeconds", delaySeconds);
            request.put("maxAttempts", maxAttempts);
            byte[] json = canonicalMapper.writeValueAsBytes(request);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot fingerprint request", e);
        }
    }

    private RuntimeException notFoundOrConflict(UUID id, String action, String requiredStatus) {
        return repository.findById(id)
                .<RuntimeException>map(job -> new JobStateConflictException(
                        "Job " + id + " is " + job.status() + "; only " + requiredStatus
                                + " jobs can be " + action))
                .orElseGet(() -> new JobNotFoundException(id));
    }
}
