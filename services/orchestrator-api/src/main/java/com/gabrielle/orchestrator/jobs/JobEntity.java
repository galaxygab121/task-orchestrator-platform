package com.gabrielle.orchestrator.jobs;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "jobs")
public class JobEntity {

    @Id
    @GeneratedValue
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
    private JobStatus status = JobStatus.SUBMITTED;

    @Column(nullable = false)
    private Instant createdAtUtc = Instant.now();

    @Column(nullable = false)
    private Instant updatedAtUtc = Instant.now();

    private int maxRetries = 3;
    private int attempt = 0;

    // ---- getters & setters ----

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public long getEstimatedRuntimeMs() {
        return estimatedRuntimeMs;
    }

    public void setEstimatedRuntimeMs(long estimatedRuntimeMs) {
        this.estimatedRuntimeMs = estimatedRuntimeMs;
    }

    public Instant getDeadlineUtc() {
        return deadlineUtc;
    }

    public void setDeadlineUtc(Instant deadlineUtc) {
        this.deadlineUtc = deadlineUtc;
    }

    public JobStatus getStatus() {
        return status;
    }

    public void setStatus(JobStatus status) {
        this.status = status;
        this.updatedAtUtc = Instant.now();
    }

    public Instant getCreatedAtUtc() {
        return createdAtUtc;
    }

    public Instant getUpdatedAtUtc() {
        return updatedAtUtc;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public int getAttempt() {
        return attempt;
    }

    public void setAttempt(int attempt) {
        this.attempt = attempt;
        this.updatedAtUtc = Instant.now();
    }
}

