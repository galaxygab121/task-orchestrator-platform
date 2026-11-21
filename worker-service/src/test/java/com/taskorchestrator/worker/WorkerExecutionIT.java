package com.taskorchestrator.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskorchestrator.contracts.JobStatus;
import com.taskorchestrator.contracts.JobType;
import com.taskorchestrator.contracts.Topics;
import com.taskorchestrator.contracts.events.EventTypes;
import com.taskorchestrator.contracts.events.JobDispatchEvent;
import com.taskorchestrator.core.domain.Job;
import com.taskorchestrator.core.domain.JobEvent;
import com.taskorchestrator.core.domain.JobEventRepository;
import com.taskorchestrator.core.domain.JobRepository;
import com.taskorchestrator.core.lifecycle.JobLifecycleService;
import com.taskorchestrator.core.messaging.EventCodec;
import com.taskorchestrator.testsupport.AbstractContainersIT;
import com.taskorchestrator.testsupport.TestKafka;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Worker end to end: jobs are put in QUEUED via the lifecycle service exactly like the scheduler does, the
 * worker's own outbox relay publishes the dispatch, and the listener executes it.
 */
@SpringBootTest(properties = {
    "worker.concurrency=3",
    "worker.task-timeout=4s",
    "worker.lease-duration=1500ms",
    "worker.heartbeat-interval=300ms",
    "orchestrator.retry.initial-delay=200ms",
    "orchestrator.retry.max-delay=400ms",
    "orchestrator.outbox.poll-interval-ms=100"
})
class WorkerExecutionIT extends AbstractContainersIT {

    private static final Duration WAIT = Duration.ofSeconds(40);

    @Autowired
    JobLifecycleService lifecycle;
    @Autowired
    JobRepository jobs;
    @Autowired
    JobEventRepository events;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    EventCodec codec;
    @Autowired
    ObjectMapper mapper;

    private UUID enqueue(JobType type, String payload, int maxRetries) {
        Job job = new Job(UUID.randomUUID(), type.name().toLowerCase(), type, payload, 3, 10, maxRetries);
        tx.executeWithoutResult(s -> {
            lifecycle.submit(job, "test");
            lifecycle.dispatch(jobs.findById(job.getId()).orElseThrow(), "test-scheduler");
        });
        return job.getId();
    }

    private Job awaitStatus(UUID id, JobStatus status) {
        await().atMost(WAIT).untilAsserted(() -> assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(status));
        return jobs.findById(id).orElseThrow();
    }

    private List<JobStatus> history(UUID id) {
        return events.findByJobIdOrderByIdAsc(id).stream().map(JobEvent::getToStatus).toList();
    }

    @Test
    void executesFibonacciAndStoresResult() throws Exception {
        UUID id = enqueue(JobType.FIBONACCI, "{\"n\": 30}", 0);
        Job done = awaitStatus(id, JobStatus.SUCCEEDED);
        assertThat(mapper.readTree(done.getResult()).get("value").asLong()).isEqualTo(832040L);
        assertThat(done.getStartedAt()).isNotNull();
        assertThat(done.getCompletedAt()).isAfterOrEqualTo(done.getStartedAt());
        assertThat(done.getLeaseOwner()).isNull();
    }

    @Test
    void executesDataSummary() throws Exception {
        UUID id = enqueue(JobType.DATA_SUMMARY, "{\"values\": [2, 4, 4, 4, 5, 5, 7, 9]}", 0);
        Job done = awaitStatus(id, JobStatus.SUCCEEDED);
        assertThat(mapper.readTree(done.getResult()).get("stdDev").asDouble()).isEqualTo(2.0);
        assertThat(mapper.readTree(done.getResult()).get("mean").asDouble()).isEqualTo(5.0);
    }

    @Test
    void transientFailuresAreRetriedWithBackoffThenSucceed() {
        UUID id = enqueue(JobType.FIBONACCI, "{\"n\": 10, \"failTransientlyTimes\": 2}", 3);
        Job done = awaitStatus(id, JobStatus.SUCCEEDED);
        assertThat(done.getAttempt()).isEqualTo(3);
        assertThat(history(id)).containsExactly(JobStatus.SUBMITTED, JobStatus.QUEUED, JobStatus.RUNNING, JobStatus.QUEUED,
                JobStatus.RUNNING, JobStatus.QUEUED, JobStatus.RUNNING, JobStatus.SUCCEEDED);
        assertThat(events.findByJobIdOrderByIdAsc(id).get(3).getDetail()).contains("retry 2/4 in 200ms");
    }

    @Test
    void exhaustedRetriesFailAndPublishSanitisedDlqEvent() {
        UUID id = enqueue(JobType.FIBONACCI, "{\"n\": 10, \"failTransientlyTimes\": 5}", 1);
        Job failed = awaitStatus(id, JobStatus.FAILED);
        assertThat(failed.getAttempt()).isEqualTo(2);
        assertThat(failed.getLastError()).contains("simulated transient failure on attempt 2");

        List<ConsumerRecord<String, String>> dlq = TestKafka.readUntil(Topics.JOB_DLQ, r -> id.toString().equals(r.key()), WAIT);
        assertThat(dlq).hasSize(1);
        assertThat(new String(dlq.getFirst().headers().lastHeader(EventTypes.HEADER).value(), StandardCharsets.UTF_8))
                .isEqualTo(EventTypes.JOB_DEAD_LETTERED);
        assertThat(dlq.getFirst().value()).contains("retries exhausted after 2 attempts").doesNotContain("failTransientlyTimes");
    }

    @Test
    void permanentFailureIsNotRetried() {
        UUID id = enqueue(JobType.DATA_SUMMARY, "{\"values\": [1], \"failPermanently\": true}", 3);
        Job failed = awaitStatus(id, JobStatus.FAILED);
        assertThat(failed.getAttempt()).isEqualTo(1);
        assertThat(failed.getLastError()).contains("simulated permanent failure");
        assertThat(TestKafka.readUntil(Topics.JOB_DLQ, r -> id.toString().equals(r.key()), WAIT)).isNotEmpty();
    }

    @Test
    void timeoutIsRetryableAndBounded() {
        UUID id = enqueue(JobType.SLEEP, "{\"durationMs\": 8000}", 0);
        Job failed = awaitStatus(id, JobStatus.FAILED);
        assertThat(failed.getLastError()).contains("timed out after 4000 ms");
    }

    @Test
    void duplicateDispatchDeliveriesNeverExecuteTwice() throws Exception {
        UUID id = enqueue(JobType.SLEEP, "{\"durationMs\": 600}", 0);
        String duplicate = codec.encode(new JobDispatchEvent(1, UUID.randomUUID(), id, 1, JobType.SLEEP, Instant.now()));
        for (int i = 0; i < 5; i++) {
            kafka.send(Topics.JOB_DISPATCH, id.toString(), duplicate).get();
        }
        awaitStatus(id, JobStatus.SUCCEEDED);
        // redeliver after completion as well
        kafka.send(Topics.JOB_DISPATCH, id.toString(), duplicate).get();
        Thread.sleep(1_500);

        assertThat(events.countByJobIdAndToStatus(id, JobStatus.RUNNING)).isEqualTo(1);
        assertThat(events.countByJobIdAndToStatus(id, JobStatus.SUCCEEDED)).isEqualTo(1);
        assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(JobStatus.SUCCEEDED);
    }

    @Test
    void heartbeatKeepsLeaseAliveBeyondInitialLeaseDuration() {
        UUID id = enqueue(JobType.SLEEP, "{\"durationMs\": 3000}", 0);
        awaitStatus(id, JobStatus.RUNNING);
        await().pollDelay(Duration.ofMillis(2_000)).atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            Job running = jobs.findById(id).orElseThrow();
            if (running.getStatus() == JobStatus.RUNNING) {
                // started >2s ago with a 1.5s lease: only renewals can keep this in the future
                assertThat(running.getLeaseExpiresAt()).isAfter(Instant.now());
            }
        });
        awaitStatus(id, JobStatus.SUCCEEDED);
    }

    @Test
    void cancelledJobIsNotExecuted() throws Exception {
        Job job = new Job(UUID.randomUUID(), "cancel", JobType.FIBONACCI, "{\"n\": 3}", 3, 10, 0);
        tx.executeWithoutResult(s -> {
            lifecycle.submit(job, "test");
            Job managed = jobs.findById(job.getId()).orElseThrow();
            lifecycle.dispatch(managed, "test");
            lifecycle.cancel(managed, "api");
        });
        Thread.sleep(2_000);
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.CANCELLED);
        assertThat(events.countByJobIdAndToStatus(job.getId(), JobStatus.RUNNING)).isZero();
    }

    @Test
    void poisonDispatchMessageIsDeadLettered() throws Exception {
        String key = "poison-" + UUID.randomUUID();
        kafka.send(Topics.JOB_DISPATCH, key, "{\"schemaVersion\": 99, \"jobId\": \"" + UUID.randomUUID() + "\", \"attempt\": 1}").get();
        List<ConsumerRecord<String, String>> dlq = TestKafka.readUntil(Topics.JOB_DLQ, r -> key.equals(r.key()), WAIT);
        assertThat(dlq).isNotEmpty();
        assertThat(dlq.getFirst().headers().lastHeader("kafka_dlt-exception-fqcn")).isNotNull();
    }
}
