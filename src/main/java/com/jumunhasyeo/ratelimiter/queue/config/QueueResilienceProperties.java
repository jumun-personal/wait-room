package com.jumunhasyeo.ratelimiter.queue.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "queue.resilience")
public class QueueResilienceProperties {

    private RetryProperties request = new RetryProperties();
    private RetryProperties scheduler = new RetryProperties();
    private RetryProperties callback = new RetryProperties();

    @Getter
    @Setter
    public static class RetryProperties {

        private int retryMaxAttempts = 3;
        private long retryInitialBackoffMs = 200;
        private long retryMaxBackoffMs = 500;
        private double retryJitterFactor = 0.5;
        private CircuitBreakerProperties circuitBreaker = new CircuitBreakerProperties();
    }

    @Getter
    @Setter
    public static class CircuitBreakerProperties {

        private int slidingWindowSize = 50;
        private int minimumNumberOfCalls = 50;
        private float failureRateThreshold = 50f;
        private long openStateDurationMs = 5_000;
        private int halfOpenPermittedCalls = 5;
    }
}
