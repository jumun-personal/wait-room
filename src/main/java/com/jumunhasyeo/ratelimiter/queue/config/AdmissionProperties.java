package com.jumunhasyeo.ratelimiter.queue.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "admission")
public record AdmissionProperties(
        boolean enabled,
        int maxInflight,
        long maxWaitingQueue,
        long acquireTimeoutMs,
        int retryAfterSeconds
) {
}
