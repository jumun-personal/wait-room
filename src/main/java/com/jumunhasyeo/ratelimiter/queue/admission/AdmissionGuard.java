package com.jumunhasyeo.ratelimiter.queue.admission;

import com.jumunhasyeo.ratelimiter.queue.config.AdmissionProperties;
import com.jumunhasyeo.ratelimiter.queue.metrics.QueueMetrics;
import com.jumunhasyeo.ratelimiter.queue.repository.WaitingQueueRedisRepository;
import com.jumunhasyeo.ratelimiter.queue.resilience.QueueResilienceExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class AdmissionGuard {
    /**
     * 지금 요청을 더 받아도 되는지 빠르게 판단하는 보호막이다.
     * 대기열이 너무 길어졌는지, 동시에 처리 중인 요청이 너무 많은지를 본다.
     */

    private final AdmissionProperties properties;
    private final WaitingQueueRedisRepository repository;
    private final QueueMetrics queueMetrics;
    private final QueueResilienceExecutor resilienceExecutor;

    private volatile Semaphore semaphore;
    private volatile int configuredPermits;

    /**
     * 새로 들어오는 요청용 검사.
     * 대기열 길이와 동시 처리량을 모두 본다.
     */
    public Decision tryEnter() throws InterruptedException {
        return tryEnter(true);
    }

    /**
     * 상태 조회용 검사.
     * poll은 사용자 경험을 살리기 위해 대기열 길이 제한은 보지 않고,
     * 동시 처리량만 확인한다.
     */
    public Decision tryEnterWithoutQueueLimit() throws InterruptedException {
        return tryEnter(false);
    }

    private Decision tryEnter(boolean checkWaitingQueueLimit) throws InterruptedException {
        if (!properties.enabled()) {
            return Decision.allowed(false, "AdmissionProperties disable");
        }

        ensureSemaphore();
        if (checkWaitingQueueLimit) {
            // 새로 줄을 세우는 요청은 대기열이 너무 길면 아예 받지 않는다.
            long waitingSize = resilienceExecutor.executeRequest(repository::waitingQueueSize);
            if (waitingSize > properties.maxWaitingQueue()) {
                queueMetrics.recordAdmissionRejected("queue_full");
                return Decision.rejected("queue_full");
            }
        }

        // 실제 처리 중인 요청 수를 제한해서, 순간적으로 너무 많은 요청이 한꺼번에 몰리지 않게 막는다.
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
