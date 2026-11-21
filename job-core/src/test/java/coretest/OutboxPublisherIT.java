package coretest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.taskorchestrator.contracts.JobType;
import com.taskorchestrator.contracts.Topics;
import com.taskorchestrator.contracts.events.EventTypes;
import com.taskorchestrator.core.domain.Job;
import com.taskorchestrator.core.lifecycle.JobLifecycleService;
import com.taskorchestrator.core.outbox.OutboxPublisher;
import com.taskorchestrator.core.outbox.OutboxRepository;
import com.taskorchestrator.testsupport.AbstractContainersIT;
import com.taskorchestrator.testsupport.TestKafka;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(classes = CoreTestApplication.class)
class OutboxPublisherIT extends AbstractContainersIT {

    @Autowired
    JobLifecycleService lifecycle;
    @Autowired
    OutboxRepository outbox;
    @Autowired
    OutboxPublisher publisher;
    @Autowired
    TransactionTemplate tx;

    @Test
    void committedOutboxRowIsPublishedToKafkaAndMarkedSent() {
        Job job = new Job(UUID.randomUUID(), "pub", JobType.FIBONACCI, "{\"n\": 5}", 4, 10, 0);
        tx.executeWithoutResult(s -> lifecycle.submit(job, "test"));

        publisher.publishBatch();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(outbox.findByAggregateIdOrderByIdAsc(job.getId()))
                        .allSatisfy(row -> assertThat(row.getPublishedAt()).isNotNull()));

        List<ConsumerRecord<String, String>> records = TestKafka.readUntil(Topics.JOB_SUBMITTED,
                r -> job.getId().toString().equals(r.key()), Duration.ofSeconds(20));
        assertThat(records).isNotEmpty();
        ConsumerRecord<String, String> record = records.getFirst();
        assertThat(record.value()).contains(job.getId().toString()).contains("\"priority\": 4");
        assertThat(new String(record.headers().lastHeader(EventTypes.HEADER).value(), StandardCharsets.UTF_8))
                .isEqualTo(EventTypes.JOB_SUBMITTED);
    }
}
