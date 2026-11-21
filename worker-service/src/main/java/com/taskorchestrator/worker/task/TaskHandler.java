package com.taskorchestrator.worker.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.taskorchestrator.contracts.JobType;

/**
 * A built-in, bounded, side-effect-free task. Delivery is at-least-once, so handlers must be idempotent:
 * running the same attempt twice must be harmless. Implementations must respond to interruption.
 */
public interface TaskHandler {

    JobType type();

    JsonNode execute(TaskContext context) throws InterruptedException;
}
