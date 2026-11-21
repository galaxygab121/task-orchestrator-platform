package com.taskorchestrator.contracts;

/** Kafka topic names (versioned). */
public final class Topics {

    /** API -> scheduler: a job was persisted and is waiting in the DB queue. */
    public static final String JOB_SUBMITTED = "jobs.submitted.v1";
    /** Scheduler/worker -> worker: run a specific attempt of a job. */
    public static final String JOB_DISPATCH = "jobs.v1";
    /** Terminal failures (sanitised) and poison messages. */
    public static final String JOB_DLQ = "jobs.dlq.v1";

    private Topics() {
    }
}
