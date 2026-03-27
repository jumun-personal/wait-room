package com.jumunhasyeo.ratelimiter.queue.resilience;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Slf4j
@Component
@RequiredArgsConstructor
public class QueueResilienceExecutor {
    /**
     * Redis가 잠깐 흔들릴 때의 공통 처리 규칙을 모아둔 곳이다.
     * 요청 경로, 스케줄러, 콜백은 성격이 달라서 같은 방식으로 다루지 않는다.
     */

    private final CircuitBreaker queueRequestCircuitBreaker;
    private final Retry queueRequestRetry;
    private final Retry queueSchedulerRetry;
    private final Retry queueCallbackRetry;

    /**
     * 실제 사용자 요청에서 쓰는 경로.
     * 짧게 다시 시도해도 계속 실패하면 잠시 후 재시도 응답으로 바꾸고,
     * 장애가 길어져 차단기가 열린 상태면 우회 신호로 바꾼다.
     */
    public <T> T executeRequest(Supplier<T> supplier) {
        Supplier<T> retried = Retry.decorateSupplier(queueRequestRetry, supplier);
        Supplier<T> guarded = CircuitBreaker.decorateSupplier(queueRequestCircuitBreaker, retried);
        try {
            return guarded.get();
        } catch (CallNotPermittedException e) {
            throw new QueueBypassSignalException(e);
        } catch (QueueRedisTransientException e) {
            throw new QueueTemporarilyUnavailableException(e);
        }
    }

    /**
     * 스케줄러용 경로.
     * 백그라운드 작업은 사용자 응답을 바로 만들지 않으므로,
     * 잠깐 다시 시도만 하고 실패는 바깥에서 로그로 처리하게 둔다.
     */
    public <T> T executeScheduler(Supplier<T> supplier) {
        Supplier<T> retried = Retry.decorateSupplier(queueSchedulerRetry, supplier);
        return retried.get();
    }

    /**
     * 결제 콜백용 경로.
     * 가능한 만큼 다시 시도하고, 그래도 안 되면 나중에 TTL 정리로 회수할 수 있게 미뤘다고 본다.
     */
    public <T> T executeCallback(Supplier<T> supplier, Supplier<T> fallbackSupplier) {
        Supplier<T> retried = Retry.decorateSupplier(queueCallbackRetry, supplier);
        try {
            return retried.get();
        } catch (QueueRedisTransientException e) {
            log.warn("Queue callback handling deferred after Redis transient failure", e);
            return fallbackSupplier.get();
        }
    }
}
