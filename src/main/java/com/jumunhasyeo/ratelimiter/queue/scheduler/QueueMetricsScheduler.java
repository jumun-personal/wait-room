package com.jumunhasyeo.ratelimiter.queue.scheduler;

import com.jumunhasyeo.ratelimiter.queue.metrics.QueueMetrics;
import com.jumunhasyeo.ratelimiter.queue.repository.WaitingQueueRedisRepository;
import com.jumunhasyeo.ratelimiter.queue.resilience.QueueResilienceExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class QueueMetricsScheduler {

    private final WaitingQueueRedisRepository repository;
    private final QueueMetrics queueMetrics;
    private final QueueResilienceExecutor resilienceExecutor;

    @Scheduled(fixedDelayString = "${queue.metrics-interval-ms:1000}")
    public void collect() {
        try {
            long waiting = resilienceExecutor.executeScheduler(repository::waitingQueueSize);
            long active = resilienceExecutor.executeScheduler(repository::activeCount);
            queueMetrics.updateQueueSizes(waiting, active);
        } catch (RuntimeException e) {
            log.warn("Failed to collect queue size metrics", e);
        }
    }
}
