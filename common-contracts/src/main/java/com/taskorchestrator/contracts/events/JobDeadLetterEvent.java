package com.taskorchestrator.contracts.events;

import com.taskorchestrator.contracts.JobType;
import java.time.Instant;
import java.util.UUID;

/** Sanitised terminal-failure record. Never contains payload contents or stack traces. */
public record JobDeadLetterEvent(
        int schemaVersion,
        UUID eventId,
        UUID jobId,
        JobType type,
        int attempts,
        int maxRetries,
        String reason,
        Instant failedAt) {

    public static final int SCHEMA_VERSION = 1;
}
