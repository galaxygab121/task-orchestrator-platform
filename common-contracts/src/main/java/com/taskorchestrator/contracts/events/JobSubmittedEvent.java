package com.taskorchestrator.contracts.events;

import com.taskorchestrator.contracts.JobType;
import java.time.Instant;
import java.util.UUID;

/** Notification that a job row exists in SUBMITTED state. Carries ids only; the DB row is authoritative. */
public record JobSubmittedEvent(int schemaVersion, UUID eventId, UUID jobId, JobType type, int priority, Instant occurredAt) {

    public static final int SCHEMA_VERSION = 1;
}
