package com.taskorchestrator.scheduler;

import com.taskorchestrator.contracts.JobStatus;
import com.taskorchestrator.core.domain.Job;
import com.taskorchestrator.core.domain.JobRepository;
import com.taskorchestrator.core.lifecycle.JobLifecycleService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moves SUBMITTED jobs to QUEUED in strict {@code priority DESC, created_at ASC} order, never letting more
 * than {@code scheduler.max-in-flight} jobs be QUEUED/RUNNING at once. Because the backlog handed to Kafka
 * is bounded, a newly submitted high-priority job overtakes older low-priority ones that are still in the
 * DB queue, even though Kafka itself only orders within a partition.
 *
 * <p>Each cycle is one transaction: Postgres advisory lock (one dispatcher at a time cluster-wide), capacity
 * count, {@code FOR UPDATE SKIP LOCKED} selection, SUBMITTED->QUEUED transition and outbox dispatch row.
 * A job can therefore be queued for attempt 1 only once, regardless of duplicate submission events or
 * scheduler restarts.
 */
@Service
public class DispatchService {

    static final long DISPATCH_LOCK_KEY = 0x6A6F62735F647370L;
    private static final Logger log = LoggerFactory.getLogger(DispatchService.class);
    private static final EnumSet<JobStatus> IN_FLIGHT = EnumSet.of(JobStatus.QUEUED, JobStatus.RUNNING);

    private final JobRepository jobs;
    private final JobLifecycleService lifecycle;
    private final TransactionTemplate tx;
    private final DispatchCoordinator coordinator;
    private final SchedulerProperties props;
    private final Counter dispatched;

    public DispatchService(JobRepository jobs, JobLifecycleService lifecycle, TransactionTemplate tx, DispatchCoordinator coordinator,
            SchedulerProperties props, MeterRegistry meters) {
        this.jobs = jobs;
        this.lifecycle = lifecycle;
        this.tx = tx;
        this.coordinator = coordinator;
        this.props = props;
        this.dispatched = Counter.builder("orchestrator.scheduler.dispatched").description("Jobs moved SUBMITTED->QUEUED")
                .register(meters);
        for (JobStatus status : JobStatus.values()) {
            Gauge.builder("orchestrator.jobs", jobs, r -> r.countByStatusIn(EnumSet.of(status)))
                    .tag("status", status.name())
                    .description("Jobs currently in each status")
                    .register(meters);
        }
    }

    @Scheduled(fixedDelayString = "${scheduler.dispatch-interval-ms:1000}", initialDelayString = "${scheduler.initial-delay-ms:2000}")
    public void scheduledDispatch() {
        dispatchOnce();
    }

    /** Runs one bounded dispatch cycle and returns how many jobs were queued. */
    public int dispatchOnce() {
        if (!coordinator.isLeader()) {
            return 0;
        }
        Integer count = tx.execute(status -> {
            if (!jobs.tryAdvisoryXactLock(DISPATCH_LOCK_KEY)) {
                return 0;
            }
            long inFlight = jobs.countByStatusIn(IN_FLIGHT);
            int slots = (int) Math.min(props.dispatchBatchSize(), props.maxInFlight() - inFlight);
            if (slots <= 0) {
                return 0;
            }
            List<Job> next = jobs.lockNextSubmitted(slots);
            for (Job job : next) {
                MDC.put("jobId", job.getId().toString());
                try {
                    lifecycle.dispatch(job, "scheduler");
                    log.info("Dispatched job {} priority={} attempt={}", job.getId(), job.getPriority(), job.getAttempt());
                } finally {
                    MDC.remove("jobId");
                }
            }
            return next.size();
        });
        int n = count == null ? 0 : count;
        dispatched.increment(n);
        return n;
    }
}
