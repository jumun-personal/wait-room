package com.jumunhasyeo.ratelimiter.queue.admission;

import com.jumunhasyeo.ratelimiter.queue.config.AdmissionProperties;
import com.jumunhasyeo.ratelimiter.queue.metrics.QueueMetrics;
import com.jumunhasyeo.ratelimiter.queue.repository.WaitingQueueRedisRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class AdmissionGuard {

    private final AdmissionProperties properties;
    private final WaitingQueueRedisRepository repository;
    private final QueueMetrics queueMetrics;

    private volatile Semaphore semaphore;
    private volatile int configuredPermits;

    public Decision tryEnter() throws InterruptedException {
        if (!properties.enabled()) {
            return Decision.allowed(false, null);
        }

        ensureSemaphore();
        long waitingSize = repository.waitingQueueSize();
        if (waitingSize > properties.maxWaitingQueue()) {
            queueMetrics.recordAdmissionRejected("queue_full");
            return Decision.rejected("queue_full");
        }

        boolean acquired = semaphore.tryAcquire(properties.acquireTimeoutMs(), TimeUnit.MILLISECONDS);
        if (!acquired) {
            queueMetrics.recordAdmissionRejected("inflight");
            return Decision.rejected("inflight");
        }

        queueMetrics.setAdmissionInflight(configuredPermits - semaphore.availablePermits());
        return Decision.allowed(true, null);
    }

    public void leave(boolean permitAcquired) {
        if (!properties.enabled() || !permitAcquired) {
            return;
        }

        ensureSemaphore();
        semaphore.release();
        queueMetrics.setAdmissionInflight(configuredPermits - semaphore.availablePermits());
    }

    private void ensureSemaphore() {
        int targetPermits = Math.max(1, properties.maxInflight());
        Semaphore current = semaphore;
        if (current != null && configuredPermits == targetPermits) {
            return;
        }

        synchronized (this) {
            if (semaphore == null || configuredPermits != targetPermits) {
                semaphore = new Semaphore(targetPermits);
                configuredPermits = targetPermits;
            }
        }
    }

    public record Decision(boolean allowed, boolean permitAcquired, String reason) {
        public static Decision allowed(boolean permitAcquired, String reason) {
            return new Decision(true, permitAcquired, reason);
        }

        public static Decision rejected(String reason) {
            return new Decision(false, false, reason);
        }
    }
}
