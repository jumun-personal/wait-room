package com.jumunhasyeo.ratelimiter.queue.admission;

import com.jumunhasyeo.ratelimiter.queue.config.AdmissionProperties;
import com.jumunhasyeo.ratelimiter.queue.metrics.QueueMetrics;
import com.jumunhasyeo.ratelimiter.queue.repository.WaitingQueueRedisRepository;
import com.jumunhasyeo.ratelimiter.queue.resilience.QueueResilienceExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdmissionGuard 단위 테스트")
class AdmissionGuardTest {

    @Mock
    WaitingQueueRedisRepository repository;

    @Mock
    QueueMetrics queueMetrics;

    @Mock
    QueueResilienceExecutor resilienceExecutor;

    @BeforeEach
    void setUp() {
        given(resilienceExecutor.executeRequest(any())).willAnswer(invocation -> invocation.<java.util.function.Supplier<?>>getArgument(0).get());
    }

    @Test
    @DisplayName("대기열이 임계치를 넘으면 queue_full로 거절된다")
    void rejectsWhenWaitingQueueIsFull() throws Exception {
        AdmissionProperties properties = new AdmissionProperties(true, 2, 10, 1, 1);
        AdmissionGuard guard = new AdmissionGuard(properties, repository, queueMetrics, resilienceExecutor);
        given(repository.waitingQueueSize()).willReturn(11L);

        AdmissionGuard.Decision decision = guard.tryEnter();

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("queue_full");
    }

    @Test
    @DisplayName("inflight 한도 내에서는 허용된다")
    void allowsWithinInflightLimit() throws Exception {
        AdmissionProperties properties = new AdmissionProperties(true, 1, 10, 1, 1);
        AdmissionGuard guard = new AdmissionGuard(properties, repository, queueMetrics, resilienceExecutor);
        given(repository.waitingQueueSize()).willReturn(0L);

        AdmissionGuard.Decision decision = guard.tryEnter();

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.permitAcquired()).isTrue();
    }

    @Test
    @DisplayName("inflight 한도를 초과하면 inflight로 거절된다")
    void rejectsWhenInflightIsSaturated() throws Exception {
        AdmissionProperties properties = new AdmissionProperties(true, 1, 10, 1, 1);
        AdmissionGuard guard = new AdmissionGuard(properties, repository, queueMetrics, resilienceExecutor);
        given(repository.waitingQueueSize()).willReturn(0L);

        AdmissionGuard.Decision first = guard.tryEnter();
        AdmissionGuard.Decision second = guard.tryEnter();

        assertThat(first.allowed()).isTrue();
        assertThat(second.allowed()).isFalse();
        assertThat(second.reason()).isEqualTo("inflight");
    }

    @Test
    @DisplayName("leave 호출 시 permit이 반납되어 재진입 가능하다")
    void leaveReleasesPermit() throws Exception {
        AdmissionProperties properties = new AdmissionProperties(true, 1, 10, 1, 1);
        AdmissionGuard guard = new AdmissionGuard(properties, repository, queueMetrics, resilienceExecutor);
        given(repository.waitingQueueSize()).willReturn(0L);

        AdmissionGuard.Decision first = guard.tryEnter();
        guard.leave(first.permitAcquired());
        AdmissionGuard.Decision second = guard.tryEnter();

        assertThat(second.allowed()).isTrue();
    }
}
