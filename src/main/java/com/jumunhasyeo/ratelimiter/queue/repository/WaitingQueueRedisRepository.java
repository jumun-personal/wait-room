package com.jumunhasyeo.ratelimiter.queue.repository;

import com.jumunhasyeo.ratelimiter.queue.metrics.QueueMetrics;
import com.jumunhasyeo.ratelimiter.queue.redis.QueueRedisKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class WaitingQueueRedisRepository {

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;
    private final QueueMetrics queueMetrics;

    private static final DefaultRedisScript<String> ENTER_SCRIPT = loadScript("lua/queue-enter.lua", String.class);
    private static final DefaultRedisScript<String> POLL_SCRIPT = loadScript("lua/queue-poll.lua", String.class);
    private static final DefaultRedisScript<String> ACTIVE_CALLBACK_SCRIPT = loadScript("lua/active-callback.lua", String.class);
    private static final DefaultRedisScript<Long> CLEANUP_STALE_SCRIPT = loadScript("lua/cleanup-stale.lua", Long.class);
    private static final DefaultRedisScript<Long> CLEANUP_ACTIVE_SCRIPT = loadScript("lua/cleanup-active.lua", Long.class);

    private static <T> DefaultRedisScript<T> loadScript(String path, Class<T> resultType) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(resultType);
        return script;
    }

    /**
     * 대기열 진입 또는 즉시 입장 시도.
     *
     * @return "ALREADY_ACTIVE" | "ACTIVE:{token}" | "{rank}"
     */
    public String enterOrQueue(String userId, String queueToken, int maxTokens, int metaTtlSeconds, int activeTtlSeconds) {
        long startNanos = System.nanoTime();
        long now = clock.millis();
        try {
            String result = redisTemplate.execute(
                    ENTER_SCRIPT,
                    List.of(QueueRedisKeys.ACTIVE_SET, QueueRedisKeys.WAITING_QUEUE,
                            QueueRedisKeys.metaKey(userId), QueueRedisKeys.POLL_TRACKER,
                            QueueRedisKeys.activeMetaKey(queueToken)),
                    userId, String.valueOf(now), String.valueOf(maxTokens), queueToken,
                    String.valueOf(metaTtlSeconds), String.valueOf(activeTtlSeconds)
            );
            queueMetrics.recordRedisCommand("enter", classifyResult(result), Duration.ofNanos(System.nanoTime() - startNanos));
            return result;
        } catch (RuntimeException e) {
            queueMetrics.recordRedisCommandError("enter", e.getClass().getSimpleName());
            throw e;
        }
    }

    /**
     * 대기열 폴링.
     *
     * @return "ALREADY_ACTIVE" | "INVALID_TOKEN" | "NOT_IN_QUEUE" | "ADMITTED:{token}" | "{rank}"
     */
    public String poll(String userId, String expectedToken, String activeToken, int maxTokens, int metaTtlSeconds, int activeTtlSeconds) {
        long startNanos = System.nanoTime();
        long now = clock.millis();
        try {
            String result = redisTemplate.execute(
                    POLL_SCRIPT,
                    List.of(QueueRedisKeys.ACTIVE_SET, QueueRedisKeys.WAITING_QUEUE,
                            QueueRedisKeys.metaKey(userId), QueueRedisKeys.POLL_TRACKER,
                            QueueRedisKeys.activeMetaKey(activeToken)),
                    userId, String.valueOf(now), String.valueOf(maxTokens), expectedToken,
                    String.valueOf(metaTtlSeconds), activeToken, String.valueOf(activeTtlSeconds)
            );
            queueMetrics.recordRedisCommand("poll", classifyResult(result), Duration.ofNanos(System.nanoTime() - startNanos));
            return result;
        } catch (RuntimeException e) {
            queueMetrics.recordRedisCommandError("poll", e.getClass().getSimpleName());
            throw e;
        }
    }

    /**
     * Stale 폴링 사용자 정리.
     *
     * @return 제거된 사용자 수
     */
    public long cleanupStale(int stalePollSeconds, int batchSize) {
        long startNanos = System.nanoTime();
        long now = clock.millis();
        long thresholdMillis = stalePollSeconds * 1000L;
        try {
            Long removed = redisTemplate.execute(
                    CLEANUP_STALE_SCRIPT,
                    List.of(QueueRedisKeys.WAITING_QUEUE, QueueRedisKeys.POLL_TRACKER),
                    "q:waitroom:meta:", String.valueOf(now), String.valueOf(thresholdMillis), String.valueOf(batchSize)
            );
            long result = removed != null ? removed : 0;
            queueMetrics.recordRedisCommand("cleanup_stale", String.valueOf(result), Duration.ofNanos(System.nanoTime() - startNanos));
            return result;
        } catch (RuntimeException e) {
            queueMetrics.recordRedisCommandError("cleanup_stale", e.getClass().getSimpleName());
            throw e;
        }
    }

    /**
     * 만료된 active 사용자 정리.
     *
     * @return 제거된 사용자 수
     */
    public long cleanupExpiredActive(int activeTtlSeconds) {
        long startNanos = System.nanoTime();
        long now = clock.millis();
        long ttlMillis = activeTtlSeconds * 1000L;
        try {
            Long removed = redisTemplate.execute(
                    CLEANUP_ACTIVE_SCRIPT,
                    List.of(QueueRedisKeys.ACTIVE_SET),
                    String.valueOf(now), String.valueOf(ttlMillis)
            );
            long result = removed != null ? removed : 0;
            queueMetrics.recordRedisCommand("cleanup_active", String.valueOf(result), Duration.ofNanos(System.nanoTime() - startNanos));
            return result;
        } catch (RuntimeException e) {
            queueMetrics.recordRedisCommandError("cleanup_active", e.getClass().getSimpleName());
            throw e;
        }
    }

    /**
     * 현재 대기열 크기.
     */
    public long waitingQueueSize() {
        Long size = redisTemplate.opsForZSet().zCard(QueueRedisKeys.WAITING_QUEUE);
        return size != null ? size : 0;
    }

    /**
     * 현재 활성 사용자 수.
     */
    public long activeCount() {
        Long size = redisTemplate.opsForZSet().zCard(QueueRedisKeys.ACTIVE_SET);
        return size != null ? size : 0;
    }

    /**
     * 결제 콜백 처리.
     *
     * @return "REMOVED" | "REFRESHED" | "NOT_FOUND" | "MISMATCH" | "NOT_ACTIVE" | "INVALID_ACTION"
     */
    public String handlePaymentCallback(String userId, String activeToken, String action, int activeTtlSeconds) {
        long startNanos = System.nanoTime();
        long now = clock.millis();
        try {
            String result = redisTemplate.execute(
                    ACTIVE_CALLBACK_SCRIPT,
                    List.of(QueueRedisKeys.ACTIVE_SET, QueueRedisKeys.activeMetaKey(activeToken)),
                    userId, String.valueOf(now), String.valueOf(activeTtlSeconds), action
            );
            queueMetrics.recordRedisCommand("active_callback", classifyResult(result),
                    Duration.ofNanos(System.nanoTime() - startNanos));
            return result;
        } catch (RuntimeException e) {
            queueMetrics.recordRedisCommandError("active_callback", e.getClass().getSimpleName());
            throw e;
        }
    }

    private String classifyResult(String result) {
        if (result == null) {
            return "null";
        }
        int separator = result.indexOf(':');
        if (separator > 0) {
            return result.substring(0, separator);
        }
        if (result.chars().allMatch(Character::isDigit)) {
            return "rank";
        }
        return result;
    }
}
