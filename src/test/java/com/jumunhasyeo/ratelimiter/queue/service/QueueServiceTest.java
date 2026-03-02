package com.jumunhasyeo.ratelimiter.queue.service;

import com.jumunhasyeo.ratelimiter.queue.config.QueueProperties;
import com.jumunhasyeo.ratelimiter.queue.config.QueueRuntimeConfig;
import com.jumunhasyeo.ratelimiter.queue.dto.QueueEntryRequest;
import com.jumunhasyeo.ratelimiter.queue.dto.QueueEntryResponse;
import com.jumunhasyeo.ratelimiter.queue.dto.QueuePollResponse;
import com.jumunhasyeo.ratelimiter.queue.enums.QueueResult;
import com.jumunhasyeo.ratelimiter.queue.repository.WaitingQueueRedisRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("QueueService 단위 테스트")
class QueueServiceTest {

    @Mock
    WaitingQueueRedisRepository repository;

    @Mock
    QueueProperties properties;

    @InjectMocks
    QueueServiceImpl queueService;

    @BeforeEach
    void setUp() {
        given(properties.activeTtlSeconds()).willReturn(600);
    }

    @Mock
    QueueRuntimeConfig runtimeConfig;

    @Nested
    @DisplayName("대기열 진입")
    class EnterTests {

        @Test
        @DisplayName("토큰이_남아있으면_즉시_입장할_수_있다")
        void 토큰이_남아있으면_즉시_입장할_수_있다() {
            given(properties.metaTtlSeconds()).willReturn(60);
            given(runtimeConfig.maxActiveTokens()).willReturn(100);
            given(repository.enterOrQueue(eq("user-1"), anyString(), eq(100), eq(60), eq(600)))
                    .willReturn(QueueResult.ACTIVE.withPayload("some-token"));

            QueueEntryResponse response = queueService.enter(new QueueEntryRequest("user-1"));

            assertThat(response.allowed()).isTrue();
            assertThat(response.activeToken()).isEqualTo("some-token");
        }

        @Test
        @DisplayName("이미_활성_상태인_사용자는_allowed_응답을_받는다")
        void 이미_활성_상태인_사용자는_allowed_응답을_받는다() {
            given(properties.metaTtlSeconds()).willReturn(60);
            given(runtimeConfig.maxActiveTokens()).willReturn(100);
            given(repository.enterOrQueue(eq("user-1"), anyString(), eq(100), eq(60), eq(600)))
                    .willReturn(QueueResult.ALREADY_ACTIVE.name());

            QueueEntryResponse response = queueService.enter(new QueueEntryRequest("user-1"));

            assertThat(response.allowed()).isTrue();
        }

        @Test
        @DisplayName("토큰이_없으면_대기열에_추가되고_순위를_반환한다")
        void 토큰이_없으면_대기열에_추가되고_순위를_반환한다() {
            given(properties.metaTtlSeconds()).willReturn(60);
            given(runtimeConfig.maxActiveTokens()).willReturn(100);
            given(properties.estimatedProcessingSeconds()).willReturn(2);
            given(repository.enterOrQueue(eq("user-1"), anyString(), eq(100), eq(60), eq(600)))
                    .willReturn("4"); // 0-based rank

            QueueEntryResponse response = queueService.enter(new QueueEntryRequest("user-1"));

            assertThat(response.allowed()).isFalse();
            assertThat(response.rank()).isEqualTo(5); // 1-based
            assertThat(response.queueToken()).isNotNull();
            assertThat(response.pollIntervalSeconds()).isEqualTo(5);
        }

        @Test
        @DisplayName("대기_시간_추정은_선형_보간_공식을_사용한다")
        void 대기_시간_추정은_선형_보간_공식을_사용한다() {
            // throughput = 100 / 2 = 50 users/sec
            // rank=100 → 100/50 = 2초
            given(properties.metaTtlSeconds()).willReturn(60);
            given(runtimeConfig.maxActiveTokens()).willReturn(100);
            given(properties.estimatedProcessingSeconds()).willReturn(2);
            given(repository.enterOrQueue(eq("user-1"), anyString(), eq(100), eq(60), eq(600)))
                    .willReturn("99"); // 0-based rank → 100 (1-based)

            QueueEntryResponse response = queueService.enter(new QueueEntryRequest("user-1"));

            assertThat(response.rank()).isEqualTo(100);
            assertThat(response.estimatedWaitSeconds()).isEqualTo(2);
            assertThat(response.pollIntervalSeconds()).isEqualTo(5);
        }

        @Test
        @DisplayName("대기_시간은_최소_1초이다")
        void 대기_시간은_최소_1초이다() {
            // throughput = 100 / 2 = 50 users/sec
            // rank=1 → 1/50 = 0.02초 → ceil → 1초 (최소값)
            given(properties.metaTtlSeconds()).willReturn(60);
            given(runtimeConfig.maxActiveTokens()).willReturn(100);
            given(properties.estimatedProcessingSeconds()).willReturn(2);
            given(repository.enterOrQueue(eq("user-1"), anyString(), eq(100), eq(60), eq(600)))
                    .willReturn("0"); // 0-based rank → 1 (1-based)

            QueueEntryResponse response = queueService.enter(new QueueEntryRequest("user-1"));

            assertThat(response.rank()).isEqualTo(1);
            assertThat(response.estimatedWaitSeconds()).isEqualTo(1);
            assertThat(response.pollIntervalSeconds()).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("폴링")
    class PollTests {

        @Test
        @DisplayName("순번이_도달하면_입장_허용된다")
        void 순번이_도달하면_입장_허용된다() {
            given(properties.metaTtlSeconds()).willReturn(60);
            given(runtimeConfig.maxActiveTokens()).willReturn(100);
            given(repository.poll(eq("user-1"), eq("qt"), anyString(), eq(100), eq(60), eq(600)))
                    .willReturn(QueueResult.ADMITTED.withPayload("active-123"));

            QueuePollResponse response = queueService.poll("user-1", "qt");

            assertThat(response.allowed()).isTrue();
            assertThat(response.activeToken()).isEqualTo("active-123");
        }

        @Test
        @DisplayName("순번이_아직_아니면_현재_순위를_반환한다")
        void 순번이_아직_아니면_현재_순위를_반환한다() {
            given(properties.metaTtlSeconds()).willReturn(60);
            given(runtimeConfig.maxActiveTokens()).willReturn(100);
            given(properties.estimatedProcessingSeconds()).willReturn(2);
            given(repository.poll(eq("user-1"), eq("qt"), anyString(), eq(100), eq(60), eq(600)))
                    .willReturn("9"); // 0-based

            QueuePollResponse response = queueService.poll("user-1", "qt");

            assertThat(response.allowed()).isFalse();
            assertThat(response.rank()).isEqualTo(10); // 1-based
            assertThat(response.pollIntervalSeconds()).isEqualTo(5);
        }

        @Test
        @DisplayName("잘못된_토큰이면_IllegalArgumentException_반환")
        void 잘못된_토큰이면_IllegalArgumentException_반환() {
            given(properties.metaTtlSeconds()).willReturn(60);
            given(runtimeConfig.maxActiveTokens()).willReturn(100);
            given(repository.poll(eq("user-1"), eq("wrong"), anyString(), eq(100), eq(60), eq(600)))
                    .willReturn(QueueResult.INVALID_TOKEN.name());

            assertThatThrownBy(() -> queueService.poll("user-1", "wrong"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("대기열에_없는_사용자가_폴링하면_IllegalStateException_반환")
        void 대기열에_없는_사용자가_폴링하면_IllegalStateException_반환() {
            given(properties.metaTtlSeconds()).willReturn(60);
            given(runtimeConfig.maxActiveTokens()).willReturn(100);
            given(repository.poll(eq("user-1"), eq("qt"), anyString(), eq(100), eq(60), eq(600)))
                    .willReturn(QueueResult.NOT_IN_QUEUE.name());

            assertThatThrownBy(() -> queueService.poll("user-1", "qt"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
