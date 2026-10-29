package com.example.jobqueue.schedule;

import com.example.jobqueue.api.JobStateConflictException;
import com.example.jobqueue.domain.Job;
import com.example.jobqueue.repo.JobRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ScheduleService {

    private static final int DUE_BATCH = 20;

    private final ScheduleRepository schedules;
    private final JobRepository jobs;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;

    public ScheduleService(ScheduleRepository schedules, JobRepository jobs,
                           TransactionTemplate tx, ObjectMapper mapper) {
        this.schedules = schedules;
        this.jobs = jobs;
        this.tx = tx;
        this.mapper = mapper;
    }

    public Schedule create(CreateScheduleRequest req) {
        String cron = CronSupport.normalize(req.cron());
        String timezone = (req.timezone() == null || req.timezone().isBlank()) ? "UTC" : req.timezone().trim();
        CronSupport.validate(cron, timezone);
        Instant next = CronSupport.next(cron, timezone, Instant.now());
        JsonNode payload = req.payload() != null ? req.payload() : mapper.createObjectNode();
        String name = req.name().trim();
        try {
            return schedules.insert(UUID.randomUUID(), name, req.type().trim(), payload,
                    req.priority() != null ? req.priority() : 5,
                    req.maxAttempts() != null ? req.maxAttempts() : 5,
                    cron, timezone, req.enabled() == null || req.enabled(), next);
        } catch (DuplicateKeyException e) {
            throw new JobStateConflictException("A schedule named '" + name + "' already exists");
        }
    }

    public Schedule get(UUID id) {
        return schedules.findById(id).orElseThrow(() -> new ScheduleNotFoundException(id));
    }

    public List<Schedule> list() {
        return schedules.list();
    }

    /** The most recent jobs this schedule created (newest first), with their status and result. */
    public List<Job> runs(UUID id, int limit) {
        get(id); // 404 if the schedule does not exist
        return jobs.listByScheduleId(id, Math.min(Math.max(limit, 1), 50));
    }

    public Schedule pause(UUID id) {
        return schedules.pause(id).orElseThrow(() -> new ScheduleNotFoundException(id));
    }

    public Schedule resume(UUID id) {
        Schedule schedule = get(id);
        Instant next = CronSupport.next(schedule.cron(), schedule.timezone(), Instant.now());
        if (next == null) {
            throw new InvalidScheduleException("This cron expression never fires again");
        }
        return schedules.resume(id, next).orElseThrow(() -> new ScheduleNotFoundException(id));
    }

    /** Deletes the schedule; jobs it already created stay (their schedule link is cleared). */
    public Schedule delete(UUID id) {
        Schedule schedule = get(id);
        schedules.delete(id);
        return schedule;
    }

    /** Runs the schedule's job right now. The schedule's own timing is not affected. */
    public Job triggerNow(UUID id) {
        Schedule s = get(id);
        return jobs.insertForSchedule(UUID.randomUUID(), s.id(), s.type(), s.payload(), s.priority(), s.maxAttempts());
    }

    /**
     * One scheduler tick: for every due schedule, create its job and move next_run_at forward, in ONE
     * transaction. Several worker containers can tick at the same time; SKIP LOCKED lets only one of them
     * handle a given schedule, and the transaction guarantees a job is never created without the
     * schedule advancing (or the other way round).
     * Missed runs (workers were down) are not replayed: one job is created and the next run is the
     * first occurrence after now.
     */
    public int fireDueSchedules() {
        Integer fired = tx.execute(status -> {
            List<Schedule> due = schedules.lockDue(DUE_BATCH);
            Instant now = Instant.now();
            for (Schedule s : due) {
                Job job = jobs.insertForSchedule(UUID.randomUUID(), s.id(), s.type(), s.payload(),
                        s.priority(), s.maxAttempts());
                // never compute the next run from before the occurrence that just fired, even if clocks differ slightly
                Instant after = now.isAfter(s.nextRunAt()) ? now : s.nextRunAt();
                Instant next = CronSupport.next(s.cron(), s.timezone(), after);
                if (next == null) {
                    schedules.markFiredAndDisable(s.id(), job.id());
                } else {
                    schedules.markFired(s.id(), job.id(), next);
                }
            }
            return due.size();
        });
        return fired == null ? 0 : fired;
    }
}
