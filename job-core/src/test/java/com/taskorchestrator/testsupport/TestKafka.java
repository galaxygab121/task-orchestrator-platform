package com.taskorchestrator.testsupport;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

/** Minimal raw consumer used by tests to assert what actually reached a topic. */
public final class TestKafka {

    private TestKafka() {
    }

    /** Reads {@code topic} from the beginning until {@code match} finds a record or the deadline passes. */
    public static List<ConsumerRecord<String, String>> readUntil(String topic, Predicate<ConsumerRecord<String, String>> match,
            Duration timeout) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, SharedContainers.kafkaBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> matches = new ArrayList<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(topic));
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(250))) {
                    if (match.test(record)) {
                        matches.add(record);
                    }
                }
                if (!matches.isEmpty()) {
                    // one more short poll to collect duplicates that may follow
                    consumer.poll(Duration.ofMillis(500)).forEach(r -> {
                        if (match.test(r)) {
                            matches.add(r);
                        }
                    });
                    return matches;
                }
            }
        }
        return matches;
    }
}
