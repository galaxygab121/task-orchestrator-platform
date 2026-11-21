package coretest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskorchestrator.contracts.InvalidJobTransitionException;
import com.taskorchestrator.contracts.JobStatus;
import com.taskorchestrator.contracts.JobType;
import com.taskorchestrator.contracts.Topics;
import com.taskorchestrator.core.domain.Job;
import com.taskorchestrator.core.domain.JobEvent;
import com.taskorchestrator.core.domain.JobEventRepository;
import com.taskorchestrator.core.domain.JobRepository;
import com.taskorchestrator.core.lifecycle.JobLifecycleService;
import com.taskorchestrator.core.lifecycle.JobLifecycleService.FailureOutcome;
import com.taskorchestrator.core.outbox.OutboxEvent;
import com.taskorchestrator.core.outbox.OutboxRepository;
import com.taskorchestrator.testsupport.AbstractContainersIT;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(classes = CoreTestApplication.class, properties = {
    "orchestrator.outbox.enabled=false",
    "orchestrator.retry.initial-delay=2s",
    "orchestrator.retry.max-delay=8s"
})
class JobLifecycleIT extends AbstractContainersIT {

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

    private UUID submit(int maxRetries) {
        Job job = new Job(UUID.randomUUID(), "t", JobType.FIBONACCI, "{\"n\": 10}", 3, 10, maxRetries);
        tx.executeWithoutResult(s -> lifecycle.submit(job, "test"));
        return job.getId();
    }

    private boolean claim(UUID id, int attempt, String worker) {
        Boolean claimed = tx.execute(s -> lifecycle.claim(jobs.findById(id).orElseThrow(), attempt, worker, Duration.ofSeconds(30)));
        return Boolean.TRUE.equals(claimed);
    }

    @Test
    void flywayMigrationApplied() {
        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '1' and success", Integer.class);
        assertThat(applied).isEqualTo(1);
    }

    @Test
    void submitPersistsJobAuditAndOutboxAtomically() {
        UUID id = submit(2);
        Job job = jobs.findById(id).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.SUBMITTED);
        assertThat(job.getPayload()).contains("\"n\"").contains("10");
        assertThat(events.findByJobIdOrderByIdAsc(id)).extracting(JobEvent::getToStatus).containsExactly(JobStatus.SUBMITTED);
        List<OutboxEvent> rows = outbox.findByAggregateIdOrderByIdAsc(id);
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.getTopic()).isEqualTo(Topics.JOB_SUBMITTED);
            assertThat(r.getPayload()).contains(id.toString());
            assertThat(r.getPublishedAt()).isNull();
        });
    }

    @Test
    void failedTransactionLeavesNoOutboxRow() {
        UUID id = UUID.randomUUID();
        try {
            tx.executeWithoutResult(s -> {
                lifecycle.submit(new Job(id, "t", JobType.SLEEP, "{\"durationMs\": 1}", 3, 1, 0), "test");
                throw new IllegalStateException("simulated crash before commit");
            });
        } catch (IllegalStateException expected) {
            // rollback expected
        }
        assertThat(jobs.findById(id)).isEmpty();
        assertThat(outbox.findByAggregateIdOrderByIdAsc(id)).isEmpty();
    }

    @Test
    void fullLifecycleWithRetryThenSuccess() {
        UUID id = submit(1);
        tx.executeWithoutResult(s -> lifecycle.dispatch(jobs.findById(id).orElseThrow(), "scheduler"));
        boolean claimed = Boolean.TRUE.equals(tx.execute(s ->
                lifecycle.claim(jobs.findById(id).orElseThrow(), 1, "w1", Duration.ofSeconds(30))));
        assertThat(claimed).isTrue();

        FailureOutcome outcome = tx.execute(s -> lifecycle.fail(jobs.findById(id).orElseThrow(), "flaky\nnetwork", true, "w1"));
        assertThat(outcome.kind()).isEqualTo(FailureOutcome.Kind.RETRY_SCHEDULED);
        assertThat(outcome.retryDelay()).isEqualTo(Duration.ofSeconds(2));
        Job retried = jobs.findById(id).orElseThrow();
        assertThat(retried.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(retried.getAttempt()).isEqualTo(2);
        assertThat(retried.getLastError()).isEqualTo("flaky network");
        assertThat(retried.getLeaseOwner()).isNull();
        OutboxEvent retryDispatch = outbox.findByAggregateIdOrderByIdAsc(id).getLast();
        assertThat(retryDispatch.getTopic()).isEqualTo(Topics.JOB_DISPATCH);
        assertThat(retryDispatch.getPayload()).contains("\"attempt\": 2");
        assertThat(retryDispatch.getAvailableAt()).isAfter(retryDispatch.getCreatedAt().plusMillis(1_900));

        // a stale duplicate of attempt 1 must not be claimable any more
        assertThat(claim(id, 1, "w2")).isFalse();
        assertThat(claim(id, 2, "w2")).isTrue();
        tx.executeWithoutResult(s -> lifecycle.succeed(jobs.findById(id).orElseThrow(), "{\"value\": 55}", "w2"));

        Job done = jobs.findById(id).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(done.getResult()).contains("55");
        assertThat(done.getCompletedAt()).isNotNull();
        assertThat(done.getStartedAt()).isNotNull();
        assertThat(events.findByJobIdOrderByIdAsc(id)).extracting(JobEvent::getToStatus).containsExactly(
                JobStatus.SUBMITTED, JobStatus.QUEUED, JobStatus.RUNNING, JobStatus.QUEUED, JobStatus.RUNNING, JobStatus.SUCCEEDED);
    }

    @Test
    void exhaustedRetriesFailAndWriteSanitisedDlqEvent() {
        UUID id = submit(0);
        tx.executeWithoutResult(s -> lifecycle.dispatch(jobs.findById(id).orElseThrow(), "scheduler"));
        tx.executeWithoutResult(s -> lifecycle.claim(jobs.findById(id).orElseThrow(), 1, "w1", Duration.ofSeconds(30)));
        FailureOutcome outcome = tx.execute(s -> lifecycle.fail(jobs.findById(id).orElseThrow(), "still broken", true, "w1"));

        assertThat(outcome.kind()).isEqualTo(FailureOutcome.Kind.DEAD_LETTERED);
        assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(JobStatus.FAILED);
        OutboxEvent dlq = outbox.findByAggregateIdOrderByIdAsc(id).getLast();
        assertThat(dlq.getTopic()).isEqualTo(Topics.JOB_DLQ);
        assertThat(dlq.getPayload()).contains("retries exhausted after 1 attempts: still broken").doesNotContain("\"n\"");
    }

    @Test
    void cancelOnlyBeforeRunning() {
        UUID queued = submit(0);
        tx.executeWithoutResult(s -> lifecycle.dispatch(jobs.findById(queued).orElseThrow(), "scheduler"));
        tx.executeWithoutResult(s -> lifecycle.cancel(jobs.findById(queued).orElseThrow(), "api"));
        assertThat(jobs.findById(queued).orElseThrow().getStatus()).isEqualTo(JobStatus.CANCELLED);
        // worker receiving the in-flight dispatch afterwards must not run it
        assertThat(claim(queued, 1, "w1")).isFalse();

        UUID running = submit(0);
        tx.executeWithoutResult(s -> lifecycle.dispatch(jobs.findById(running).orElseThrow(), "scheduler"));
        tx.executeWithoutResult(s -> lifecycle.claim(jobs.findById(running).orElseThrow(), 1, "w1", Duration.ofSeconds(5)));
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> lifecycle.cancel(jobs.findById(running).orElseThrow(), "api")))
                .isInstanceOf(InvalidJobTransitionException.class);
    }
}
