package com.gabrielle.orchestrator.jobs;

import java.time.Instant;
import java.util.UUID;

public class JobResponse {

    public UUID id;
    public String title;
    public int priority;
    public long estimatedRuntimeMs;
    public Instant deadlineUtc;
    public JobStatus status;
    public int attempt;
    public int maxRetries;

    public static JobResponse from(JobEntity e) {
        JobResponse r = new JobResponse();
        r.id = e.getId();
        r.title = e.getTitle();
        r.priority = e.getPriority();
        r.estimatedRuntimeMs = e.getEstimatedRuntimeMs();
        r.deadlineUtc = e.getDeadlineUtc();
        r.status = e.getStatus();
        r.attempt = e.getAttempt();
        r.maxRetries = e.getMaxRetries();
        return r;
    }
}

