package com.taskorchestrator.api.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.taskorchestrator.contracts.JobStatus;
import com.taskorchestrator.contracts.JobType;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record JobResponse(
        UUID id,
        String title,
        JobType type,
        JobStatus status,
        int priority,
        int maxRetries,
        int attempt,
        long estimatedRuntimeMs,
        JsonNode payload,
        JsonNode result,
        String lastError,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant completedAt) {
}
