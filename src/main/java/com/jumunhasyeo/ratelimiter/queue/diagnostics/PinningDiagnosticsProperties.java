package com.jumunhasyeo.ratelimiter.queue.diagnostics;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "diagnostics.pinning")
public record PinningDiagnosticsProperties(
        boolean enabled,
        long simulatedBlockMs
) {
}
