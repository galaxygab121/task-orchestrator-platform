package com.taskorchestrator.core.lifecycle;

import com.taskorchestrator.contracts.ErrorSanitizer;
import com.taskorchestrator.contracts.JobStateMachine;
import com.taskorchestrator.contracts.JobStatus;
import com.taskorchestrator.contracts.RetryBackoff;
import com.taskorchestrator.contracts.Topics;
import com.taskorchestrator.contracts.events.EventTypes;
import com.taskorchestrator.contracts.events.JobDeadLetterEvent;
import com.taskorchestrator.contracts.events.JobDispatchEvent;
import com.taskorchestrator.contracts.events.JobSubmittedEvent;
import com.taskorchestrator.core.config.OrchestratorProperties;
import com.taskorchestrator.core.domain.Job;
import com.taskorchestrator.core.domain.JobEvent;
import com.taskorchestrator.core.domain.JobEventRepository;
import com.taskorchestrator.core.domain.JobRepository;
import com.taskorchestrator.core.outbox.OutboxWriter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only component allowed to change {@link Job#getStatus()}. Every method runs inside the caller's
 * transaction (MANDATORY) so the status change, the audit event and any outbox message commit atomically.
 * Transition legality is delegated to {@link JobStateMachine}; concurrent writers are serialised by the
 * entity's {@code @Version} (optimistic lock) or by row locks taken by the caller.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class JobLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(JobLifecycleService.class);

    private final JobRepository jobs;
    private final JobEventRepository events;
    private final OutboxWriter outbox;
    private final Clock clock;
    private final RetryBackoff backoff;
    private final MeterRegistry meters;

    public JobLifecycleService(JobRepository jobs, JobEventRepository events, OutboxWriter outbox, Clock clock,
            OrchestratorProperties properties, MeterRegistry meters) {
        this.jobs = jobs;
        this.events = events;
        this.outbox = outbox;
        this.clock = clock;
        this.backoff = properties.retry().toBackoff();
        this.meters = meters;
    }

    /** Persists a new job in SUBMITTED and announces it to the scheduler via the outbox. */
    public Job submit(Job job, String actor) {
        Instant now = clock.instant();
        job.setStatus(JobStatus.SUBMITTED);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        Job saved = jobs.save(job);
        events.save(new JobEvent(saved.getId(), null, JobStatus.SUBMITTED, 0, actor, "accepted", now));
        UUID eventId = UUID.randomUUID();
        outbox.append(eventId, saved.getId(), Topics.JOB_SUBMITTED, EventTypes.JOB_SUBMITTED,
                new JobSubmittedEvent(JobSubmittedEvent.SCHEMA_VERSION, eventId, saved.getId(), saved.getType(),
                        saved.getPriority(), now), now, now);
        count(null, JobStatus.SUBMITTED);
        return saved;
    }

    /** SUBMITTED -> QUEUED for attempt 1, and enqueue the dispatch message for workers. */
    public void dispatch(Job job, String actor) {
        int nextAttempt = job.getAttempt() + 1;
        transition(job, JobStatus.QUEUED, nextAttempt, actor, "dispatched attempt " + nextAttempt);
        appendDispatch(job, clock.instant());
    }

    /**
     * QUEUED -> RUNNING if the job is still waiting for exactly this attempt. Returns false for duplicate or
     * stale deliveries (already claimed, cancelled, finished, or superseded by a newer attempt).
     */
    public boolean claim(Job job, int attempt, String workerId, Duration lease) {
        if (job.getStatus() != JobStatus.QUEUED || job.getAttempt() != attempt) {
            return false;
        }
        Instant now = clock.instant();
        transition(job, JobStatus.RUNNING, attempt, workerId, "claimed by " + workerId);
        job.setLeaseOwner(workerId);
        job.setLeaseExpiresAt(now.plus(lease));
        if (job.getStartedAt() == null) {
            job.setStartedAt(now);
        }
        return true;
    }

    /** True if {@code workerId} still owns the given running attempt (i.e. it was not reclaimed). */
    public boolean isOwnedBy(Job job, int attempt, String workerId) {
        return job.getStatus() == JobStatus.RUNNING && job.getAttempt() == attempt && workerId.equals(job.getLeaseOwner());
    }

    public void succeed(Job job, String resultJson, String actor) {
        transition(job, JobStatus.SUCCEEDED, job.getAttempt(), actor, "succeeded on attempt " + job.getAttempt());
        job.setResult(resultJson);
        job.setLastError(null);
        job.setCompletedAt(clock.instant());
        clearLease(job);
    }

    /**
     * Handles a failed RUNNING attempt. Retryable failures with attempts left go back to QUEUED with a
     * delayed dispatch (bounded exponential backoff); everything else becomes FAILED and a sanitised
     * event is written to the DLQ outbox (so a DLQ publish failure is retried like any outbox row).
     */
    public FailureOutcome fail(Job job, String error, boolean retryable, String actor) {
        String reason = ErrorSanitizer.sanitize(error);
        job.setLastError(reason);
        Instant now = clock.instant();
        if (retryable && job.hasRetriesLeft()) {
            Duration delay = backoff.delayAfterAttempt(job.getAttempt());
            int nextAttempt = job.getAttempt() + 1;
            transition(job, JobStatus.QUEUED, nextAttempt, actor,
                    "retry " + nextAttempt + "/" + job.maxAttempts() + " in " + delay.toMillis() + "ms: " + reason);
            clearLease(job);
            appendDispatch(job, now.plus(delay));
            log.info("Job {} attempt {} failed transiently, retry scheduled in {} ms", job.getId(), nextAttempt - 1, delay.toMillis());
            return new FailureOutcome(FailureOutcome.Kind.RETRY_SCHEDULED, delay);
        }
        String detail = (retryable ? "retries exhausted after " + job.getAttempt() + " attempts: " : "terminal failure: ") + reason;
        transition(job, JobStatus.FAILED, job.getAttempt(), actor, detail);
        job.setCompletedAt(now);
        clearLease(job);
        UUID eventId = UUID.randomUUID();
        outbox.append(eventId, job.getId(), Topics.JOB_DLQ, EventTypes.JOB_DEAD_LETTERED,
                new JobDeadLetterEvent(JobDeadLetterEvent.SCHEMA_VERSION, eventId, job.getId(), job.getType(), job.getAttempt(),
                        job.getMaxRetries(), ErrorSanitizer.sanitize(detail), now), now, now);
        log.warn("Job {} failed permanently after {} attempt(s): {}", job.getId(), job.getAttempt(), detail);
        return new FailureOutcome(FailureOutcome.Kind.DEAD_LETTERED, Duration.ZERO);
    }

    /** SUBMITTED/QUEUED -> CANCELLED; throws InvalidJobTransitionException otherwise (API maps to 409). */
    public void cancel(Job job, String actor) {
        transition(job, JobStatus.CANCELLED, job.getAttempt(), actor, "cancelled by client");
        job.setCompletedAt(clock.instant());
    }

    private void appendDispatch(Job job, Instant availableAt) {
        UUID eventId = UUID.randomUUID();
        Instant now = clock.instant();
        outbox.append(eventId, job.getId(), Topics.JOB_DISPATCH, EventTypes.JOB_DISPATCH,
                new JobDispatchEvent(JobDispatchEvent.SCHEMA_VERSION, eventId, job.getId(), job.getAttempt(), job.getType(), now),
                now, availableAt);
    }

    private void transition(Job job, JobStatus to, int attempt, String actor, String detail) {
        JobStatus from = job.getStatus();
        JobStateMachine.requireTransition(from, to);
        Instant now = clock.instant();
        job.setStatus(to);
        job.setAttempt(attempt);
        job.setUpdatedAt(now);
        events.save(new JobEvent(job.getId(), from, to, attempt, actor, ErrorSanitizer.sanitize(detail), now));
        count(from, to);
    }

    private static void clearLease(Job job) {
        job.setLeaseOwner(null);
        job.setLeaseExpiresAt(null);
    }

    private void count(JobStatus from, JobStatus to) {
        Counter.builder("orchestrator.job.transitions")
                .description("Job status transitions")
                .tag("from", from == null ? "NONE" : from.name())
                .tag("to", to.name())
                .register(meters)
                .increment();
    }

    /** Result of {@link #fail}. */
    public record FailureOutcome(Kind kind, Duration retryDelay) {

        public enum Kind {
            RETRY_SCHEDULED,
            DEAD_LETTERED
        }
    }
}
