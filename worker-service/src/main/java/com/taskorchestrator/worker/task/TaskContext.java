package com.taskorchestrator.worker.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.taskorchestrator.contracts.JobType;
import java.util.UUID;

/** Immutable snapshot handed to a handler; handlers never touch the database. */
public record TaskContext(UUID jobId, JobType type, int attempt, JsonNode payload) {
}
