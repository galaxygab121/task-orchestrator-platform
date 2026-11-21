package com.taskorchestrator.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskorchestrator.contracts.JobStatus;
import com.taskorchestrator.contracts.JobType;
import com.taskorchestrator.contracts.Topics;
import com.taskorchestrator.core.domain.Job;
import com.taskorchestrator.core.domain.JobEventRepository;
import com.taskorchestrator.core.domain.JobRepository;
import com.taskorchestrator.core.lifecycle.JobLifecycleService;
import com.taskorchestrator.core.outbox.OutboxRepository;
import com.taskorchestrator.testsupport.DatabaseCleaner;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Scheduling loops, listeners and the outbox relay are disabled: the test drives each cycle explicitly. */
@SpringBootTest(properties = {
    "scheduler.max-in-flight=2",
    "scheduler.initial-delay-ms=3600000",
    "spring.kafka.listener.auto-startup=false",
    "orchestrator.outbox.enabled=false"
})
class PriorityDispatchIT extends RedisContainerSupport {

    @Autowired
    DispatchService dispatch;
    @Autowired
    LeaseReaper reaper;
    @Autowired
    JobLifecycleService lifecycle;
    @Autowired
    JobRepository jobs;
    @Autowired
    JobEventRepository events;
    @Autowired
    OutboxRepository outbox;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        DatabaseCleaner.truncateAll(jdbc);
    }

    private UUID submit(String title, int priority, int maxRetries) {
        Job job = new Job(UUID.randomUUID(), title, JobType.FIBONACCI, "{\"n\": 1}", priority, 10, maxRetries);
        tx.executeWithoutResult(s -> lifecycle.submit(job, "test"));
        return job.getId();
    }

    private void finish(UUID id) {
        tx.executeWithoutResult(s -> {
            Job job = jobs.findById(id).orElseThrow();
            lifecycle.claim(job, job.getAttempt(), "w", Duration.ofSeconds(30));
            lifecycle.succeed(job, "{}", "w");
        });
    }

    private List<String> queuedTitles() {
        return jobs.findAll().stream().filter(j -> j.getStatus() == JobStatus.QUEUED)
                .sorted((a, b) -> a.getTitle().compareTo(b.getTitle())).map(Job::getTitle).toList();
    }

    @Test
    void dispatchesHighestPriorityThenOldestWithinBoundedCapacity() {
        // submission order deliberately mixes priorities
        UUID low = submit("a-low-p1", 1, 0);
        UUID midOld = submit("b-mid-p3-old", 3, 0);
        UUID high = submit("c-high-p5", 5, 0);
        UUID midNew = submit("d-mid-p3-new", 3, 0);
        UUID top = submit("e-top-p5-later", 5, 0);

        assertThat(dispatch.dispatchOnce()).isEqualTo(2);
        assertThat(queuedTitles()).containsExactly("c-high-p5", "e-top-p5-later");
        assertThat(dispatch.dispatchOnce()).as("capacity is full").isZero();

        // a late, urgent job jumps ahead of everything still waiting in the DB queue
        UUID urgent = submit("f-urgent-p4", 4, 0);

        List<UUID> order = new ArrayList<>(List.of(high, top));
        for (UUID finished : List.of(high, top, urgent, midOld, midNew)) {
            finish(finished);
            assertThat(dispatch.dispatchOnce()).isLessThanOrEqualTo(1);
            jobs.findAll().stream().filter(j -> j.getStatus() == JobStatus.QUEUED && !order.contains(j.getId()))
                    .forEach(j -> order.add(j.getId()));
        }
        assertThat(order).containsExactly(high, top, urgent, midOld, midNew, low);
        assertThat(jobs.countByStatusIn(List.of(JobStatus.QUEUED, JobStatus.RUNNING))).isLessThanOrEqualTo(2);
    }

    @Test
    void repeatedCyclesNeverQueueTheSameAttemptTwice() {
        UUID id = submit("once", 3, 0);
        for (int i = 0; i < 5; i++) {
            dispatch.dispatchOnce();
        }
        assertThat(events.countByJobIdAndToStatus(id, JobStatus.QUEUED)).isEqualTo(1);
        assertThat(outbox.findByAggregateIdOrderByIdAsc(id).stream().filter(r -> r.getTopic().equals(Topics.JOB_DISPATCH)))
                .hasSize(1);
    }

    @Test
    void expiredLeaseIsRequeuedWhileRetriesRemainThenDeadLettered() {
        UUID id = submit("crashy", 3, 1);
        dispatch.dispatchOnce();
        tx.executeWithoutResult(s -> lifecycle.claim(jobs.findById(id).orElseThrow(), 1, "dead-worker", Duration.ofMillis(-1)));

        assertThat(reaper.reapOnce()).isEqualTo(1);
        Job requeued = jobs.findById(id).orElseThrow();
        assertThat(requeued.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(requeued.getAttempt()).isEqualTo(2);
        assertThat(requeued.getLastError()).contains("lease expired").contains("dead-worker");

        tx.executeWithoutResult(s -> lifecycle.claim(jobs.findById(id).orElseThrow(), 2, "dead-again", Duration.ofMillis(-1)));
        assertThat(reaper.reapOnce()).isEqualTo(1);
        Job failed = jobs.findById(id).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(outbox.findByAggregateIdOrderByIdAsc(id).getLast().getTopic()).isEqualTo(Topics.JOB_DLQ);
        assertThat(reaper.reapOnce()).isZero();
    }

    @Test
    void liveLeaseIsNotReclaimed() {
        UUID id = submit("healthy", 3, 1);
        dispatch.dispatchOnce();
        tx.executeWithoutResult(s -> lifecycle.claim(jobs.findById(id).orElseThrow(), 1, "alive", Duration.ofMinutes(5)));
        assertThat(reaper.reapOnce()).isZero();
        assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(JobStatus.RUNNING);
    }
}
