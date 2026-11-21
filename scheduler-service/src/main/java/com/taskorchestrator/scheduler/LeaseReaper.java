package com.taskorchestrator.scheduler;

import com.taskorchestrator.core.domain.Job;
import com.taskorchestrator.core.domain.JobRepository;
import com.taskorchestrator.core.lifecycle.JobLifecycleService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Crash recovery. A RUNNING job whose lease deadline passed (worker died or stopped heartbeating) is treated
 * as a transient failure of that attempt: re-queued with backoff if retries remain, otherwise FAILED + DLQ.
 * Rows are locked with SKIP LOCKED, and the worker's own completion is guarded by {@code @Version}, so a late
 * result from a presumed-dead worker is discarded rather than overwriting the newer attempt.
 */
@Component
public class LeaseReaper {

    private static final Logger log = LoggerFactory.getLogger(LeaseReaper.class);

    private final JobRepository jobs;
    private final JobLifecycleService lifecycle;
    private final TransactionTemplate tx;
    private final SchedulerProperties props;
    private final Clock clock;
    private final Counter reclaimed;

    public LeaseReaper(JobRepository jobs, JobLifecycleService lifecycle, TransactionTemplate tx, SchedulerProperties props, Clock clock,
            MeterRegistry meters) {
        this.jobs = jobs;
        this.lifecycle = lifecycle;
        this.tx = tx;
        this.props = props;
        this.clock = clock;
        this.reclaimed = Counter.builder("orchestrator.scheduler.leases.reclaimed").register(meters);
    }

    @Scheduled(fixedDelayString = "${scheduler.lease-reaper-interval-ms:5000}", initialDelayString = "${scheduler.initial-delay-ms:2000}")
    public void scheduledReap() {
        reapOnce();
    }

    public int reapOnce() {
        Integer count = tx.execute(status -> {
            List<Job> expired = jobs.lockExpiredLeases(clock.instant(), props.reaperBatchSize());
            for (Job job : expired) {
                MDC.put("jobId", job.getId().toString());
                try {
                    log.warn("Lease expired for job {} attempt {} (owner {}), reclaiming", job.getId(), job.getAttempt(),
                            job.getLeaseOwner());
                    lifecycle.fail(job, "lease expired: worker " + job.getLeaseOwner() + " stopped heartbeating", true, "lease-reaper");
                } finally {
                    MDC.remove("jobId");
                }
            }
            return expired.size();
        });
        int n = count == null ? 0 : count;
        reclaimed.increment(n);
        return n;
    }
}
