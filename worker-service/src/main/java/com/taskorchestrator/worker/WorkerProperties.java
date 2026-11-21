package com.taskorchestrator.worker;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param concurrency       Kafka listener threads == max concurrently executing tasks in this instance
 * @param taskTimeout       hard limit per attempt; exceeding it is a retryable failure
 * @param leaseDuration     how long a claim stays valid without a heartbeat
 * @param heartbeatInterval how often a running attempt extends its lease (must be well below leaseDuration)
 */
@ConfigurationProperties("worker")
public record WorkerProperties(
        @DefaultValue("2") int concurrency,
        @DefaultValue("20s") Duration taskTimeout,
        @DefaultValue("30s") Duration leaseDuration,
        @DefaultValue("10s") Duration heartbeatInterval,
        @DefaultValue("worker") String instanceId) {

    public WorkerProperties {
        if (concurrency < 1 || concurrency > 64) {
            throw new IllegalArgumentException("worker.concurrency must be 1..64");
        }
        if (heartbeatInterval.compareTo(leaseDuration) >= 0) {
            throw new IllegalArgumentException("worker.heartbeat-interval must be shorter than worker.lease-duration");
        }
    }
}
