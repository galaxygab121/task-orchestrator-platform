package com.taskorchestrator.worker.task;

/** Non-retryable failure (invalid payload, unsupported type, deterministic error). */
public class PermanentTaskException extends RuntimeException {

    public PermanentTaskException(String message) {
        super(message);
    }
}
