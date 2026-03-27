package com.jumunhasyeo.ratelimiter.queue.scheduler;

import com.jumunhasyeo.ratelimiter.queue.config.QueueProperties;
import com.jumunhasyeo.ratelimiter.queue.config.QueueRuntimeConfig;
import com.jumunhasyeo.ratelimiter.queue.redis.QueueRedisKeys;
import com.jumunhasyeo.ratelimiter.queue.repository.WaitingQueueRedisRepository;
import com.jumunhasyeo.ratelimiter.queue.resilience.QueueResilienceExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("QueuePromotionScheduler 단위 테스트")
class QueuePromotionSchedulerTest {

    @Mock
    WaitingQueueRedisRepository repository;

    @Mock
    QueueProperties properties;

    @Mock
    QueueRuntimeConfig runtimeConfig;

    @Mock
    RedisSchedulerLock schedulerLock;

    @Mock
    QueueResilienceExecutor resilienceExecutor;

    @InjectMocks
    QueuePromotionScheduler scheduler;

    @BeforeEach
    void setUp() {
        given(resilienceExecutor.executeScheduler(any())).willAnswer(invocation -> invocation.<java.util.function.Supplier<?>>getArgument(0).get());
    }

    @Test
    @DisplayName("promotion은_PROMOTION_LOCK과_가용_슬롯_승격_설정을_사용한다")
    void promotion은_PROMOTION_LOCK과_가용_슬롯_승격_설정을_사용한다() {
        given(properties.cleanupLockTtlMs()).willReturn(3000L);
        given(properties.maxPollIntervalSeconds()).willReturn(30);
        given(properties.activeTtlSeconds()).willReturn(600);
        given(runtimeConfig.maxActiveTokens()).willReturn(100);
        given(schedulerLock.tryLock(eq(QueueRedisKeys.PROMOTION_LOCK), any(Duration.class))).willReturn(true);
        given(repository.promoteWaitingUsers(100, 600, 90, 100)).willReturn(3L);

        scheduler.promote();

        verify(repository).promoteWaitingUsers(100, 600, 90, 100);
    }
}
