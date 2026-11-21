package com.taskorchestrator.worker.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskorchestrator.contracts.ErrorSanitizer;
import com.taskorchestrator.contracts.events.JobDispatchEvent;
import com.taskorchestrator.core.domain.Job;
import com.taskorchestrator.core.domain.JobRepository;
import com.taskorchestrator.core.lifecycle.JobLifecycleService;
import com.taskorchestrator.worker.WorkerProperties;
import com.taskorchestrator.worker.task.PermanentTaskException;
import com.taskorchestrator.worker.task.TaskContext;
import com.taskorchestrator.worker.task.TransientTaskException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Executes one dispatch message:
 * <ol>
 *   <li>Claim: QUEUED->RUNNING for exactly this attempt in a short transaction (optimistic lock). Duplicate,
 *       stale, cancelled or already-claimed deliveries are acknowledged and ignored.</li>
 *   <li>Run the bounded handler with a timeout, heartbeating the lease; if the lease is lost (reclaimed by
 *       the scheduler) the task is interrupted.</li>
 *   <li>Complete in a second transaction, but only if this worker still owns the attempt; otherwise the
 *       result is discarded. Failures go through JobLifecycleService (retry with backoff or FAILED + DLQ).</li>
 * </ol>
 * The worker never holds a DB transaction open while user work runs.
 */
@Service
public class JobExecutionService {

    private static final Logger log = LoggerFactory.getLogger(JobExecutionService.class);

    private final JobRepository jobs;
    private final JobLifecycleService lifecycle;
    private final TransactionTemplate tx;
    private final TaskRunner runner;
    private final WorkerProperties props;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final MeterRegistry meters;
    private final String workerId;
    private final ScheduledExecutorService heartbeats;
    private final Counter skipped;

    public JobExecutionService(JobRepository jobs, JobLifecycleService lifecycle, TransactionTemplate tx, TaskRunner runner,
            WorkerProperties props, ObjectMapper mapper, Clock clock, MeterRegistry meters) {
        this.jobs = jobs;
        this.lifecycle = lifecycle;
        this.tx = tx;
        this.runner = runner;
        this.props = props;
        this.mapper = mapper;
        this.clock = clock;
        this.meters = meters;
        this.workerId = props.instanceId() + "-" + UUID.randomUUID().toString().substring(0, 8);
        this.heartbeats = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lease-heartbeat");
            t.setDaemon(true);
            return t;
        });
        this.skipped = Counter.builder("orchestrator.worker.deliveries.skipped")
                .description("Dispatch deliveries ignored because the attempt was not claimable (duplicate/stale)")
                .register(meters);
    }

    public String workerId() {
        return workerId;
    }

    /** Returns true if this delivery was executed, false if it was a duplicate/stale delivery. */
    public boolean process(JobDispatchEvent event) {
        Optional<TaskContext> claimed = claim(event);
        if (claimed.isEmpty()) {
            skipped.increment();
            log.info("Skipping dispatch of job {} attempt {}: not claimable (duplicate, stale, cancelled or finished)",
                    event.jobId(), event.attempt());
            return false;
        }
        TaskContext context = claimed.get();
        log.info("Claimed job {} attempt {} type={}", context.jobId(), context.attempt(), context.type());
        Timer.Sample sample = Timer.start(meters);
        String outcome = "succeeded";
        Future<JsonNode> future = null;
        ScheduledFuture<?> heartbeat = null;
        try {
            future = runner.start(context);
            heartbeat = startHeartbeat(context, future);
            JsonNode result = runner.await(future);
            complete(context, job -> lifecycle.succeed(job, result.toString(), workerId));
        } catch (TransientTaskException e) {
            outcome = "transient_failure";
            complete(context, job -> lifecycle.fail(job, e.getMessage(), true, workerId));
        } catch (PermanentTaskException e) {
            outcome = "permanent_failure";
            complete(context, job -> lifecycle.fail(job, e.getMessage(), false, workerId));
        } catch (InterruptedException e) {
            // Shutdown: leave the attempt RUNNING and ack the message; the lease reaper re-queues it once the
            // lease expires. Rethrowing would make the Kafka error handler dead-letter a perfectly valid message.
            outcome = "interrupted";
            if (future != null) {
                future.cancel(true);
            }
            Thread.currentThread().interrupt();
            log.warn("Interrupted while running job {} attempt {}; lease reaper will recover it", context.jobId(), context.attempt());
        } catch (RuntimeException e) {
            outcome = "transient_failure";
            log.error("Unexpected error running job {}", context.jobId(), e);
            complete(context, job -> lifecycle.fail(job, ErrorSanitizer.describe(e), true, workerId));
        } finally {
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
            sample.stop(Timer.builder("orchestrator.worker.execution")
                    .description("Task execution time by type and outcome")
                    .tag("type", context.type().name())
                    .tag("outcome", outcome)
                    .register(meters));
        }
        return true;
    }

    private Optional<TaskContext> claim(JobDispatchEvent event) {
        try {
            return Optional.ofNullable(tx.execute(status -> {
                Job job = jobs.findById(event.jobId()).orElse(null);
                if (job == null) {
                    log.warn("Dispatch for unknown job {} ignored", event.jobId());
                    return null;
                }
                if (!lifecycle.claim(job, event.attempt(), workerId, props.leaseDuration())) {
                    return null;
                }
                jobs.saveAndFlush(job);
                return new TaskContext(job.getId(), job.getType(), job.getAttempt(), parsePayload(job));
            }));
        } catch (ObjectOptimisticLockingFailureException lostRace) {
            return Optional.empty();
        }
    }

    private JsonNode parsePayload(Job job) {
        try {
            return mapper.readTree(job.getPayload());
        } catch (JsonProcessingException e) {
            return mapper.createObjectNode();
        }
    }

    private ScheduledFuture<?> startHeartbeat(TaskContext context, Future<JsonNode> task) {
        long periodMs = props.heartbeatInterval().toMillis();
        return heartbeats.scheduleAtFixedRate(() -> {
            try {
                Integer renewed = tx.execute(s -> jobs.renewLease(context.jobId(), workerId, context.attempt(),
                        clock.instant().plus(props.leaseDuration())));
                if (renewed == null || renewed == 0) {
                    log.warn("Lost lease on job {} attempt {}; interrupting task", context.jobId(), context.attempt());
                    task.cancel(true);
                }
            } catch (RuntimeException e) {
                log.warn("Heartbeat for job {} failed: {}", context.jobId(), e.getMessage());
            }
        }, periodMs, periodMs, TimeUnit.MILLISECONDS);
    }

    private void complete(TaskContext context, Consumer<Job> action) {
        try {
            tx.executeWithoutResult(status -> {
                Job job = jobs.findById(context.jobId()).orElseThrow();
                if (!lifecycle.isOwnedBy(job, context.attempt(), workerId)) {
                    log.warn("Discarding outcome of job {} attempt {}: lease no longer held (now {} attempt {})",
                            context.jobId(), context.attempt(), job.getStatus(), job.getAttempt());
                    return;
                }
                action.accept(job);
                jobs.saveAndFlush(job);
            });
        } catch (ObjectOptimisticLockingFailureException e) {
            log.warn("Discarding outcome of job {} attempt {}: concurrently reclaimed", context.jobId(), context.attempt());
        }
    }

    @PreDestroy
    public void shutdown() {
        heartbeats.shutdownNow();
    }
}
