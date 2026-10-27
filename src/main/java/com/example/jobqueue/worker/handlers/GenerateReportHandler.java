package com.example.jobqueue.worker.handlers;

import com.example.jobqueue.domain.Job;
import com.example.jobqueue.repo.JobRepository;
import com.example.jobqueue.worker.ResultJobHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;

/** A real job type: builds a summary of the queue itself and returns it as the job's result. */
@Component
public class GenerateReportHandler implements ResultJobHandler {

    private final JobRepository repository;

    public GenerateReportHandler(JobRepository repository) {
        this.repository = repository;
    }

    @Override
    public String type() {
        return "GENERATE_REPORT";
    }

    @Override
    public JsonNode run(Job job) {
        ObjectNode report = JsonNodeFactory.instance.objectNode();
        report.put("generatedAt", Instant.now().toString());

        Map<String, Long> byStatus = repository.statusCounts();
        ObjectNode statuses = report.putObject("jobsByStatus");
        byStatus.forEach((status, count) -> statuses.put(status, count));
        report.put("totalJobs", byStatus.values().stream().mapToLong(Long::longValue).sum());

        JobRepository.RecentStats recent = repository.recentStats();
        report.put("finishedLastMinute", recent.succeededLastMinute());
        if (recent.oldestDuePendingSeconds() == null) {
            report.putNull("longestWaitSeconds");
        } else {
            report.put("longestWaitSeconds", recent.oldestDuePendingSeconds());
        }

        ObjectNode types = report.putObject("jobsByType");
        repository.countsByType().forEach((type, count) -> types.put(type, count));
        return report;
    }
}
