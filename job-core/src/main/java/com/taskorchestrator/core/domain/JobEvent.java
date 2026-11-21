package com.taskorchestrator.core.domain;

import com.taskorchestrator.contracts.JobStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Append-only record of one status transition. */
@Entity
@Table(name = "job_events")
public class JobEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20)
    private JobStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20)
    private JobStatus toStatus;

    @Column(nullable = false)
    private int attempt;

    @Column(nullable = false, length = 200)
    private String actor;

    @Column(length = 1000)
    private String detail;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected JobEvent() {
    }

    public JobEvent(UUID jobId, JobStatus fromStatus, JobStatus toStatus, int attempt, String actor, String detail, Instant occurredAt) {
        this.jobId = jobId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.attempt = attempt;
        this.actor = actor;
        this.detail = detail;
        this.occurredAt = occurredAt;
    }

    public Long getId() {
        return id;
    }

    public UUID getJobId() {
        return jobId;
    }

    public JobStatus getFromStatus() {
        return fromStatus;
    }

    public JobStatus getToStatus() {
        return toStatus;
    }

    public int getAttempt() {
        return attempt;
    }

    public String getActor() {
        return actor;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
