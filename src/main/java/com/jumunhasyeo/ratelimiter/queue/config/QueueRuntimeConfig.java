package com.jumunhasyeo.ratelimiter.queue.config;

import com.jumunhasyeo.ratelimiter.queue.redis.QueueRedisKeys;
import com.jumunhasyeo.ratelimiter.queue.resilience.QueueRedisExceptionClassifier;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class QueueRuntimeConfig {

    private final StringRedisTemplate redisTemplate;
    private final QueueProperties queueProperties;
    private final QueueRedisExceptionClassifier exceptionClassifier;

    public int maxActiveTokens() {
        try {
            String value = redisTemplate.opsForValue().get(QueueRedisKeys.MAX_ACTIVE_TOKENS);
            if (value == null || value.isBlank()) {
                return queueProperties.maxActiveTokens();
            }

            final int parsed;
            try {
                parsed = Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                throw new IllegalStateException("maxActiveTokens must be a valid integer", e);
            }
            if (parsed <= 0) {
                throw new IllegalStateException("maxActiveTokens must be positive");
            }
            return parsed;
        } catch (RuntimeException e) {
            throw exceptionClassifier.wrapIfTransient("max_active_tokens", e);
        }
    }
}
