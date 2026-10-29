package com.example.jobqueue.schedule;

import com.example.jobqueue.domain.Job;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {

    private final ScheduleService service;

    public ScheduleController(ScheduleService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Schedule> create(@Valid @RequestBody CreateScheduleRequest request) {
        Schedule schedule = service.create(request);
        return ResponseEntity.created(URI.create("/api/schedules/" + schedule.id())).body(schedule);
    }

    @GetMapping
    public List<Schedule> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public Schedule get(@PathVariable UUID id) {
        return service.get(id);
    }

    /** Recent runs of a schedule: the jobs it created, newest first (limit 1-50, default 10). */
    @GetMapping("/{id}/runs")
    public List<Job> runs(@PathVariable UUID id, @RequestParam(defaultValue = "10") int limit) {
        return service.runs(id, limit);
    }

    @PostMapping("/{id}/pause")
    public Schedule pause(@PathVariable UUID id) {
        return service.pause(id);
    }

    @PostMapping("/{id}/resume")
    public Schedule resume(@PathVariable UUID id) {
        return service.resume(id);
    }

    /** Creates one job from the schedule right now, without changing the schedule's timing. */
    @PostMapping("/{id}/trigger")
    public Job trigger(@PathVariable UUID id) {
        return service.triggerNow(id);
    }

    @DeleteMapping("/{id}")
    public Schedule delete(@PathVariable UUID id) {
        return service.delete(id);
    }
}
