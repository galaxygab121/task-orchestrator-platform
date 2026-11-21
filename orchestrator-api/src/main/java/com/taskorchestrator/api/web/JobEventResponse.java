package com.taskorchestrator.api.web;

import com.taskorchestrator.contracts.JobStatus;
import java.time.Instant;

public record JobEventResponse(JobStatus fromStatus, JobStatus toStatus, int attempt, String actor, String detail, Instant occurredAt) {
}
