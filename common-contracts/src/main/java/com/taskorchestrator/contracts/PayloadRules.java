package com.taskorchestrator.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Strict, allowlist-based validation of job payloads. Used by the API (reject with 400) and again by
 * the worker (defence in depth: invalid payloads fail terminally, never retried).
 *
 * <p>Every payload may include two optional, bounded fault-injection fields used for demos and tests:
 * {@code failTransientlyTimes} (0..10, the first N attempts throw a retryable error) and
 * {@code failPermanently} (boolean, a non-retryable error).
 */
public final class PayloadRules {

    public static final long SLEEP_MAX_MS = 15_000;
    public static final int FIBONACCI_MAX_N = 40;
    public static final int DATA_SUMMARY_MAX_VALUES = 1_000;
    public static final int MAX_SIMULATED_TRANSIENT_FAILURES = 10;

    public static final String FAIL_TRANSIENTLY_TIMES = "failTransientlyTimes";
    public static final String FAIL_PERMANENTLY = "failPermanently";

    private static final Set<String> SIMULATION_FIELDS = Set.of(FAIL_TRANSIENTLY_TIMES, FAIL_PERMANENTLY);
    private static final Map<JobType, Set<String>> TYPE_FIELDS = Map.of(
            JobType.SLEEP, Set.of("durationMs"),
            JobType.FIBONACCI, Set.of("n"),
            JobType.DATA_SUMMARY, Set.of("values"));

    private PayloadRules() {
    }

    /** Returns human-readable problems; an empty list means the payload is valid. */
    public static List<String> validate(JobType type, JsonNode payload) {
        List<String> errors = new ArrayList<>();
        if (type == null) {
            errors.add("type is required");
            return errors;
        }
        if (payload == null || !payload.isObject()) {
            errors.add("payload must be a JSON object");
            return errors;
        }
        Set<String> allowed = TYPE_FIELDS.get(type);
        Iterator<String> names = payload.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name) && !SIMULATION_FIELDS.contains(name)) {
                errors.add("payload." + name + " is not supported for type " + type);
            }
        }
        switch (type) {
            case SLEEP -> requireInteger(payload, "durationMs", 0, SLEEP_MAX_MS, errors);
            case FIBONACCI -> requireInteger(payload, "n", 0, FIBONACCI_MAX_N, errors);
            case DATA_SUMMARY -> validateValues(payload.get("values"), errors);
            default -> errors.add("unsupported type " + type);
        }
        JsonNode transientTimes = payload.get(FAIL_TRANSIENTLY_TIMES);
        if (transientTimes != null) {
            requireInteger(payload, FAIL_TRANSIENTLY_TIMES, 0, MAX_SIMULATED_TRANSIENT_FAILURES, errors);
        }
        JsonNode permanent = payload.get(FAIL_PERMANENTLY);
        if (permanent != null && !permanent.isBoolean()) {
            errors.add("payload." + FAIL_PERMANENTLY + " must be a boolean");
        }
        return errors;
    }

    /** Rough runtime estimate stored on the job for observability and capacity planning. */
    public static long estimateRuntimeMs(JobType type, JsonNode payload) {
        if (type == JobType.SLEEP && payload != null && payload.path("durationMs").canConvertToLong()) {
            return payload.path("durationMs").asLong();
        }
        return 10;
    }

    public static int simulatedTransientFailures(JsonNode payload) {
        return payload == null ? 0 : payload.path(FAIL_TRANSIENTLY_TIMES).asInt(0);
    }

    public static boolean simulatedPermanentFailure(JsonNode payload) {
        return payload != null && payload.path(FAIL_PERMANENTLY).asBoolean(false);
    }

    private static void requireInteger(JsonNode payload, String field, long min, long max, List<String> errors) {
        JsonNode node = payload.get(field);
        if (node == null || node.isNull()) {
            errors.add("payload." + field + " is required");
        } else if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            errors.add("payload." + field + " must be an integer");
        } else if (node.asLong() < min || node.asLong() > max) {
            errors.add("payload." + field + " must be between " + min + " and " + max);
        }
    }

    private static void validateValues(JsonNode values, List<String> errors) {
        if (values == null || !values.isArray()) {
            errors.add("payload.values must be an array of numbers");
            return;
        }
        if (values.isEmpty() || values.size() > DATA_SUMMARY_MAX_VALUES) {
            errors.add("payload.values must contain between 1 and " + DATA_SUMMARY_MAX_VALUES + " numbers");
            return;
        }
        for (int i = 0; i < values.size(); i++) {
            JsonNode value = values.get(i);
            if (!value.isNumber() || !Double.isFinite(value.asDouble())) {
                errors.add("payload.values[" + i + "] must be a finite number");
                return;
            }
        }
    }
}
