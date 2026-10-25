package com.example.jobqueue.api;

import com.example.jobqueue.apikey.ApiKey;
import com.example.jobqueue.apikey.ApiKeyFilter;
import com.example.jobqueue.domain.Job;
import com.example.jobqueue.domain.JobAttempt;
import com.example.jobqueue.domain.JobStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService service;

    public JobController(JobService service) {
        this.service = service;
    }

    /**
     * Optional Idempotency-Key header. First request: 201 Created. The same key with the same request again:
     * 200 OK with the original job and "Idempotent-Replayed: true". The same key with a different request: 422.
     *
     * <p>If API key authentication is active, the resolved {@code clientId} is stored on the job.
     */
    @PostMapping
    public ResponseEntity<Job> submit(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody SubmitJobRequest request,
            HttpServletRequest httpRequest) {
        String clientId = resolveClientId(httpRequest);
        JobService.SubmitResult result = service.submit(request, idempotencyKey, clientId);
        Job job = result.job();
        URI location = URI.create("/api/jobs/" + job.id());
        if (result.created()) {
            return ResponseEntity.created(location).body(job);
        }
        return ResponseEntity.ok().location(location).header("Idempotent-Replayed", "true").body(job);
    }

    @GetMapping("/{id}")
    public Job get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping("/{id}/attempts")
    public List<JobAttempt> attempts(@PathVariable UUID id) {
        return service.attempts(id);
    }

    /**
     * Cancel a job. PENDING: cancelled immediately (200). RUNNING: cancellation is requested and the worker stops
     * the job at its next heartbeat (202, the job still shows RUNNING with cancelRequested = true).
     * 404 if unknown, 409 if the job already finished.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Job> cancel(@PathVariable UUID id) {
        JobService.CancelResult result = service.cancel(id);
        return result.immediate()
                ? ResponseEntity.ok(result.job())
                : ResponseEntity.accepted().body(result.job());
    }

    /** Re-queue a DEAD job. 404 if unknown, 409 if it is not DEAD. */
    @PostMapping("/{id}/retry")
    public Job retry(@PathVariable UUID id) {
        return service.retry(id);
    }

    /** The body stays a plain array; the total number of matches is in the X-Total-Count header. */
    @GetMapping
    public ResponseEntity<List<Job>> list(@RequestParam(required = false) JobStatus status,
                                          @RequestParam(required = false) String type,
                                          @RequestParam(required = false) String clientId,
                                          @RequestParam(defaultValue = "50") int limit,
                                          @RequestParam(defaultValue = "0") int offset) {
        JobService.PagedJobs page = service.list(status, type, clientId, limit, offset);
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(page.total()))
                .body(page.items());
    }

    /** Reads the resolved API key from the request attribute set by {@link ApiKeyFilter}. */
    private static String resolveClientId(HttpServletRequest request) {
        Object attr = request.getAttribute(ApiKeyFilter.ATTR_API_KEY);
        return attr instanceof ApiKey key ? key.clientId() : null;
    }
}
