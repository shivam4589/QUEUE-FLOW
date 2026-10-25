package com.example.jobqueue.worker;

import com.example.jobqueue.domain.Job;
import com.example.jobqueue.repo.JobRepository;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Worker: N threads, each repeatedly claims one job and runs its handler.
 * Delivery is at-least-once: a job can run again if its worker dies, so handlers should be idempotent.
 * Only created when jobqueue.worker.enabled=true, so the API container stays a pure API.
 *
 * When nothing is waiting, a thread sleeps until (a) a LISTEN/NOTIFY wake-up arrives, (b) the earliest waiting job
 * becomes due (retries and delayed jobs), or (c) a safety timeout passes. Without a LISTEN connection it simply
 * polls every poll-interval-ms.
 *
 * Metrics (Micrometer, exported to Prometheus):
 *   jobqueue.job.duration    timer, time inside the handler, tags: type, outcome (success / failure)
 *   jobqueue.job.queue.wait  timer, time between a job becoming due and a worker picking it up
 *   jobqueue.job.retries     counter, failed attempts that were scheduled for retry
 *   jobqueue.job.dead        counter, jobs that ran out of attempts
 */
@Component
@ConditionalOnProperty(name = "jobqueue.worker.enabled", havingValue = "true")
public class WorkerLoop {

    private static final Logger log = LoggerFactory.getLogger(WorkerLoop.class);
    private static final int MAX_ERROR_LENGTH = 2000;
    private static final long MIN_IDLE_WAIT_MS = 100;

    private final JobRepository repository;
    private final JobHandlerRegistry registry;
    private final WorkerProperties props;
    private final BackoffPolicy backoff;
    private final MeterRegistry meters;
    private final WakeSignal wake;
    private final JobNotifier notifier;
    private final InFlightJobs inFlight;
    private final ConcurrencyLimits concurrencyLimits;

    private ExecutorService executor;
    private volatile boolean running;

    public WorkerLoop(JobRepository repository, JobHandlerRegistry registry, WorkerProperties props,
                      MeterRegistry meters, WakeSignal wake, JobNotifier notifier, InFlightJobs inFlight) {
        this.repository = repository;
        this.registry = registry;
        this.props = props;
        this.backoff = new BackoffPolicy(props.backoffBaseSeconds(), props.backoffMaxSeconds());
        this.meters = meters;
        this.wake = wake;
        this.notifier = notifier;
        this.inFlight = inFlight;
        this.concurrencyLimits = new ConcurrencyLimits(props.typeConcurrency());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        running = true;
        int threads = Math.max(1, props.concurrency());
        String prefix = hostName() + "-" + UUID.randomUUID().toString().substring(0, 8);
        executor = Executors.newFixedThreadPool(threads);
        for (int i = 1; i <= threads; i++) {
            String workerId = prefix + "-t" + i;
            executor.submit(() -> runLoop(workerId));
        }
        log.info("Worker started: {} thread(s), poll interval {} ms, type-concurrency limits: {}",
                threads, props.pollIntervalMs(),
                concurrencyLimits.isEmpty() ? "none" : concurrencyLimits.asMap());
    }

    @PreDestroy
    public void stop() {
        running = false;
        wake.signal(); // let idle threads notice immediately
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Worker stopped");
    }

    private void runLoop(String workerId) {
        while (running) {
            try {
                long seen = wake.generation(); // read BEFORE looking for work so a wake-up cannot be missed
                Optional<Job> claimed = repository.claimNext(workerId, concurrencyLimits);
                if (claimed.isPresent()) {
                    execute(claimed.get(), workerId);
                } else {
                    idleWait(seen);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // e.g. database briefly unavailable: log and back off, never kill the thread
                log.error("Worker {} poll error: {}", workerId, e.toString());
                try {
                    Thread.sleep(Math.max(1000, props.pollIntervalMs()));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void idleWait(long seenGeneration) throws InterruptedException {
        long waitMs = props.pollIntervalMs();
        if (notifier.isListening()) {
            // woken by NOTIFY; otherwise sleep until the earliest waiting job is due, or the safety timeout
            waitMs = Math.max(props.pollIntervalMs(), props.listenSafetyPollMs());
            Optional<Instant> nextDue = repository.nextPendingRunAt();
            if (nextDue.isPresent()) {
                long untilDue = Duration.between(Instant.now(), nextDue.get()).toMillis();
                waitMs = Math.min(waitMs, Math.max(MIN_IDLE_WAIT_MS, untilDue));
            }
        }
        wake.awaitChange(seenGeneration, waitMs);
    }

    private void execute(Job job, String workerId) {
        // from now on the heartbeat keeps this job's lock fresh and can interrupt this thread to cancel the job
        InFlightJobs.Entry entry = inFlight.add(job.id(), workerId);
        try {
            recordQueueWait(job);
            long attemptId = repository.startAttempt(job.id(), workerId);

            Timer.Sample sample = Timer.start(meters);
            Exception handlerError = null;
            JsonNode handlerResult = null;
            JobHandler handler = null;
            try {
                handler = registry.find(job.type()).orElseThrow(
                        () -> new IllegalStateException("No handler registered for job type " + job.type()));
                handlerResult = handler.handle(job);
            } catch (Exception e) {
                handlerError = e;
            }

            boolean cancelRequested = entry.cancelRequested();
            if (cancelRequested) {
                Thread.interrupted(); // the interrupt was aimed at the job; it must not leak into this worker thread
            } else if (handlerError instanceof InterruptedException) {
                Thread.currentThread().interrupt(); // a real shutdown interrupt: let the loop see it
            }

            // Measure before touching the database, so anything that sees the new job status also sees the measurement.
            sample.stop(durationTimer(job.type(), handlerError == null ? "success" : "failure"));

            if (handlerError == null) {
                // finished normally, even if cancellation arrived in the meantime: the work is done
                repository.finishAttempt(attemptId, true, null);
                if (repository.markSucceeded(job.id(), workerId, handlerResult) == 0) {
                    log.warn("Job {} finished but this worker no longer holds its lock (reclaimed by the reaper?)",
                            job.id());
                } else {
                    log.info("Job {} ({}) succeeded on attempt {} by {}",
                            job.id(), job.type(), job.attempts(), workerId);
                }
            } else if (cancelRequested) {
                String note = handlerError instanceof InterruptedException
                        ? "Cancelled on request"
                        : "Cancelled on request (the handler failed while stopping: "
                                + truncate(handlerError.toString()) + ")";
                repository.finishAttempt(attemptId, false, note);
                if (repository.markCancelled(job.id(), workerId, note) > 0) {
                    meters.counter("jobqueue.job.cancelled", "type", job.type()).increment();
                    log.info("Job {} ({}) was cancelled while running on {}", job.id(), job.type(), workerId);
                }
            } else {
                handleFailure(job, workerId, attemptId, truncate(handlerError.toString()),
                        handler, handlerError);
            }
        } finally {
            inFlight.remove(job.id());
            if (running) {
                Thread.interrupted(); // clear any interrupt that was still aimed at this job
            }
        }
    }

    private void handleFailure(Job job, String workerId, long attemptId, String error,
                               JobHandler handler, Exception lastException) {
        repository.finishAttempt(attemptId, false, error);
        if (job.attempts() >= job.maxAttempts()) {
            JsonNode errorDetail = handler != null ? handler.errorDetail(job, lastException) : null;
            repository.markDead(job.id(), workerId, error, errorDetail);
            meters.counter("jobqueue.job.dead", "type", job.type()).increment();
            log.error("Job {} ({}) is DEAD after {} attempts: {}",
                    job.id(), job.type(), job.attempts(), error);
        } else {
            double delay = backoff.delaySeconds(job.attempts());
            repository.scheduleRetry(job.id(), workerId, error, delay);
            meters.counter("jobqueue.job.retries", "type", job.type()).increment();
            log.warn("Job {} ({}) failed attempt {}/{}, retrying in {}s: {}",
                    job.id(), job.type(), job.attempts(), job.maxAttempts(),
                    String.format("%.1f", delay), error);
        }
    }

    private Timer durationTimer(String type, String outcome) {
        return Timer.builder("jobqueue.job.duration")
                .description("Time spent inside the job handler")
                .tag("type", type)
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofMinutes(5))
                .register(meters);
    }

    /** How long the job sat in the queue after it became due (clock differences can make this slightly negative). */
    private void recordQueueWait(Job job) {
        Duration wait = Duration.between(job.runAt(), Instant.now());
        if (wait.isNegative()) {
            wait = Duration.ZERO;
        }
        Timer.builder("jobqueue.job.queue.wait")
                .description("Time between a job becoming due and a worker picking it up")
                .tag("type", job.type())
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofMinutes(10))
                .register(meters)
                .record(wait);
    }

    private static String truncate(String s) {
        return s.length() <= MAX_ERROR_LENGTH ? s : s.substring(0, MAX_ERROR_LENGTH);
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "worker";
        }
    }
}
