package com.jumunhasyeo.ratelimiter.queue.scheduler;

import com.jumunhasyeo.ratelimiter.queue.redis.QueueRedisKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class RedisSchedulerLock {

    private final StringRedisTemplate redisTemplate;

    private static final String INSTANCE_ID = UUID.randomUUID().toString();

    /**
     * SET NX EX 기반 분산 락 획득 시도.
     *
     * @param ttl 락 유지 시간
     * @return 락 획득 성공 여부
     */
    public boolean tryLock(String lockKey, Duration ttl) {
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, INSTANCE_ID, ttl);
        return Boolean.TRUE.equals(acquired);
    }
}
