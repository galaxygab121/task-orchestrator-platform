package com.taskorchestrator.core.messaging;

/** A record that can never be processed (malformed JSON, unknown schema). Sent straight to the DLQ. */
public class PoisonMessageException extends RuntimeException {

    public PoisonMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
