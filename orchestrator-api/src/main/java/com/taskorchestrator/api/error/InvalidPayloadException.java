package com.taskorchestrator.api.error;

import java.util.List;

public class InvalidPayloadException extends RuntimeException {

    private final List<String> errors;

    public InvalidPayloadException(List<String> errors) {
        super("Invalid job payload");
        this.errors = List.copyOf(errors);
    }

    public List<String> getErrors() {
        return errors;
    }
}
