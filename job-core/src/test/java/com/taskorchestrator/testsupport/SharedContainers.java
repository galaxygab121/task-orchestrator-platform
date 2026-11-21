package com.taskorchestrator.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * JVM-wide singleton Postgres 16 + Kafka (KRaft) containers, started lazily on first use and removed by
 * Testcontainers' Ryuk at JVM exit. Shared by all integration tests to keep the suite fast.
 */
public final class SharedContainers {

    private static PostgreSQLContainer<?> postgres;
    private static ConfluentKafkaContainer kafka;

    private SharedContainers() {
    }

    public static synchronized void start() {
        if (postgres == null) {
            postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
                    .withDatabaseName("orchestrator")
                    .withUsername("orchestrator")
                    .withPassword("orchestrator");
            postgres.start();
        }
        if (kafka == null) {
            kafka = new ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
            kafka.start();
        }
    }

    public static void register(DynamicPropertyRegistry registry) {
        start();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    public static String kafkaBootstrapServers() {
        start();
        return kafka.getBootstrapServers();
    }
}
