package com.jumunhasyeo.ratelimiter.queue.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class QueueMetrics {

    private final MeterRegistry meterRegistry;

    private final AtomicInteger vtInflight = new AtomicInteger();
    private final AtomicInteger admissionInflight = new AtomicInteger();
    private final AtomicLong waitingQueueSize = new AtomicLong();
    private final AtomicLong activeQueueSize = new AtomicLong();

    public QueueMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        Gauge.builder("queue.vt.request.inflight", vtInflight, AtomicInteger::get)
                .description("Inflight queue API requests")
                .register(meterRegistry);

        Gauge.builder("queue.admission.inflight", admissionInflight, AtomicInteger::get)
                .description("Inflight requests guarded by admission control")
                .register(meterRegistry);

        Gauge.builder("queue.waiting.size", waitingQueueSize, AtomicLong::get)
                .description("Current waiting queue size")
                .register(meterRegistry);

        Gauge.builder("queue.active.size", activeQueueSize, AtomicLong::get)
                .description("Current active user size")
                .register(meterRegistry);
    }

    public void incrementVtInflight() {
        vtInflight.incrementAndGet();
    }

    public void decrementVtInflight() {
        vtInflight.decrementAndGet();
    }

    public void recordVtRequest(String endpoint, int statusCode, boolean virtualThread, Duration duration) {
        List<Tag> tags = List.of(
                Tag.of("endpoint", endpoint),
                Tag.of("status", String.valueOf(statusCode)),
                Tag.of("virtual", String.valueOf(virtualThread))
        );

        Timer.builder("queue.vt.request.duration")
                .tags(tags)
                .register(meterRegistry)
                .record(duration);

        Counter.builder("queue.vt.request.total")
                .tags(tags)
                .register(meterRegistry)
                .increment();
    }

    public void recordAdmissionRejected(String reason) {
        Counter.builder("queue.admission.rejected")
                .tag("reason", reason)
                .register(meterRegistry)
                .increment();
    }

    public void setAdmissionInflight(int inflight) {
        admissionInflight.set(Math.max(0, inflight));
    }

    public void updateQueueSizes(long waiting, long active) {
        waitingQueueSize.set(waiting);
        activeQueueSize.set(active);
    }

    public void recordRedisCommand(String operation, String result, Duration duration) {
        Timer.builder("queue.redis.command.duration")
                .tags("op", operation, "result", result)
                .register(meterRegistry)
                .record(duration);
    }

    public void recordRedisCommandError(String operation, String errorType) {
        Counter.builder("queue.redis.command.error")
                .tags("op", operation, "error", errorType)
                .register(meterRegistry)
                .increment();
    }
}
