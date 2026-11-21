package com.taskorchestrator.core.outbox;

import com.taskorchestrator.contracts.ErrorSanitizer;
import com.taskorchestrator.contracts.events.EventTypes;
import com.taskorchestrator.core.config.OrchestratorProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Relays committed outbox rows to Kafka.
 *
 * <p>Delivery is at-least-once: a row is marked published only after the broker acknowledged it
 * (acks=all). If the process dies between the ack and the commit, the row is sent again after restart,
 * so every consumer is idempotent (it checks DB state, never trusts the event alone). Rows are claimed
 * with {@code FOR UPDATE SKIP LOCKED}, so every service instance can run a publisher safely. A failed
 * send is recorded on the row ({@code publish_attempts}, {@code last_error}) and retried with
 * exponential backoff capped by {@code orchestrator.outbox.max-retry-delay}; this covers DLQ events too.
 */
@Component
@ConditionalOnProperty(prefix = "orchestrator.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;
    private final OrchestratorProperties.Outbox props;
    private final Clock clock;
    private final Counter published;
    private final Counter failed;

    public OutboxPublisher(OutboxRepository repository, KafkaTemplate<String, String> kafkaTemplate,
            TransactionTemplate transactionTemplate, OrchestratorProperties properties, Clock clock, MeterRegistry meters) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.transactionTemplate = transactionTemplate;
        this.props = properties.outbox();
        this.clock = clock;
        this.published = Counter.builder("orchestrator.outbox.published").register(meters);
        this.failed = Counter.builder("orchestrator.outbox.publish.failures").register(meters);
        Gauge.builder("orchestrator.outbox.pending", repository, OutboxRepository::countByPublishedAtIsNull)
                .description("Outbox rows not yet acknowledged by Kafka")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${orchestrator.outbox.poll-interval-ms:500}", initialDelay = 1_000)
    public void poll() {
        // Drain in bounded batches; stop as soon as a batch is not full or had failures.
        for (int i = 0; i < 10; i++) {
            BatchResult result = publishBatch();
            if (result.claimed() < props.batchSize() || result.failures() > 0) {
                return;
            }
        }
    }

    /** Publishes one bounded batch inside a single DB transaction. Visible for tests. */
    public BatchResult publishBatch() {
        BatchResult result = transactionTemplate.execute(status -> {
            Instant now = clock.instant();
            List<OutboxEvent> batch = repository.lockPublishable(now, props.batchSize());
            int failures = 0;
            for (OutboxEvent event : batch) {
                if (!send(event)) {
                    failures++;
                }
            }
            return new BatchResult(batch.size(), failures);
        });
        return result == null ? new BatchResult(0, 0) : result;
    }

    private boolean send(OutboxEvent event) {
        MDC.put("jobId", event.getAggregateId().toString());
        try {
            ProducerRecord<String, String> record = new ProducerRecord<>(event.getTopic(), event.getAggregateId().toString(),
                    event.getPayload());
            record.headers().add(EventTypes.HEADER, event.getEventType().getBytes(StandardCharsets.UTF_8));
            record.headers().add(EventTypes.HEADER_EVENT_ID, event.getEventId().toString().getBytes(StandardCharsets.UTF_8));
            kafkaTemplate.send(record).get(props.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            event.markPublished(clock.instant());
            published.increment();
            log.debug("Published outbox event {} type={} topic={}", event.getEventId(), event.getEventType(), event.getTopic());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordFailure(event, e);
            return false;
        } catch (Exception e) {
            recordFailure(event, e);
            return false;
        } finally {
            MDC.remove("jobId");
        }
    }

    private void recordFailure(OutboxEvent event, Exception e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        long delayMs = Math.min(props.maxRetryDelay().toMillis(), 1_000L << Math.min(event.getPublishAttempts(), 16));
        event.markFailed(ErrorSanitizer.describe(cause), clock.instant().plus(Duration.ofMillis(delayMs)));
        failed.increment();
        log.warn("Outbox publish failed for event {} (attempt {}), retry in {} ms: {}", event.getEventId(),
                event.getPublishAttempts(), delayMs, event.getLastError());
    }

    public record BatchResult(int claimed, int failures) {
    }
}
