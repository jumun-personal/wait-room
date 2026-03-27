package com.jumunhasyeo.ratelimiter.queue.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("QueueResilienceExecutor 단위 테스트")
class QueueResilienceExecutorTest {

    @Test
    @DisplayName("일시 장애가 한 번만 발생하면 retry로 흡수된다")
    void 일시_장애가_한_번만_발생하면_retry로_흡수된다() {
        QueueResilienceExecutor executor = new QueueResilienceExecutor(
                requestCircuitBreaker(),
                retry(3),
                retry(3),
                retry(3)
        );
        AtomicInteger attempts = new AtomicInteger();

        String result = executor.executeRequest(() -> {
            if (attempts.getAndIncrement() == 0) {
                throw new QueueRedisTransientException("enter", new RuntimeException("timeout"));
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("지속적인 일시 장애가 누적되면 breaker open 이후 bypass 예외를 던진다")
    void 지속적인_일시_장애가_누적되면_breaker_open_이후_bypass_예외를_던진다() {
        QueueResilienceExecutor executor = new QueueResilienceExecutor(
                CircuitBreaker.of("queueRequest", CircuitBreakerConfig.custom()
                        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                        .slidingWindowSize(2)
                        .minimumNumberOfCalls(2)
                        .failureRateThreshold(50)
                        .waitDurationInOpenState(Duration.ofSeconds(5))
                        .permittedNumberOfCallsInHalfOpenState(1)
                        .recordException(throwable -> throwable instanceof QueueRedisTransientException)
                        .build()),
                retry(1),
                retry(1),
                retry(1)
        );

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> executor.executeRequest(this::alwaysFail))
                    .isInstanceOf(QueueTemporarilyUnavailableException.class);
        }

        assertThatThrownBy(() -> executor.executeRequest(this::alwaysFail))
                .isInstanceOf(QueueBypassSignalException.class);
    }

    private String alwaysFail() {
        throw new QueueRedisTransientException("poll", new RuntimeException("timeout"));
    }

    private Retry retry(int maxAttempts) {
        return Retry.of("test-retry-" + maxAttempts, RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .retryExceptions(QueueRedisTransientException.class)
                .waitDuration(Duration.ZERO)
                .failAfterMaxAttempts(true)
                .build());
    }

    private CircuitBreaker requestCircuitBreaker() {
        return CircuitBreaker.of("queueRequest", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(5))
                .permittedNumberOfCallsInHalfOpenState(1)
                .recordException(throwable -> throwable instanceof QueueRedisTransientException)
                .build());
    }
}
