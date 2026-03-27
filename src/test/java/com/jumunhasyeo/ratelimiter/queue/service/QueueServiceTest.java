package com.jumunhasyeo.ratelimiter.queue.service;

import com.jumunhasyeo.ratelimiter.queue.config.QueueProperties;
import com.jumunhasyeo.ratelimiter.queue.dto.QueueEntryRequest;
import com.jumunhasyeo.ratelimiter.queue.dto.QueueEntryResponse;
import com.jumunhasyeo.ratelimiter.queue.dto.QueuePollResponse;
import com.jumunhasyeo.ratelimiter.queue.repository.WaitingQueueRedisRepository;
import com.jumunhasyeo.ratelimiter.queue.resilience.QueueRedisTransientException;
import com.jumunhasyeo.ratelimiter.queue.resilience.QueueResilienceExecutor;
import com.jumunhasyeo.ratelimiter.queue.resilience.QueueTemporarilyUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
@DisplayName("QueueService 단위 테스트")
class QueueServiceTest {

    @Mock
    WaitingQueueRedisRepository repository;

    @Mock
    QueueProperties properties;

    @Mock
    QueueResilienceExecutor resilienceExecutor;

    @InjectMocks
    QueueServiceImpl queueService;

    @BeforeEach
    void setUp() {
        lenient().when(properties.activeTtlSeconds()).thenReturn(600);
        lenient().when(properties.maxPollIntervalSeconds()).thenReturn(30);
        lenient().when(resilienceExecutor.executeRequest(any())).thenAnswer(invocation -> invocation.<java.util.function.Supplier<?>>getArgument(0).get());
        lenient().when(resilienceExecutor.executeCallback(any(), any())).thenAnswer(invocation -> invocation.<java.util.function.Supplier<?>>getArgument(0).get());
    }

    @Nested
    @DisplayName("대기열 진입")
    class EnterTests {

        @Test
        @DisplayName("이미_활성_상태인_사용자는_allowed_응답을_받는다")
        void 이미_활성_상태인_사용자는_allowed_응답을_받는다() {
            given(repository.enterOrQueue(eq("user-1"), anyString(), eq(600)))
                    .willReturn("ALREADY_ACTIVE:some-token");

            QueueEntryResponse response = queueService.enter(new QueueEntryRequest("user-1"));

            assertThat(response.allowed()).isTrue();
            assertThat(response.activeToken()).isEqualTo("some-token");
        }

        @Test
        @DisplayName("새_사용자는_즉시_입장하지_않고_대기열_순위를_반환한다")
        void 새_사용자는_즉시_입장하지_않고_대기열_순위를_반환한다() {
            given(repository.enterOrQueue(eq("user-1"), anyString(), eq(600)))
                    .willReturn("QUEUED:4");

            QueueEntryResponse response = queueService.enter(new QueueEntryRequest("user-1"));

            assertThat(response.allowed()).isFalse();
            assertThat(response.rank()).isEqualTo(5); // 1-based
            assertThat(response.pollIntervalSeconds()).isEqualTo(5);
        }

        @Test
        @DisplayName("상위_100위_안은_5초_폴링_간격을_사용한다")
        void 상위_100위_안은_5초_폴링_간격을_사용한다() {
            given(repository.enterOrQueue(eq("user-1"), anyString(), eq(600)))
                    .willReturn("QUEUED:99"); // 0-based rank -> 100 (1-based)

            QueueEntryResponse response = queueService.enter(new QueueEntryRequest("user-1"));

            assertThat(response.rank()).isEqualTo(100);
            assertThat(response.pollIntervalSeconds()).isEqualTo(5);
        }

        @Test
        @DisplayName("1000위_이하는_10초_폴링_간격을_사용한다")
        void 천위_이하는_10초_폴링_간격을_사용한다() {
            given(repository.enterOrQueue(eq("user-1"), anyString(), eq(600)))
                    .willReturn("QUEUED:100"); // 0-based rank -> 101 (1-based)

            QueueEntryResponse response = queueService.enter(new QueueEntryRequest("user-1"));

            assertThat(response.rank()).isEqualTo(101);
            assertThat(response.pollIntervalSeconds()).isEqualTo(10);
        }

        @Test
        @DisplayName("1000위를_초과하면_최대_폴링_간격_설정값을_사용한다")
        void 천위를_초과하면_최대_폴링_간격_설정값을_사용한다() {
            given(properties.maxPollIntervalSeconds()).willReturn(30);
            given(repository.enterOrQueue(eq("user-1"), anyString(), eq(600)))
                    .willReturn("QUEUED:1000"); // 0-based rank -> 1001 (1-based)

            QueueEntryResponse response = queueService.enter(new QueueEntryRequest("user-1"));

            assertThat(response.allowed()).isFalse();
            assertThat(response.rank()).isEqualTo(1001);
            assertThat(response.pollIntervalSeconds()).isEqualTo(30);
        }

        @Test
        @DisplayName("예상하지_못한_enter_결과는_예외를_던진다")
        void 예상하지_못한_enter_결과는_예외를_던진다() {
            given(repository.enterOrQueue(eq("user-1"), anyString(), eq(600)))
                    .willReturn("0");

            assertThatThrownBy(() -> queueService.enter(new QueueEntryRequest("user-1")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("예상하지 못한 queue enter 결과");
        }
    }

    @Nested
    @DisplayName("폴링")
    class PollTests {

        @Test
        @DisplayName("활성_상태인_사용자는_토큰과_함께_입장_허용된다")
        void 활성_상태인_사용자는_토큰과_함께_입장_허용된다() {
            given(repository.poll(eq("user-1"), anyString(), eq(600)))
                    .willReturn("ALREADY_ACTIVE:active-123");

            QueuePollResult result = queueService.poll("user-1", null);
            QueuePollResponse response = result.response();

            assertThat(response.allowed()).isTrue();
            assertThat(response.activeToken()).isEqualTo("active-123");
            assertThat(result.stale()).isFalse();
        }

        @Test
        @DisplayName("순번이_아직_아니면_현재_순위를_반환한다")
        void 순번이_아직_아니면_현재_순위를_반환한다() {
            given(repository.poll(eq("user-1"), anyString(), eq(600)))
                    .willReturn("9"); // 0-based

            QueuePollResult result = queueService.poll("user-1", null);
            QueuePollResponse response = result.response();

            assertThat(response.allowed()).isFalse();
            assertThat(response.rank()).isEqualTo(10); // 1-based
            assertThat(response.pollIntervalSeconds()).isEqualTo(5);
            assertThat(result.stale()).isFalse();
        }

        @Test
        @DisplayName("대기열에_없는_사용자가_폴링하면_자동_등록된_순위를_반환한다")
        void 대기열에_없는_사용자가_폴링하면_자동_등록된_순위를_반환한다() {
            given(repository.poll(eq("user-1"), anyString(), eq(600)))
                    .willReturn("0");

            QueuePollResult result = queueService.poll("user-1", null);
            QueuePollResponse response = result.response();

            assertThat(response.allowed()).isFalse();
            assertThat(response.rank()).isEqualTo(1);
            assertThat(result.stale()).isFalse();
        }

        @Test
        @DisplayName("예상하지_못한_poll_결과는_예외를_던진다")
        void 예상하지_못한_poll_결과는_예외를_던진다() {
            given(repository.poll(eq("user-1"), anyString(), eq(600)))
                    .willReturn("QUEUED:1");

            assertThatThrownBy(() -> queueService.poll("user-1", null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("예상하지 못한 queue poll 결과");
        }

        @Test
        @DisplayName("Redis_일시_장애_시_lastKnownRank가_있으면_stale_waiting_응답을_반환한다")
        void Redis_일시_장애_시_lastKnownRank가_있으면_stale_waiting_응답을_반환한다() {
            doThrow(new QueueTemporarilyUnavailableException(
                    new QueueRedisTransientException("poll", new RuntimeException("timeout"))
            )).when(resilienceExecutor).executeRequest(any());

            QueuePollResult result = queueService.poll("user-1", 7);

            assertThat(result.stale()).isTrue();
            assertThat(result.response().allowed()).isFalse();
            assertThat(result.response().rank()).isEqualTo(7);
            assertThat(result.response().pollIntervalSeconds()).isEqualTo(5);
        }

        @Test
        @DisplayName("Redis_일시_장애_시_lastKnownRank가_없으면_예외를_던진다")
        void Redis_일시_장애_시_lastKnownRank가_없으면_예외를_던진다() {
            doThrow(new QueueTemporarilyUnavailableException(
                    new QueueRedisTransientException("poll", new RuntimeException("timeout"))
            )).when(resilienceExecutor).executeRequest(any());

            assertThatThrownBy(() -> queueService.poll("user-1", null))
                    .isInstanceOf(QueueTemporarilyUnavailableException.class);
        }

        @Test
        @DisplayName("결제_callback_재시도_소진_후_DEFERRED를_반환할_수_있다")
        void 결제_callback_재시도_소진_후_DEFERRED를_반환할_수_있다() {
            doReturn("DEFERRED").when(resilienceExecutor).executeCallback(any(), any());

            String result = queueService.handlePaymentCallback("user-1", "token", "SUCCESS");

            assertThat(result).isEqualTo("DEFERRED");
        }
    }
}
