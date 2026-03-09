package com.jumunhasyeo.ratelimiter.queue.diagnostics;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@Profile("local")
@Slf4j
@ConditionalOnProperty(prefix = "diagnostics.pinning", name = "enabled", havingValue = "true")
@RequestMapping("/internal/diag/pinning")
public class PinningDiagnosticsController {

    private final PinningDiagnosticsProperties properties;
    private final Object monitor = new Object();

    public PinningDiagnosticsController(PinningDiagnosticsProperties properties) {
        this.properties = properties;
    }

    @PostMapping("/simulate")
    public Map<String, Object> simulate() throws InterruptedException {
        boolean virtualBefore = Thread.currentThread().isVirtual();
        long sleepMs = Math.max(1, properties.simulatedBlockMs());

        synchronized (monitor) {
            Thread.sleep(sleepMs);
        }

        return Map.of(
                "simulated", true,
                "threadVirtual", virtualBefore,
                "sleepMs", sleepMs,
                "timestamp", Instant.now().toString(),
                "hint", "Run with -Djdk.tracePinnedThreads=full and inspect pinned thread traces"
        );
    }

    @GetMapping("/debug/thread")
    public String t() {
        log.info("thread={}", Thread.currentThread());
        return "ok";
    }
}
