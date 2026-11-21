package com.taskorchestrator.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

class DispatchCoordinatorTest {

    private final SchedulerProperties props = new SchedulerProperties(8, 20, 50, Duration.ofSeconds(10), "s1");

    @SuppressWarnings("unchecked")
    @Test
    void leaderWhenKeyAcquiredOrRenewedByOwner() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        DispatchCoordinator coordinator = new DispatchCoordinator(redis, props);

        when(ops.setIfAbsent(eq(DispatchCoordinator.LEADER_KEY), eq(coordinator.ownerId()), any(Duration.class))).thenReturn(true);
        assertThat(coordinator.isLeader()).isTrue();

        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(1L);
        assertThat(coordinator.isLeader()).as("renewed by owner").isTrue();

        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(0L);
        assertThat(coordinator.isLeader()).as("someone else leads").isFalse();
    }

    @Test
    void fallsBackToDbLockingWhenRedisIsDown() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("connection refused"));
        assertThat(new DispatchCoordinator(redis, props).isLeader()).isTrue();
    }
}
