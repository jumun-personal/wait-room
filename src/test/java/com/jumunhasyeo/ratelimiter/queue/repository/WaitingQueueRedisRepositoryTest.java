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

import java.util.UUID;

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
        redisTemplate.delete(QueueRedisKeys.CLEANUP_LOCK);
        redisTemplate.delete(QueueRedisKeys.PROMOTION_LOCK);
        var activeTokenKeys = redisTemplate.keys("q:waitroom:active-token:*");
        if (activeTokenKeys != null && !activeTokenKeys.isEmpty()) {
            redisTemplate.delete(activeTokenKeys);
        }
    }

    @Nested
    @DisplayName("대기열 진입 테스트")
    class EnterTests {

        @Test
        @DisplayName("새_사용자는_즉시_입장하지_않고_대기열에_추가된다")
        void 새_사용자는_즉시_입장하지_않고_대기열에_추가된다() {
            String result = repository.enterOrQueue("user-1", "active-1", 600);

            assertThat(result).isEqualTo("QUEUED:0");
            assertThat(repository.waitingQueueSize()).isEqualTo(1);
            assertThat(repository.activeCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("여러_사용자는_FIFO_순서로_대기열에_추가된다")
        void 여러_사용자는_FIFO_순서로_대기열에_추가된다() throws InterruptedException {
            String first = repository.enterOrQueue("user-1", "active-1", 600);
            Thread.sleep(10);
            String result = repository.enterOrQueue("user-2", "active-2", 600);

            assertThat(first).isEqualTo("QUEUED:0");
            assertThat(result).isEqualTo("QUEUED:1"); // 0-based rank
            assertThat(repository.waitingQueueSize()).isEqualTo(2);
        }

        @Test
        @DisplayName("이미_대기열에_있는_사용자는_중복_등록되지_않는다")
        void 이미_대기열에_있는_사용자는_중복_등록되지_않는다() {
            String first = repository.enterOrQueue("user-2", "active-2", 600);
            String second = repository.enterOrQueue("user-2", "active-3", 600);

            assertThat(first).isEqualTo("QUEUED:0");
            assertThat(second).isEqualTo("QUEUED:0");
            assertThat(repository.waitingQueueSize()).isEqualTo(1);
        }

        @Test
        @DisplayName("이미_활성_상태인_사용자는_ALREADY_ACTIVE를_반환한다")
        void 이미_활성_상태인_사용자는_ALREADY_ACTIVE를_반환한다() {
            activateUser("user-1", "active-1", 600);
            String result = repository.enterOrQueue("user-1", "active-2", 600);

            assertThat(result).isEqualTo("ALREADY_ACTIVE:active-1");
            assertThat(repository.activeCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("FIFO_순서로_대기열_순위가_매겨진다")
        void FIFO_순서로_대기열_순위가_매겨진다() throws InterruptedException {
            String rank1 = repository.enterOrQueue("user-1", "active-1", 600);
            Thread.sleep(10); // score 차이를 위해
            String rank2 = repository.enterOrQueue("user-2", "active-2", 600);

            assertThat(rank1).isEqualTo("QUEUED:0");
            assertThat(rank2).isEqualTo("QUEUED:1");
        }
    }

    @Nested
    @DisplayName("폴링 테스트")
    class PollTests {

        @Test
        @DisplayName("활성_상태인_사용자가_폴링하면_토큰과_함께_반환된다")
        void 활성_상태인_사용자가_폴링하면_토큰과_함께_반환된다() {
            activateUser("user-1", "active-1", 600);

            String result = repository.poll("user-1", "active-token", 600);

            assertThat(result).isEqualTo("ALREADY_ACTIVE:active-1");
            assertThat(repository.activeCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("대기열에_있는_사용자가_폴링하면_현재_순위를_반환한다")
        void 대기열에_있는_사용자가_폴링하면_현재_순위를_반환한다() {
            repository.enterOrQueue("user-1", "active-1", 600);
            repository.enterOrQueue("user-2", "active-2", 600);

            String result = repository.poll("user-2", "active-token", 600);
            assertThat(result).isEqualTo("1");
        }

        @Test
        @DisplayName("대기열에_없는_사용자가_폴링하면_자동으로_대기열에_등록된다")
        void 대기열에_없는_사용자가_폴링하면_자동으로_대기열에_등록된다() {
            String result = repository.poll("ghost", "active-token", 600);
            assertThat(result).isEqualTo("0");
            assertThat(repository.waitingQueueSize()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("승격 테스트")
    class PromotionTests {

        @Test
        @DisplayName("가용_슬롯만큼만_승격한다")
        void 가용_슬롯만큼만_승격한다() throws InterruptedException {
            activateUser("active-user", "token-active", 600);
            repository.enterOrQueue("user-1", "active-1", 600);
            Thread.sleep(10);
            repository.enterOrQueue("user-2", "active-2", 600);

            long promoted = repository.promoteWaitingUsers(2, 600, 90, 100);

            assertThat(promoted).isEqualTo(1);
            assertThat(repository.activeCount()).isEqualTo(2);
            assertThat(repository.waitingQueueSize()).isEqualTo(1);
            assertThat(redisTemplate.opsForZSet().score(QueueRedisKeys.ACTIVE_SET, "user-1")).isNotNull();
            assertThat(UUID.fromString(redisTemplate.opsForValue().get(QueueRedisKeys.activeTokenKey("user-1")))).isNotNull();
        }

        @Test
        @DisplayName("stale_선두_사용자는_제거하고_뒤의_fresh_사용자를_승격한다")
        void stale_선두_사용자는_제거하고_뒤의_fresh_사용자를_승격한다() throws InterruptedException {
            repository.enterOrQueue("user-1", "active-1", 600);
            Thread.sleep(10);
            repository.enterOrQueue("user-2", "active-2", 600);

            long pastMillis = System.currentTimeMillis() - 100_000;
            redisTemplate.opsForZSet().add(QueueRedisKeys.POLL_TRACKER, "user-1", pastMillis);

            long promoted = repository.promoteWaitingUsers(1, 600, 90, 100);

            assertThat(promoted).isEqualTo(1);
            assertThat(repository.waitingQueueSize()).isEqualTo(0);
            assertThat(redisTemplate.opsForZSet().score(QueueRedisKeys.ACTIVE_SET, "user-2")).isNotNull();
            assertThat(redisTemplate.opsForZSet().score(QueueRedisKeys.ACTIVE_SET, "user-1")).isNull();
        }

        @Test
        @DisplayName("승격은_스캔_상한_100명을_넘어가지_않는다")
        void 승격은_스캔_상한_100명을_넘어가지_않는다() {
            long nowMillis = System.currentTimeMillis();
            long staleMillis = nowMillis - 100_000;

            for (int i = 1; i <= 101; i++) {
                String userId = "user-" + i;
                redisTemplate.opsForZSet().add(QueueRedisKeys.WAITING_QUEUE, userId, i);
                redisTemplate.opsForZSet().add(
                        QueueRedisKeys.POLL_TRACKER,
                        userId,
                        i <= 100 ? staleMillis : nowMillis
                );
            }

            long promoted = repository.promoteWaitingUsers(1, 600, 90, 100);

            assertThat(promoted).isEqualTo(0);
            assertThat(repository.activeCount()).isEqualTo(0);
            assertThat(repository.waitingQueueSize()).isEqualTo(1);
            assertThat(redisTemplate.opsForZSet().score(QueueRedisKeys.WAITING_QUEUE, "user-101")).isNotNull();
        }

        @Test
        @DisplayName("만료된_active는_승격_전에_먼저_제거된다")
        void 만료된_active는_승격_전에_먼저_제거된다() {
            repository.enterOrQueue("user-1", "active-1", 600);
            activateUser("expired-user", "expired-token", -1);

            long promoted = repository.promoteWaitingUsers(1, 600, 90, 100);

            assertThat(promoted).isEqualTo(1);
            assertThat(repository.activeCount()).isEqualTo(1);
            assertThat(redisTemplate.opsForZSet().score(QueueRedisKeys.ACTIVE_SET, "user-1")).isNotNull();
        }
    }

    @Nested
    @DisplayName("정리 작업 테스트")
    class CleanupTests {

        @Test
        @DisplayName("만료된_활성_사용자가_제거된다")
        void 만료된_활성_사용자가_제거된다() {
            activateUser("user-1", "active-1", -1);

            long removed = repository.cleanupExpiredActive();
            assertThat(removed).isEqualTo(1);
            assertThat(repository.activeCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("stale_폴링_사용자가_대기열에서_제거된다")
        void stale_폴링_사용자가_대기열에서_제거된다() {
            repository.enterOrQueue("user-1", "active-1", 600);

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
            activateUser("user-1", token, 600);

            String result = repository.handlePaymentCallback("user-1", token, "SUCCESS", 600);

            assertThat(result).isEqualTo("REMOVED");
            assertThat(repository.activeCount()).isEqualTo(0);
            assertThat(redisTemplate.hasKey(QueueRedisKeys.activeTokenKey("user-1"))).isFalse();
        }

        @Test
        @DisplayName("실패_콜백이면_TTL이_갱신된다")
        void 실패_콜백이면_TTL이_갱신된다() {
            String token = "active-1";
            activateUser("user-1", token, 600);

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
            activateUser("user-1", token, 600);

            String result = repository.handlePaymentCallback("user-2", token, "SUCCESS", 600);

            assertThat(result).isEqualTo("NOT_FOUND");
            assertThat(repository.activeCount()).isEqualTo(1);
        }
    }

    private void activateUser(String userId, String token, int ttlSeconds) {
        long expireAtMillis = ttlSeconds > 0
                ? System.currentTimeMillis() + ttlSeconds * 1000L
                : System.currentTimeMillis() - 1_000L;
        redisTemplate.opsForZSet().add(QueueRedisKeys.ACTIVE_SET, userId, expireAtMillis);
        if (ttlSeconds > 0) {
            redisTemplate.opsForValue().set(QueueRedisKeys.activeTokenKey(userId), token, java.time.Duration.ofSeconds(ttlSeconds));
        } else {
            redisTemplate.opsForValue().set(QueueRedisKeys.activeTokenKey(userId), token);
        }
    }
}
