package com.jumunhasyeo.ratelimiter.queue.resilience;

import com.jumunhasyeo.ratelimiter.queue.config.QueueResilienceProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class QueueResilienceConfiguration {

    @Bean
    CircuitBreaker queueRequestCircuitBreaker(QueueResilienceProperties properties) {
        QueueResilienceProperties.CircuitBreakerProperties circuitBreaker = properties.getRequest().getCircuitBreaker();
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(circuitBreaker.getSlidingWindowSize())
                .minimumNumberOfCalls(circuitBreaker.getMinimumNumberOfCalls())
                .failureRateThreshold(circuitBreaker.getFailureRateThreshold())
                .waitDurationInOpenState(Duration.ofMillis(circuitBreaker.getOpenStateDurationMs()))
                .permittedNumberOfCallsInHalfOpenState(circuitBreaker.getHalfOpenPermittedCalls())
                .recordException(throwable -> throwable instanceof QueueRedisTransientException)
                .build();
        return CircuitBreaker.of("queueRequest", config);
    }

    @Bean
    Retry queueRequestRetry(QueueResilienceProperties properties) {
        return Retry.of("queueRequest", retryConfig(properties.getRequest()));
    }

    @Bean
    Retry queueSchedulerRetry(QueueResilienceProperties properties) {
        return Retry.of("queueScheduler", retryConfig(properties.getScheduler()));
    }

    @Bean
    Retry queueCallbackRetry(QueueResilienceProperties properties) {
        return Retry.of("queueCallback", retryConfig(properties.getCallback()));
    }

    private RetryConfig retryConfig(QueueResilienceProperties.RetryProperties properties) {
        IntervalFunction intervalFunction = IntervalFunction.ofExponentialRandomBackoff(
                properties.getRetryInitialBackoffMs(),
                2.0,
                properties.getRetryJitterFactor()
        );
        return RetryConfig.custom()
                .maxAttempts(properties.getRetryMaxAttempts())
                .intervalFunction(attempt -> Math.min(intervalFunction.apply(attempt), properties.getRetryMaxBackoffMs()))
                .retryExceptions(QueueRedisTransientException.class)
                .failAfterMaxAttempts(true)
                .build();
    }
}
