package com.taskorchestrator.scheduler;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param maxInFlight      cap on jobs that are QUEUED or RUNNING; bounds the worker backlog so the DB queue
 *                         (not Kafka) decides priority order
 * @param dispatchBatchSize max jobs dispatched per cycle
 * @param leaderLockTtl    TTL of the advisory Redis leader key
 */
@ConfigurationProperties("scheduler")
public record SchedulerProperties(
        @DefaultValue("8") int maxInFlight,
        @DefaultValue("20") int dispatchBatchSize,
        @DefaultValue("50") int reaperBatchSize,
        @DefaultValue("10s") Duration leaderLockTtl,
        @DefaultValue("scheduler-service") String instanceId) {
}
