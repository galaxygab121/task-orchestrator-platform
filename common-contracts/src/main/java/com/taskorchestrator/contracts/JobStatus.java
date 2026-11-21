package com.taskorchestrator.contracts;

/** Lifecycle states of a job. The database row is authoritative; Kafka events only reference it. */
public enum JobStatus {
    SUBMITTED,
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
