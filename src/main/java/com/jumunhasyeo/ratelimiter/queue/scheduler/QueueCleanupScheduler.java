package com.jumunhasyeo.ratelimiter.queue.scheduler;

import com.jumunhasyeo.ratelimiter.queue.config.QueueProperties;
import com.jumunhasyeo.ratelimiter.queue.redis.QueueRedisKeys;
import com.jumunhasyeo.ratelimiter.queue.repository.WaitingQueueRedisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class QueueCleanupScheduler {

    private static final int CLEANUP_BATCH_SIZE = 100;
    private static final int STALE_THRESHOLD_MULTIPLIER = 3;

    private final WaitingQueueRedisRepository repository;
    private final QueueProperties properties;
    private final RedisSchedulerLock schedulerLock;

    @Scheduled(fixedDelayString = "${queue.cleanup-interval-ms}")
    public void cleanup() {
        Duration lockTtl = Duration.ofMillis(properties.cleanupLockTtlMs());
        if (!schedulerLock.tryLock(QueueRedisKeys.CLEANUP_LOCK, lockTtl)) {
            return;
        }

        cleanupStalePolling();
        cleanupExpiredActive();
    }

    private void cleanupStalePolling() {
        int stalePollSeconds = properties.maxPollIntervalSeconds() * STALE_THRESHOLD_MULTIPLIER;
        long removed = repository.cleanupStale(stalePollSeconds, CLEANUP_BATCH_SIZE);
        if (removed > 0) {
            log.info("대기열에서 stale 사용자 {}명 제거", removed);
        }
    }

    private void cleanupExpiredActive() {
        long removed = repository.cleanupExpiredActive(properties.activeTtlSeconds());
        if (removed > 0) {
            log.info("활성 세트에서 만료된 사용자 {}명 제거", removed);
        }
    }
}
