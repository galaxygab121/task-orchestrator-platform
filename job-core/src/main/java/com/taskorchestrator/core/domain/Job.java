package com.taskorchestrator.core.domain;

import com.taskorchestrator.contracts.JobStatus;
import com.taskorchestrator.contracts.JobType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Persistent job. Status must only be changed through {@code JobLifecycleService}, which validates
 * transitions and writes the audit trail; {@link Version} guards against concurrent writers.
 */
@Entity
@Table(name = "jobs")
public class Job {

    @Id
    private UUID id;

    @Column(nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private JobType type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(nullable = false)
    private int priority;

    @Column(name = "estimated_runtime_ms", nullable = false)
    private long estimatedRuntimeMs;

    @Column(name = "max_retries", nullable = false)
    private int maxRetries;

    @Column(nullable = false)
    private int attempt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JobStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String result;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "idempotency_key", length = 128, unique = true)
    private String idempotencyKey;

    @Column(name = "request_hash", length = 64)
    private String requestHash;

    @Column(name = "lease_owner", length = 200)
    private String leaseOwner;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /** Null until first persisted, which also tells Spring Data the entity is new (persist, not merge). */
    @Version
    @Column(nullable = false)
    private Long version;

    protected Job() {
    }

    public Job(UUID id, String title, JobType type, String payload, int priority, long estimatedRuntimeMs, int maxRetries) {
        this.id = id;
        this.title = title;
        this.type = type;
        this.payload = payload;
        this.priority = priority;
        this.estimatedRuntimeMs = estimatedRuntimeMs;
        this.maxRetries = maxRetries;
        this.attempt = 0;
    }

    /** Total executions allowed: the first attempt plus {@code maxRetries} retries. */
    public int maxAttempts() {
        return maxRetries + 1;
    }

    public boolean hasRetriesLeft() {
        return attempt < maxAttempts();
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public JobType getType() {
        return type;
    }

    public String getPayload() {
        return payload;
    }

    public int getPriority() {
        return priority;
    }

    public long getEstimatedRuntimeMs() {
        return estimatedRuntimeMs;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public int getAttempt() {
        return attempt;
    }

    public void setAttempt(int attempt) {
        this.attempt = attempt;
    }

    public JobStatus getStatus() {
        return status;
    }

    /** Package-private on purpose in spirit: only JobLifecycleService should call this. */
    public void setStatus(JobStatus status) {
        this.status = status;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public void setRequestHash(String requestHash) {
        this.requestHash = requestHash;
    }

    public String getLeaseOwner() {
        return leaseOwner;
    }

    public void setLeaseOwner(String leaseOwner) {
        this.leaseOwner = leaseOwner;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public void setLeaseExpiresAt(Instant leaseExpiresAt) {
        this.leaseExpiresAt = leaseExpiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public Long getVersion() {
        return version;
    }
}
