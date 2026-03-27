package com.jumunhasyeo.ratelimiter.queue.repository;

import com.jumunhasyeo.ratelimiter.queue.metrics.QueueMetrics;
import com.jumunhasyeo.ratelimiter.queue.redis.QueueRedisKeys;
import com.jumunhasyeo.ratelimiter.queue.resilience.QueueRedisExceptionClassifier;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class WaitingQueueRedisRepository {

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;
    private final QueueMetrics queueMetrics;
    private final QueueRedisExceptionClassifier exceptionClassifier;

    private static final DefaultRedisScript<String> ENTER_SCRIPT = loadScript("lua/queue-enter.lua", String.class);
    private static final DefaultRedisScript<String> POLL_SCRIPT = loadScript("lua/queue-poll.lua", String.class);
    private static final DefaultRedisScript<String> ACTIVE_CALLBACK_SCRIPT = loadScript("lua/active-callback.lua", String.class);
    private static final DefaultRedisScript<Long> CLEANUP_STALE_SCRIPT = loadScript("lua/cleanup-stale.lua", Long.class);
    private static final DefaultRedisScript<Long> CLEANUP_ACTIVE_SCRIPT = loadScript("lua/cleanup-active.lua", Long.class);
    private static final DefaultRedisScript<Long> PROMOTE_WAITING_SCRIPT = loadScript("lua/queue-promote.lua", Long.class);

    private static <T> DefaultRedisScript<T> loadScript(String path, Class<T> resultType) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(resultType);
        return script;
    }

    /**
     * active 상태를 확인하고, 아니라면 대기열에 등록한다.
     *
     * @return "ALREADY_ACTIVE:{token}" | "QUEUED:{rank}"
     */
    public String enterOrQueue(String userId, String activeToken, int activeTtlSeconds) {
        long startNanos = System.nanoTime();
        long now = clock.millis();
        try {
            String result = redisTemplate.execute(
                    ENTER_SCRIPT,
                    List.of(
                            QueueRedisKeys.ACTIVE_SET,
                            QueueRedisKeys.WAITING_QUEUE,
                            QueueRedisKeys.POLL_TRACKER,
                            QueueRedisKeys.activeTokenKey(userId)
                    ),
                    userId, String.valueOf(now), activeToken, String.valueOf(activeTtlSeconds)
            );
            queueMetrics.recordRedisCommand("enter", classifyResult(result), Duration.ofNanos(System.nanoTime() - startNanos));
            return result;
        } catch (RuntimeException e) {
            queueMetrics.recordRedisCommandError("enter", e.getClass().getSimpleName());
            throw exceptionClassifier.wrapIfTransient("enter", e);
        }
    }

    /**
     * 대기열 폴링.
     *
     * @return "ALREADY_ACTIVE:{token}" | "{rank}"
     */
    public String poll(String userId, String activeToken, int activeTtlSeconds) {
        long startNanos = System.nanoTime();
        long now = clock.millis();
        try {
            String result = redisTemplate.execute(
                    POLL_SCRIPT,
                    List.of(QueueRedisKeys.ACTIVE_SET, QueueRedisKeys.WAITING_QUEUE,
                            QueueRedisKeys.POLL_TRACKER,
                            QueueRedisKeys.activeTokenKey(userId)),
                    userId, String.valueOf(now), activeToken, String.valueOf(activeTtlSeconds)
            );
            queueMetrics.recordRedisCommand("poll", classifyResult(result), Duration.ofNanos(System.nanoTime() - startNanos));
            return result;
        } catch (RuntimeException e) {
            queueMetrics.recordRedisCommandError("poll", e.getClass().getSimpleName());
            throw exceptionClassifier.wrapIfTransient("poll", e);
        }
    }

    /**
     * 대기열에서 활성 세트로 승격.
     *
     * @return 승격된 사용자 수
     */
    public long promoteWaitingUsers(int maxTokens, int activeTtlSeconds, int stalePollSeconds, int scanLimit) {
        long startNanos = System.nanoTime();
        long now = clock.millis();
        long staleThresholdMillis = stalePollSeconds * 1000L;
        List<String> arguments = new ArrayList<>(6 + scanLimit);
        arguments.add(String.valueOf(now));
        arguments.add(String.valueOf(maxTokens));
        arguments.add(String.valueOf(activeTtlSeconds));
        arguments.add(String.valueOf(staleThresholdMillis));
        arguments.add(String.valueOf(scanLimit));
        arguments.add(QueueRedisKeys.activeTokenPrefix());
        for (int i = 0; i < scanLimit; i++) {
            arguments.add(UUID.randomUUID().toString());
        }
        try {
            Long promoted = redisTemplate.execute(
                    PROMOTE_WAITING_SCRIPT,
                    List.of(
                            QueueRedisKeys.ACTIVE_SET,
                            QueueRedisKeys.WAITING_QUEUE,
                            QueueRedisKeys.POLL_TRACKER
                    ),
                    arguments.toArray()
            );
            long result = promoted != null ? promoted : 0;
            queueMetrics.recordRedisCommand("promote", String.valueOf(result),
                    Duration.ofNanos(System.nanoTime() - startNanos));
            return result;
        } catch (RuntimeException e) {
            queueMetrics.recordRedisCommandError("promote", e.getClass().getSimpleName());
            throw exceptionClassifier.wrapIfTransient("promote", e);
        }
    }

    /**
     * 대기열 Stale 폴링 사용자 정리.
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
                    String.valueOf(now), String.valueOf(thresholdMillis), String.valueOf(batchSize)
            );
            long result = removed != null ? removed : 0;
            queueMetrics.recordRedisCommand("cleanup_stale", String.valueOf(result), Duration.ofNanos(System.nanoTime() - startNanos));
            return result;
        } catch (RuntimeException e) {
            queueMetrics.recordRedisCommandError("cleanup_stale", e.getClass().getSimpleName());
            throw exceptionClassifier.wrapIfTransient("cleanup_stale", e);
        }
    }

    /**
     * 입장열 만료된 active 사용자 정리.
     *
     * @return 제거된 사용자 수
     */
    public long cleanupExpiredActive() {
        long startNanos = System.nanoTime();
        long now = clock.millis();
        try {
            Long removed = redisTemplate.execute(
                    CLEANUP_ACTIVE_SCRIPT,
                    List.of(QueueRedisKeys.ACTIVE_SET),
                    String.valueOf(now)
            );
            long result = removed != null ? removed : 0;
            queueMetrics.recordRedisCommand("cleanup_active", String.valueOf(result),
                    Duration.ofNanos(System.nanoTime() - startNanos));
            return result;
        } catch (RuntimeException e) {
            queueMetrics.recordRedisCommandError("cleanup_active", e.getClass().getSimpleName());
            throw exceptionClassifier.wrapIfTransient("cleanup_active", e);
        }
    }

    /**
     * 현재 대기열 크기.
     */
    public long waitingQueueSize() {
        try {
            Long size = redisTemplate.opsForZSet().zCard(QueueRedisKeys.WAITING_QUEUE);
            return size != null ? size : 0;
        } catch (RuntimeException e) {
            queueMetrics.recordRedisCommandError("waiting_size", e.getClass().getSimpleName());
            throw exceptionClassifier.wrapIfTransient("waiting_size", e);
        }
    }

    /**
     * 현재 활성 사용자 수.
     */
    public long activeCount() {
        try {
            Long size = redisTemplate.opsForZSet().zCard(QueueRedisKeys.ACTIVE_SET);
            return size != null ? size : 0;
        } catch (RuntimeException e) {
            queueMetrics.recordRedisCommandError("active_count", e.getClass().getSimpleName());
            throw exceptionClassifier.wrapIfTransient("active_count", e);
        }
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
                    List.of(QueueRedisKeys.ACTIVE_SET, QueueRedisKeys.activeTokenKey(userId)),
                    userId, activeToken, String.valueOf(now), String.valueOf(activeTtlSeconds), action
            );
            queueMetrics.recordRedisCommand("active_callback", classifyResult(result),
                    Duration.ofNanos(System.nanoTime() - startNanos));
            return result;
        } catch (RuntimeException e) {
            queueMetrics.recordRedisCommandError("active_callback", e.getClass().getSimpleName());
            throw exceptionClassifier.wrapIfTransient("active_callback", e);
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
