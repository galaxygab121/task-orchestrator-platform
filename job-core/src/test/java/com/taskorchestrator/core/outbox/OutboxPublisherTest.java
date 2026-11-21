package com.taskorchestrator.core.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.taskorchestrator.core.config.OrchestratorProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class OutboxPublisherTest {

    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    @SuppressWarnings("unchecked")
    @Test
    void brokerFailureIsRecordedAndRetriedLaterWithBackoff() {
        OutboxRepository repo = mock(OutboxRepository.class);
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(inv -> ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));

        OutboxEvent ok = new OutboxEvent(UUID.randomUUID(), UUID.randomUUID(), "t", "E", "{}", now, now);
        OutboxEvent bad = new OutboxEvent(UUID.randomUUID(), UUID.randomUUID(), "t", "E", "{}", now, now);
        when(repo.lockPublishable(any(), anyInt())).thenReturn(List.of(ok, bad));
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(inv -> {
            ProducerRecord<String, String> rec = inv.getArgument(0);
            if (rec.key().equals(bad.getAggregateId().toString())) {
                return CompletableFuture.failedFuture(new IllegalStateException("broker down\nretry"));
            }
            return CompletableFuture.completedFuture(mock(SendResult.class));
        });

        OrchestratorProperties props = new OrchestratorProperties(
                new OrchestratorProperties.Retry(Duration.ofSeconds(1), 2, Duration.ofSeconds(8)),
                new OrchestratorProperties.Outbox(true, 500, 50, Duration.ofSeconds(1), Duration.ofSeconds(60)),
                new OrchestratorProperties.Kafka(3, (short) 1));
        OutboxPublisher publisher = new OutboxPublisher(repo, kafka, tx, props, clock, new SimpleMeterRegistry());

        OutboxPublisher.BatchResult result = publisher.publishBatch();

        assertThat(result.claimed()).isEqualTo(2);
        assertThat(result.failures()).isEqualTo(1);
        assertThat(ok.getPublishedAt()).isEqualTo(now);
        assertThat(bad.getPublishedAt()).isNull();
        assertThat(bad.getPublishAttempts()).isEqualTo(1);
        assertThat(bad.getLastError()).isEqualTo("IllegalStateException: broker down retry");
        assertThat(bad.getAvailableAt()).isEqualTo(now.plusSeconds(1));

        publisher.publishBatch();
        assertThat(bad.getPublishAttempts()).isEqualTo(2);
        assertThat(bad.getAvailableAt()).isEqualTo(now.plusSeconds(2));
    }
}
