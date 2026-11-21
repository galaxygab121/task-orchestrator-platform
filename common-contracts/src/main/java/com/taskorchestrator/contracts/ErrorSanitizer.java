package com.taskorchestrator.contracts;

/** Produces short, single-line error text that is safe to persist, log and publish to the DLQ. */
public final class ErrorSanitizer {

    public static final int MAX_LENGTH = 500;

    private ErrorSanitizer() {
    }

    public static String sanitize(String message) {
        if (message == null || message.isBlank()) {
            return "unknown error";
        }
        String singleLine = message.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\p{Cntrl}", "").trim();
        return singleLine.length() <= MAX_LENGTH ? singleLine : singleLine.substring(0, MAX_LENGTH - 3) + "...";
    }

    public static String describe(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        String message = error.getMessage();
        return sanitize(error.getClass().getSimpleName() + (message == null ? "" : ": " + message));
    }
}
