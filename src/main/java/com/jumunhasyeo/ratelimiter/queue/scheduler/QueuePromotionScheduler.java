package com.jumunhasyeo.ratelimiter.queue.scheduler;

import com.jumunhasyeo.ratelimiter.queue.config.QueueProperties;
import com.jumunhasyeo.ratelimiter.queue.config.QueueRuntimeConfig;
import com.jumunhasyeo.ratelimiter.queue.redis.QueueRedisKeys;
import com.jumunhasyeo.ratelimiter.queue.repository.WaitingQueueRedisRepository;
import com.jumunhasyeo.ratelimiter.queue.resilience.QueueResilienceExecutor;
import com.jumunhasyeo.ratelimiter.queue.resilience.QueueRedisTransientException;
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
public class QueuePromotionScheduler {

    private static final int PROMOTION_SCAN_LIMIT = 100;
    private static final int STALE_THRESHOLD_MULTIPLIER = 3;
    private static final long PROMOTION_INTERVAL_MS = 1000L;

    private final WaitingQueueRedisRepository repository;
    private final QueueProperties properties;
    private final QueueRuntimeConfig runtimeConfig;
    private final RedisSchedulerLock schedulerLock;
    private final QueueResilienceExecutor resilienceExecutor;

    @Scheduled(fixedDelay = PROMOTION_INTERVAL_MS)
    public void promote() {
        Duration lockTtl = Duration.ofMillis(Math.min(properties.cleanupLockTtlMs(), PROMOTION_INTERVAL_MS));
        try {
            boolean locked = resilienceExecutor.executeScheduler(() ->
                    schedulerLock.tryLock(QueueRedisKeys.PROMOTION_LOCK, lockTtl)
            );
            if (!locked) {
                return;
            }

            int stalePollSeconds = properties.maxPollIntervalSeconds() * STALE_THRESHOLD_MULTIPLIER;
            long promoted = resilienceExecutor.executeScheduler(() ->
                    repository.promoteWaitingUsers(
                            runtimeConfig.maxActiveTokens(),
                            properties.activeTtlSeconds(),
                            stalePollSeconds,
                            PROMOTION_SCAN_LIMIT
                    )
            );
            if (promoted > 0) {
                log.info("대기열에서 활성 세트로 {}명 승격", promoted);
            }
        } catch (QueueRedisTransientException e) {
            log.warn("대기열 승격 중 Redis 일시 장애가 발생했습니다", e);
        }
    }
}
