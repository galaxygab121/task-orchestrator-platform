package com.taskorchestrator.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.taskorchestrator.contracts.JobStatus;
import com.taskorchestrator.contracts.JobType;
import com.taskorchestrator.contracts.Topics;
import com.taskorchestrator.contracts.events.JobSubmittedEvent;
import com.taskorchestrator.core.domain.Job;
import com.taskorchestrator.core.domain.JobEventRepository;
import com.taskorchestrator.core.domain.JobRepository;
import com.taskorchestrator.core.lifecycle.JobLifecycleService;
import com.taskorchestrator.core.messaging.EventCodec;
import com.taskorchestrator.testsupport.DatabaseCleaner;
import com.taskorchestrator.testsupport.TestKafka;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;

/** Full scheduler: Kafka listener, polling dispatcher and outbox relay all running, as in production. */
@SpringBootTest(properties = {"scheduler.dispatch-interval-ms=500", "scheduler.initial-delay-ms=0"})
@DirtiesContext
class DuplicateSubmissionIT extends RedisContainerSupport {

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
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redis;

    @BeforeEach
    void clean() {
        DatabaseCleaner.truncateAll(jdbc);
        // a cached context from another test class may still hold the advisory leader key
        redis.delete(DispatchCoordinator.LEADER_KEY);
    }

    @Test
    void duplicateSubmissionEventsQueueTheJobExactlyOnceAndPublishOneDispatch() throws Exception {
        Job job = new Job(UUID.randomUUID(), "dup", JobType.SLEEP, "{\"durationMs\": 5}", 3, 5, 0);
        tx.executeWithoutResult(s -> lifecycle.submit(job, "test"));
        String duplicate = codec.encode(new JobSubmittedEvent(1, UUID.randomUUID(), job.getId(), JobType.SLEEP, 3, Instant.now()));
        for (int i = 0; i < 3; i++) {
            kafka.send(Topics.JOB_SUBMITTED, job.getId().toString(), duplicate).get();
        }

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.QUEUED));
        assertThat(TestKafka.readUntil(Topics.JOB_DISPATCH, r -> job.getId().toString().equals(r.key()), Duration.ofSeconds(20)))
                .hasSize(1);
        assertThat(events.countByJobIdAndToStatus(job.getId(), JobStatus.QUEUED)).isEqualTo(1);
    }

    @Test
    void poisonSubmissionGoesToDlqAndDoesNotBlockLaterMessages() throws Exception {
        String marker = "poison-" + UUID.randomUUID();
        kafka.send(Topics.JOB_SUBMITTED, marker, "{not json").get();

        assertThat(TestKafka.readUntil(Topics.JOB_DLQ, r -> marker.equals(r.key()), Duration.ofSeconds(30))).isNotEmpty();

        Job job = new Job(UUID.randomUUID(), "after-poison", JobType.FIBONACCI, "{\"n\": 2}", 3, 10, 0);
        tx.executeWithoutResult(s -> lifecycle.submit(job, "test"));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.QUEUED));
    }
}
