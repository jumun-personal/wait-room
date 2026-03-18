package com.jumunhasyeo.ratelimiter.queue.scheduler;

import com.jumunhasyeo.ratelimiter.queue.config.QueueProperties;
import com.jumunhasyeo.ratelimiter.queue.redis.QueueRedisKeys;
import com.jumunhasyeo.ratelimiter.queue.repository.WaitingQueueRedisRepository;
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
@DisplayName("QueueCleanupScheduler 단위 테스트")
class QueueCleanupSchedulerTest {

    @Mock
    WaitingQueueRedisRepository repository;

    @Mock
    QueueProperties properties;

    @Mock
    RedisSchedulerLock schedulerLock;

    @InjectMocks
    QueueCleanupScheduler scheduler;

    @Test
    @DisplayName("cleanup은_최대_poll_간격의_3배를_stale_기준으로_사용한다")
    void cleanup은_최대_poll_간격의_3배를_stale_기준으로_사용한다() {
        given(properties.cleanupLockTtlMs()).willReturn(3000L);
        given(schedulerLock.tryLock(eq(QueueRedisKeys.CLEANUP_LOCK), any(Duration.class))).willReturn(true);
        given(properties.maxPollIntervalSeconds()).willReturn(30);
        given(repository.cleanupStale(90, 100)).willReturn(0L);
        given(properties.activeTtlSeconds()).willReturn(600);
        given(repository.cleanupExpiredActive()).willReturn(0L);

        scheduler.cleanup();

        verify(repository).cleanupStale(90, 100);
        verify(repository).cleanupExpiredActive();
    }
}
