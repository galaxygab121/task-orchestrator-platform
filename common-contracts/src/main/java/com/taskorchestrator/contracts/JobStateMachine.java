package com.taskorchestrator.contracts;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Single definition of the allowed job status transitions.
 *
 * <pre>
 * SUBMITTED -> QUEUED      scheduler dispatches attempt 1
 * SUBMITTED -> CANCELLED   client cancel before dispatch
 * QUEUED    -> RUNNING     worker claims the attempt (lease acquired)
 * QUEUED    -> CANCELLED   client cancel before a worker claims it
 * RUNNING   -> SUCCEEDED   handler finished
 * RUNNING   -> QUEUED      transient failure or expired lease with retries left
 * RUNNING   -> FAILED      terminal failure or retries exhausted (dead-lettered)
 * </pre>
 */
public final class JobStateMachine {

    private static final Map<JobStatus, Set<JobStatus>> ALLOWED = new EnumMap<>(JobStatus.class);

    static {
        ALLOWED.put(JobStatus.SUBMITTED, EnumSet.of(JobStatus.QUEUED, JobStatus.CANCELLED));
        ALLOWED.put(JobStatus.QUEUED, EnumSet.of(JobStatus.RUNNING, JobStatus.CANCELLED));
        ALLOWED.put(JobStatus.RUNNING, EnumSet.of(JobStatus.SUCCEEDED, JobStatus.QUEUED, JobStatus.FAILED));
        ALLOWED.put(JobStatus.SUCCEEDED, EnumSet.noneOf(JobStatus.class));
        ALLOWED.put(JobStatus.FAILED, EnumSet.noneOf(JobStatus.class));
        ALLOWED.put(JobStatus.CANCELLED, EnumSet.noneOf(JobStatus.class));
    }

    private JobStateMachine() {
    }

    public static boolean canTransition(JobStatus from, JobStatus to) {
        if (from == null || to == null) {
            return false;
        }
        return ALLOWED.get(from).contains(to);
    }

    public static void requireTransition(JobStatus from, JobStatus to) {
        if (!canTransition(from, to)) {
            throw new InvalidJobTransitionException(from, to);
        }
    }

    public static Set<JobStatus> allowedFrom(JobStatus from) {
        return from == null ? Set.of() : Set.copyOf(ALLOWED.get(from));
    }
}
