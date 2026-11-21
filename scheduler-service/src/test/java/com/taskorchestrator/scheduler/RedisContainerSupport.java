package com.taskorchestrator.scheduler;

import com.taskorchestrator.testsupport.AbstractContainersIT;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

abstract class RedisContainerSupport extends AbstractContainersIT {

    private static GenericContainer<?> redis;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        synchronized (RedisContainerSupport.class) {
            if (redis == null) {
                redis = new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);
                redis.start();
            }
        }
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }
}
