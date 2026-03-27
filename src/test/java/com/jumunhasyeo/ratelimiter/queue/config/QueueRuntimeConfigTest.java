package com.jumunhasyeo.ratelimiter.queue.config;

import com.jumunhasyeo.ratelimiter.queue.redis.QueueRedisKeys;
import com.jumunhasyeo.ratelimiter.queue.resilience.QueueRedisExceptionClassifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("QueueRuntimeConfig 단위 테스트")
class QueueRuntimeConfigTest {

    private final QueueRedisExceptionClassifier exceptionClassifier = new QueueRedisExceptionClassifier();

    @Mock
    StringRedisTemplate redisTemplate;

    @Mock
    QueueProperties queueProperties;

    @Mock
    ValueOperations<String, String> valueOperations;

    @Test
    @DisplayName("Redis 값이 있으면 maxActiveTokens로 사용한다")
    void redisValueOverridesDefault() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get(QueueRedisKeys.MAX_ACTIVE_TOKENS)).willReturn("250");
        QueueRuntimeConfig config = new QueueRuntimeConfig(redisTemplate, queueProperties, exceptionClassifier);

        int result = config.maxActiveTokens();

        assertThat(result).isEqualTo(250);
    }

    @Test
    @DisplayName("Redis 값이 없으면 queue 설정값으로 fallback 한다")
    void fallsBackToQueuePropertiesWhenRedisValueMissing() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get(QueueRedisKeys.MAX_ACTIVE_TOKENS)).willReturn(null);
        given(queueProperties.maxActiveTokens()).willReturn(1000);
        QueueRuntimeConfig config = new QueueRuntimeConfig(redisTemplate, queueProperties, exceptionClassifier);

        int result = config.maxActiveTokens();

        assertThat(result).isEqualTo(1000);
    }

    @Test
    @DisplayName("Redis 값이 정수가 아니면 예외가 발생한다")
    void throwsWhenRedisValueIsNotNumber() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get(QueueRedisKeys.MAX_ACTIVE_TOKENS)).willReturn("abc");
        QueueRuntimeConfig config = new QueueRuntimeConfig(redisTemplate, queueProperties, exceptionClassifier);

        assertThatThrownBy(config::maxActiveTokens)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("valid integer");
    }

    @Test
    @DisplayName("Redis 값이 0 이하면 예외가 발생한다")
    void throwsWhenRedisValueIsNonPositive() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get(QueueRedisKeys.MAX_ACTIVE_TOKENS)).willReturn("0");
        QueueRuntimeConfig config = new QueueRuntimeConfig(redisTemplate, queueProperties, exceptionClassifier);

        assertThatThrownBy(config::maxActiveTokens)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("positive");
    }
}
