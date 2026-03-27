package com.jumunhasyeo.ratelimiter.queue.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "queue")
public record QueueProperties(
        int maxActiveTokens,
        int activeTtlSeconds,
        long cleanupIntervalMs,
        long cleanupLockTtlMs,
        int maxPollIntervalSeconds
) {
}
