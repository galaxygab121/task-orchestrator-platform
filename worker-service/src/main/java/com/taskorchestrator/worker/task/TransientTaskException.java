package com.taskorchestrator.worker.task;

/** Retryable failure (timeouts, simulated flakiness, resource exhaustion). */
public class TransientTaskException extends RuntimeException {

    public TransientTaskException(String message) {
        super(message);
    }
}
