package com.example.jobqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.jobqueue.domain.Job;
import com.example.jobqueue.domain.JobStatus;
import com.example.jobqueue.repo.JobRepository;
import com.example.jobqueue.schedule.CreateScheduleRequest;
import com.example.jobqueue.schedule.Schedule;
import com.example.jobqueue.schedule.ScheduleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** A schedule lists the jobs it created, newest first, including their results. */
@Testcontainers
@SpringBootTest(properties = {
        "jobqueue.worker.enabled=true",
        "jobqueue.worker.concurrency=2",
        "jobqueue.worker.poll-interval-ms=100"
})
@AutoConfigureMockMvc
class ScheduleRunsTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    ScheduleService schedules;

    @Autowired
    JobRepository jobs;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    private Schedule newSchedule(String name) {
        // disabled, so the scheduler never fires it by itself; the test triggers runs explicitly
        return schedules.create(new CreateScheduleRequest(name, "SEND_EMAIL",
                mapper.createObjectNode().put("to", "ops@example.com"), "0 0 * * *", "UTC", null, null, false));
    }

    private void awaitSucceeded(Job job) {
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(
                () -> jobs.findById(job.id()).map(j -> j.status() == JobStatus.SUCCEEDED).orElse(false));
    }

    @Test
    void aScheduleListsItsRunsNewestFirstWithTheirResults() throws Exception {
        Schedule schedule = newSchedule("runs-list");
        Job first = schedules.triggerNow(schedule.id());
        Job second = schedules.triggerNow(schedule.id());
        awaitSucceeded(first);
        awaitSucceeded(second);

        mvc.perform(get("/api/schedules/" + schedule.id() + "/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(second.id().toString()))
                .andExpect(jsonPath("$[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$[0].scheduleId").value(schedule.id().toString()))
                .andExpect(jsonPath("$[0].result.to").value("ops@example.com"));
    }

    @Test
    void theLimitParameterCapsTheList() throws Exception {
        Schedule schedule = newSchedule("runs-limit");
        for (int i = 0; i < 3; i++) {
            schedules.triggerNow(schedule.id());
        }

        mvc.perform(get("/api/schedules/" + schedule.id() + "/runs").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void runsOfAnUnknownScheduleAreNotFound() throws Exception {
        mvc.perform(get("/api/schedules/" + UUID.randomUUID() + "/runs")).andExpect(status().isNotFound());
    }

    @Test
    void aScheduleWithoutRunsReturnsAnEmptyList() throws Exception {
        Schedule schedule = newSchedule("runs-empty");

        assertThat(schedules.runs(schedule.id(), 10)).isEmpty();
        mvc.perform(get("/api/schedules/" + schedule.id() + "/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
