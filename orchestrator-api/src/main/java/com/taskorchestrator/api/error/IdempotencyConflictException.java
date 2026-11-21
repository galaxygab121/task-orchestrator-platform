package com.taskorchestrator.api.error;

public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException() {
        super("Idempotency-Key was already used with a different request body");
    }
}
