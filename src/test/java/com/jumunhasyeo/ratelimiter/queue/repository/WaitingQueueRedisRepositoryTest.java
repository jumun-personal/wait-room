package com.jumunhasyeo.ratelimiter.queue.repository;

import com.jumunhasyeo.ratelimiter.queue.redis.QueueRedisKeys;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@DisplayName("WaitingQueueRedisRepository 통합 테스트")
class WaitingQueueRedisRepositoryTest {

    @Container
    static RedisContainer redis = new RedisContainer(DockerImageName.parse("redis:7-alpine"));

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    WaitingQueueRedisRepository repository;

    @Autowired
    StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        redisTemplate.delete(QueueRedisKeys.WAITING_QUEUE);
        redisTemplate.delete(QueueRedisKeys.ACTIVE_SET);
        redisTemplate.delete(QueueRedisKeys.POLL_TRACKER);
        var activeMetaKeys = redisTemplate.keys("q:waitroom:active-meta:*");
        if (activeMetaKeys != null && !activeMetaKeys.isEmpty()) {
            redisTemplate.delete(activeMetaKeys);
        }
    }

    @Nested
    @DisplayName("대기열 진입 테스트")
    class EnterTests {

        @Test
        @DisplayName("토큰이_남아있으면_즉시_ACTIVE_상태가_된다")
        void 토큰이_남아있으면_즉시_ACTIVE_상태가_된다() {
            String result = repository.enterOrQueue("user-1", "active-1", 10, 600);

            assertThat(result).startsWith("ACTIVE:");
            assertThat(repository.activeCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("토큰이_모두_소진되면_대기열에_추가된다")
        void 토큰이_모두_소진되면_대기열에_추가된다() {
            repository.enterOrQueue("user-1", "active-1", 1, 600);

            String result = repository.enterOrQueue("user-2", "active-2", 1, 600);

            assertThat(result).isEqualTo("QUEUED:0"); // 0-based rank
            assertThat(repository.waitingQueueSize()).isEqualTo(1);
        }

        @Test
        @DisplayName("이미_대기열에_있는_사용자는_중복_등록되지_않는다")
        void 이미_대기열에_있는_사용자는_중복_등록되지_않는다() {
            repository.enterOrQueue("user-1", "active-1", 1, 600); // 입장
            String first = repository.enterOrQueue("user-2", "active-2", 1, 600); // 대기열
            String second = repository.enterOrQueue("user-2", "active-3", 1, 600); // 중복 시도

            assertThat(first).isEqualTo("QUEUED:0");
            assertThat(second).isEqualTo("QUEUED:0");
            assertThat(repository.waitingQueueSize()).isEqualTo(1);
        }

        @Test
        @DisplayName("이미_활성_상태인_사용자는_ALREADY_ACTIVE를_반환한다")
        void 이미_활성_상태인_사용자는_ALREADY_ACTIVE를_반환한다() {
            repository.enterOrQueue("user-1", "active-1", 10, 600);
            String result = repository.enterOrQueue("user-1", "active-2", 10, 600);

            assertThat(result).isEqualTo("ALREADY_ACTIVE:active-1");
            assertThat(repository.activeCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("FIFO_순서로_대기열_순위가_매겨진다")
        void FIFO_순서로_대기열_순위가_매겨진다() throws InterruptedException {
            repository.enterOrQueue("active-user", "active-0", 1, 600);

            String rank1 = repository.enterOrQueue("user-1", "active-1", 1, 600);
            Thread.sleep(10); // score 차이를 위해
            String rank2 = repository.enterOrQueue("user-2", "active-2", 1, 600);

            assertThat(rank1).isEqualTo("QUEUED:0");
            assertThat(rank2).isEqualTo("QUEUED:1");
        }
    }

    @Nested
    @DisplayName("폴링 테스트")
    class PollTests {

        @Test
        @DisplayName("순번이_도달하면_ADMITTED_상태가_된다")
        void 순번이_도달하면_ADMITTED_상태가_된다() {
            repository.enterOrQueue("user-1", "active-1", 2, 600);
            repository.enterOrQueue("user-2", "active-2", 1, 600); // 대기열 진입 (maxTokens=1)

            redisTemplate.opsForZSet().remove(QueueRedisKeys.ACTIVE_SET, "user-1");

            String result = repository.poll("user-2", "active-token", 1, 600);
            assertThat(result).startsWith("ADMITTED:");
            assertThat(repository.activeCount()).isEqualTo(1);
            assertThat(repository.waitingQueueSize()).isEqualTo(0);
        }

        @Test
        @DisplayName("순번이_아직_아니면_현재_순위를_반환한다")
        void 순번이_아직_아니면_현재_순위를_반환한다() {
            repository.enterOrQueue("active-user", "active-0", 1, 600);
            repository.enterOrQueue("user-1", "active-1", 1, 600); // rank 0

            String result = repository.poll("user-1", "active-token", 1, 600);
            assertThat(result).isEqualTo("0");
        }

        @Test
        @DisplayName("대기열에_없는_사용자가_폴링하면_NOT_IN_QUEUE를_반환한다")
        void 대기열에_없는_사용자가_폴링하면_NOT_IN_QUEUE를_반환한다() {
            String result = repository.poll("ghost", "active-token", 10, 600);
            assertThat(result).isEqualTo("NOT_IN_QUEUE");
        }
    }

    @Nested
    @DisplayName("정리 작업 테스트")
    class CleanupTests {

        @Test
        @DisplayName("만료된_활성_사용자가_제거된다")
        void 만료된_활성_사용자가_제거된다() {
            repository.enterOrQueue("user-1", "active-1", 10, 600);

            long pastMillis = System.currentTimeMillis() - 700_000; // 700초 전
            redisTemplate.opsForZSet().add(QueueRedisKeys.ACTIVE_SET, "user-1", pastMillis);

            long removed = repository.cleanupExpiredActive();
            assertThat(removed).isEqualTo(1);
            assertThat(repository.activeCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("stale_폴링_사용자가_대기열에서_제거된다")
        void stale_폴링_사용자가_대기열에서_제거된다() {
            repository.enterOrQueue("active-user", "active-0", 1, 600);
            repository.enterOrQueue("user-1", "active-1", 1, 600);

            long pastMillis = System.currentTimeMillis() - 40_000; // 40초 전
            redisTemplate.opsForZSet().add(QueueRedisKeys.POLL_TRACKER, "user-1", pastMillis);

            long removed = repository.cleanupStale(30, 100);
            assertThat(removed).isEqualTo(1);
            assertThat(repository.waitingQueueSize()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("결제 콜백 테스트")
    class PaymentCallbackTests {

        @Test
        @DisplayName("성공_콜백이면_활성에서_제거된다")
        void 성공_콜백이면_활성에서_제거된다() {
            String token = "active-1";
            repository.enterOrQueue("user-1", token, 10, 600);

            String result = repository.handlePaymentCallback("user-1", token, "SUCCESS", 600);

            assertThat(result).isEqualTo("REMOVED");
            assertThat(repository.activeCount()).isEqualTo(0);
            assertThat(redisTemplate.hasKey(QueueRedisKeys.activeTokenKey(token))).isFalse();
        }

        @Test
        @DisplayName("실패_콜백이면_TTL이_갱신된다")
        void 실패_콜백이면_TTL이_갱신된다() {
            String token = "active-1";
            repository.enterOrQueue("user-1", token, 10, 600);

            String result = repository.handlePaymentCallback("user-1", token, "FAIL", 600);

            assertThat(result).isEqualTo("REFRESHED");
            assertThat(repository.activeCount()).isEqualTo(1);
            Long ttl = redisTemplate.getExpire(QueueRedisKeys.activeTokenKey("user-1"));
            assertThat(ttl).isNotNull();
            assertThat(ttl).isGreaterThan(0);
        }

        @Test
        @DisplayName("userId가_일치하지_않으면_NOT_FOUND를_반환한다")
        void userId가_일치하지_않으면_MISMATCH를_반환한다() {
            String token = "active-1";
            repository.enterOrQueue("user-1", token, 10, 600);

            String result = repository.handlePaymentCallback("user-2", token, "SUCCESS", 600);

            assertThat(result).isEqualTo("NOT_FOUND");
            assertThat(repository.activeCount()).isEqualTo(1);
        }
    }
}
