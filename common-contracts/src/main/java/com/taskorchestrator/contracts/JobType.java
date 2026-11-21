package com.taskorchestrator.contracts;

/**
 * Allowlist of built-in task types. Workers only ever execute these bounded handlers;
 * there is deliberately no way to submit code, shell commands or URLs.
 */
public enum JobType {
    /** Sleeps for {@code durationMs} (0..15000). */
    SLEEP,
    /** Computes Fibonacci number {@code n} (0..40) iteratively. */
    FIBONACCI,
    /** Summarises a small numeric array {@code values} (1..1000 elements). */
    DATA_SUMMARY
}
