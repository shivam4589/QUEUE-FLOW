package com.example.jobqueue.worker;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * jobqueue.worker.enabled               run polling worker threads (reaper, scheduler, heartbeat too) in this process
 * jobqueue.worker.concurrency           number of polling threads
 * jobqueue.worker.poll-interval-ms      sleep between polls when the queue is empty (used while LISTEN is not connected)
 * jobqueue.worker.backoff-base-seconds  first retry delay; doubles with every failed attempt
 * jobqueue.worker.backoff-max-seconds   cap for the retry delay
 * jobqueue.worker.lock-timeout-seconds  a RUNNING job whose lock was not refreshed for this long is considered
 *                                       abandoned (the heartbeat refreshes it, so this can be short)
 * jobqueue.worker.reaper-interval-ms    how often the reaper looks for abandoned jobs
 * jobqueue.worker.scheduler-interval-ms how often recurring schedules are checked for due runs
 * jobqueue.worker.heartbeat-interval-ms how often a worker refreshes the lock of the jobs it is running;
 *                                       keep it well below half of the lock timeout
 * jobqueue.worker.listen-enabled        wake workers through PostgreSQL LISTEN/NOTIFY instead of waiting for the poll
 * jobqueue.worker.listen-safety-poll-ms longest an idle worker sleeps while LISTEN is connected (a safety net)
 */
@ConfigurationProperties(prefix = "jobqueue.worker")
public record WorkerProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("2") int concurrency,
        @DefaultValue("500") long pollIntervalMs,
        @DefaultValue("2") long backoffBaseSeconds,
        @DefaultValue("300") long backoffMaxSeconds,
        @DefaultValue("120") long lockTimeoutSeconds,
        @DefaultValue("15000") long reaperIntervalMs,
        @DefaultValue("5000") long schedulerIntervalMs,
        @DefaultValue("10000") long heartbeatIntervalMs,
        @DefaultValue("true") boolean listenEnabled,
        @DefaultValue("5000") long listenSafetyPollMs) {
}
