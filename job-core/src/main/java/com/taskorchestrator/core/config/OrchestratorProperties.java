package com.taskorchestrator.core.config;

import com.taskorchestrator.contracts.RetryBackoff;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Shared, env-overridable settings (e.g. ORCHESTRATOR_RETRY_INITIAL_DELAY=2s). */
@ConfigurationProperties("orchestrator")
public record OrchestratorProperties(
        @DefaultValue Retry retry,
        @DefaultValue Outbox outbox,
        @DefaultValue Kafka kafka) {

    public record Retry(
            @DefaultValue("1s") Duration initialDelay,
            @DefaultValue("2.0") double multiplier,
            @DefaultValue("8s") Duration maxDelay) {

        public RetryBackoff toBackoff() {
            return new RetryBackoff(initialDelay, multiplier, maxDelay);
        }
    }

    public record Outbox(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("500") long pollIntervalMs,
            @DefaultValue("50") int batchSize,
            @DefaultValue("10s") Duration sendTimeout,
            @DefaultValue("60s") Duration maxRetryDelay) {
    }

    public record Kafka(
            @DefaultValue("3") int topicPartitions,
            @DefaultValue("1") short replicationFactor) {
    }
}
