package com.taskorchestrator.contracts.events;

import com.taskorchestrator.contracts.JobType;
import java.time.Instant;
import java.util.UUID;

/**
 * Instruction for a worker to run attempt {@code attempt} of a job. A worker only acts if the
 * job row is still QUEUED with the same attempt number, so duplicate or stale deliveries are no-ops.
 */
public record JobDispatchEvent(int schemaVersion, UUID eventId, UUID jobId, int attempt, JobType type, Instant occurredAt) {

    public static final int SCHEMA_VERSION = 1;
}
