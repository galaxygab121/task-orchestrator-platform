package com.taskorchestrator.scheduler;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Advisory leader election via Redis ({@code SET NX PX} + owner-checked renew). When several scheduler
 * instances run, only the leader scans the queue, which avoids pointless contention.
 *
 * <p>Redis is <b>not</b> relied on for correctness: the dispatch transaction also takes a Postgres advisory
 * lock and row locks. If Redis is unreachable this coordinator lets every instance proceed. Non-leaders
 * ignore submission wake-ups; the leader's polling loop ({@code scheduler.dispatch-interval-ms}) picks
 * the job up, and a crashed leader is replaced once its key expires ({@code scheduler.leader-lock-ttl}).
 */
@Component
public class DispatchCoordinator {

    static final String LEADER_KEY = "orchestrator:scheduler:leader";
    private static final Logger log = LoggerFactory.getLogger(DispatchCoordinator.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end", Long.class);
    private static final DefaultRedisScript<Long> RENEW = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final SchedulerProperties props;
    private final String ownerId;
    private final AtomicLong lastWarning = new AtomicLong();

    public DispatchCoordinator(StringRedisTemplate redis, SchedulerProperties props) {
        this.redis = redis;
        this.props = props;
        this.ownerId = props.instanceId() + ":" + UUID.randomUUID();
    }

    public boolean isLeader() {
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent(LEADER_KEY, ownerId, props.leaderLockTtl());
            if (Boolean.TRUE.equals(acquired)) {
                return true;
            }
            Long renewed = redis.execute(RENEW, List.of(LEADER_KEY), ownerId, Long.toString(props.leaderLockTtl().toMillis()));
            return renewed != null && renewed == 1L;
        } catch (DataAccessException e) {
            long now = System.currentTimeMillis();
            if (now - lastWarning.get() > 60_000) {
                lastWarning.set(now);
                log.warn("Redis unavailable for leader election, falling back to DB locking only: {}", e.getMessage());
            }
            return true;
        }
    }

    /** Graceful shutdown: hand leadership over immediately instead of waiting for the TTL. */
    @PreDestroy
    public void release() {
        try {
            redis.execute(RELEASE, List.of(LEADER_KEY), ownerId);
        } catch (DataAccessException e) {
            log.debug("Could not release leader key: {}", e.getMessage());
        }
    }

    String ownerId() {
        return ownerId;
    }
}
