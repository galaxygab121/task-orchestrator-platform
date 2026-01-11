package com.gabrielle.worker.jobs;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "jobs")
public class JobEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private int priority;

    @Column(nullable = false)
    private long estimatedRuntimeMs;

    private Instant deadlineUtc;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    private int maxRetries;
    private int attempt;

    public UUID getId() { return id; }

    public String getTitle() { return title; }

    public int getPriority() { return priority; }

    public long getEstimatedRuntimeMs() { return estimatedRuntimeMs; }

    public Instant getDeadlineUtc() { return deadlineUtc; }

    public JobStatus getStatus() { return status; }
    public void setStatus(JobStatus status) { this.status = status; }

    public int getMaxRetries() { return maxRetries; }

    public int getAttempt() { return attempt; }
    public void setAttempt(int attempt) { this.attempt = attempt; }
}

