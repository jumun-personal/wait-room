package com.jumunhasyeo.ratelimiter.queue.repository;

import com.jumunhasyeo.ratelimiter.queue.redis.QueueRedisKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class WaitingQueueRedisRepository {

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;

    private static final DefaultRedisScript<String> ENTER_SCRIPT = loadScript("lua/queue-enter.lua", String.class);
    private static final DefaultRedisScript<String> POLL_SCRIPT = loadScript("lua/queue-poll.lua", String.class);
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
    public String enterOrQueue(String userId, String queueToken, int maxTokens, int metaTtlSeconds) {
        long now = clock.millis();
        return redisTemplate.execute(
                ENTER_SCRIPT,
                List.of(QueueRedisKeys.ACTIVE_SET, QueueRedisKeys.WAITING_QUEUE,
                        QueueRedisKeys.metaKey(userId), QueueRedisKeys.POLL_TRACKER),
                userId, String.valueOf(now), String.valueOf(maxTokens), queueToken, String.valueOf(metaTtlSeconds)
        );
    }

    /**
     * 대기열 폴링.
     *
     * @return "ALREADY_ACTIVE" | "INVALID_TOKEN" | "NOT_IN_QUEUE" | "ADMITTED:{token}" | "{rank}"
     */
    public String poll(String userId, String expectedToken, String activeToken, int maxTokens, int metaTtlSeconds) {
        long now = clock.millis();
        return redisTemplate.execute(
                POLL_SCRIPT,
                List.of(QueueRedisKeys.ACTIVE_SET, QueueRedisKeys.WAITING_QUEUE,
                        QueueRedisKeys.metaKey(userId), QueueRedisKeys.POLL_TRACKER),
                userId, String.valueOf(now), String.valueOf(maxTokens), expectedToken,
                String.valueOf(metaTtlSeconds), activeToken
        );
    }

    /**
     * Stale 폴링 사용자 정리.
     *
     * @return 제거된 사용자 수
     */
    public long cleanupStale(int stalePollSeconds, int batchSize) {
        long now = clock.millis();
        long thresholdMillis = stalePollSeconds * 1000L;
        Long removed = redisTemplate.execute(
                CLEANUP_STALE_SCRIPT,
                List.of(QueueRedisKeys.WAITING_QUEUE, QueueRedisKeys.POLL_TRACKER),
                "q:waitroom:meta:", String.valueOf(now), String.valueOf(thresholdMillis), String.valueOf(batchSize)
        );
        return removed != null ? removed : 0;
    }

    /**
     * 만료된 active 사용자 정리.
     *
     * @return 제거된 사용자 수
     */
    public long cleanupExpiredActive(int activeTtlSeconds) {
        long now = clock.millis();
        long ttlMillis = activeTtlSeconds * 1000L;
        Long removed = redisTemplate.execute(
                CLEANUP_ACTIVE_SCRIPT,
                List.of(QueueRedisKeys.ACTIVE_SET),
                String.valueOf(now), String.valueOf(ttlMillis)
        );
        return removed != null ? removed : 0;
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
}
