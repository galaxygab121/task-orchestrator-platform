package com.taskorchestrator.contracts;

import java.time.Duration;

/**
 * Bounded exponential backoff: {@code initial * multiplier^(failedAttempt-1)}, capped at {@code max}.
 * With defaults (1s, x2, 8s) the delays after attempts 1..5 are 1s, 2s, 4s, 8s, 8s.
 */
public record RetryBackoff(Duration initialDelay, double multiplier, Duration maxDelay) {

    public RetryBackoff {
        if (initialDelay == null || initialDelay.isNegative()) {
            throw new IllegalArgumentException("initialDelay must be >= 0");
        }
        if (maxDelay == null || maxDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException("maxDelay must be >= initialDelay");
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier must be >= 1");
        }
    }

    public static RetryBackoff defaults() {
        return new RetryBackoff(Duration.ofSeconds(1), 2.0, Duration.ofSeconds(8));
    }

    /** Delay to wait before running the attempt after {@code failedAttempt} (1-based). */
    public Duration delayAfterAttempt(int failedAttempt) {
        int exponent = Math.max(0, failedAttempt - 1);
        double millis = initialDelay.toMillis() * Math.pow(multiplier, Math.min(exponent, 30));
        long capped = (long) Math.min(millis, maxDelay.toMillis());
        return Duration.ofMillis(capped);
    }
}
