package com.jumunhasyeo.ratelimiter.queue.scheduler;

import com.jumunhasyeo.ratelimiter.queue.metrics.QueueMetrics;
import com.jumunhasyeo.ratelimiter.queue.repository.WaitingQueueRedisRepository;
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

    @Scheduled(fixedDelayString = "${queue.metrics-interval-ms:1000}")
    public void collect() {
        try {
            long waiting = repository.waitingQueueSize();
            long active = repository.activeCount();
            queueMetrics.updateQueueSizes(waiting, active);
        } catch (RuntimeException e) {
            log.warn("Failed to collect queue size metrics", e);
        }
    }
}
