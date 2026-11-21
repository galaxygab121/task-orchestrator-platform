package com.taskorchestrator.contracts;

/** Thrown when a requested status change is not allowed by {@link JobStateMachine}. */
public class InvalidJobTransitionException extends RuntimeException {

    private final JobStatus from;
    private final JobStatus to;

    public InvalidJobTransitionException(JobStatus from, JobStatus to) {
        super("Invalid job status transition " + from + " -> " + to);
        this.from = from;
        this.to = to;
    }

    public JobStatus getFrom() {
        return from;
    }

    public JobStatus getTo() {
        return to;
    }
}
