package com.jumunhasyeo.ratelimiter.queue.service;

import com.jumunhasyeo.ratelimiter.queue.config.QueueProperties;
import com.jumunhasyeo.ratelimiter.queue.dto.QueueEntryRequest;
import com.jumunhasyeo.ratelimiter.queue.dto.QueueEntryResponse;
import com.jumunhasyeo.ratelimiter.queue.dto.QueuePollResponse;
import com.jumunhasyeo.ratelimiter.queue.enums.QueueResult;
import com.jumunhasyeo.ratelimiter.queue.repository.WaitingQueueRedisRepository;
import com.jumunhasyeo.ratelimiter.queue.resilience.QueueResilienceExecutor;
import com.jumunhasyeo.ratelimiter.queue.resilience.QueueTemporarilyUnavailableException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class QueueServiceImpl implements QueueService {
    /**
     * Redis에서 받은 결과 문자열을 실제 API 응답으로 바꾸는 서비스다.
     * poll 중 장애가 나면 마지막으로 알고 있던 순번으로 응답을 복원하는 역할도 여기서 맡는다.
     */

    private final WaitingQueueRedisRepository repository;
    private final QueueProperties properties;
    private final QueueResilienceExecutor resilienceExecutor;

    @Override
    public QueueEntryResponse enter(QueueEntryRequest request) {
        String activeToken = UUID.randomUUID().toString();
        // enter는 "새로 줄을 서는 요청"이라 실제 대기열 상태를 읽지 못하면 복원할 정보가 없다.
        String result = resilienceExecutor.executeRequest(() -> {
            return repository.enterOrQueue(
                    request.userId(),
                    activeToken,
                    properties.activeTtlSeconds()
            );
        });

        QueueResult status = parseEnterResult(result);

        if (status == QueueResult.ALREADY_ACTIVE) {
            return QueueEntryResponse.allowed(QueueResult.extractPayload(result));
        }

        QueueResult.QueuedPayload queuedPayload = QueueResult.extractQueuedPayload(result);
        int rank = queuedPayload.rank() + 1; // 0-based → 1-based

        int pollIntervalSeconds = calculatePollIntervalSeconds(rank);
        return QueueEntryResponse.queued(rank, pollIntervalSeconds);
    }

    @Override
    public QueuePollResult poll(String userId, Integer lastKnownRank) {
        String activeToken = UUID.randomUUID().toString();
        try {
            // 정상일 때는 Redis가 알려준 최신 상태를 그대로 사용한다.
            String result = resilienceExecutor.executeRequest(() -> {
                return repository.poll(
                        userId,
                        activeToken,
                        properties.activeTtlSeconds()
                );
            });

            if (result.startsWith(QueueResult.ALREADY_ACTIVE.name() + ":")) {
                return QueuePollResult.normal(QueuePollResponse.admitted(QueueResult.extractPayload(result)));
            }

            int rank = parsePollRank(result);
            int pollIntervalSeconds = calculatePollIntervalSeconds(rank);
            return QueuePollResult.normal(QueuePollResponse.waiting(rank, pollIntervalSeconds));
        } catch (QueueTemporarilyUnavailableException e) {
            // 새 순번은 못 읽었더라도, 사용자가 마지막으로 본 순번이 있으면
            // 그 값을 기준으로 waiting 응답을 만들어 잠깐의 장애를 덜 거슬리게 넘긴다.
            if (lastKnownRank != null && lastKnownRank > 0) {
                int pollIntervalSeconds = calculatePollIntervalSeconds(lastKnownRank);
                return QueuePollResult.stale(QueuePollResponse.waiting(lastKnownRank, pollIntervalSeconds));
            }
            throw e;
        }
    }

    private int calculatePollIntervalSeconds(int rank) {
        if (rank < 1) {
            throw new IllegalArgumentException("rank는 1 이상이어야 합니다.");
        }
        if (rank <= 100) {
            return 5;
        }
        if (rank <= 1_000) {
            return 10;
        }
        return properties.maxPollIntervalSeconds();
    }

    @Override
    public String handlePaymentCallback(String userId, String activeToken, String status) {
        return resilienceExecutor.executeCallback(
                () -> repository.handlePaymentCallback(
                        userId,
                        activeToken,
                        status,
                        properties.activeTtlSeconds()
                ),
                () -> "DEFERRED"
        );
    }

    private QueueResult parseEnterResult(String result) {
        try {
            return QueueResult.from(result);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("예상하지 못한 queue enter 결과입니다: " + result, e);
        }
    }

    private int parsePollRank(String result) {
        // poll은 숫자 순번만 따로 내려주므로 여기서만 별도로 해석한다.
        if (result == null || result.isBlank() || !result.chars().allMatch(Character::isDigit)) {
            throw new IllegalStateException("예상하지 못한 queue poll 결과입니다: " + result);
        }

        try {
            return Integer.parseInt(result) + 1; // 0-based → 1-based
        } catch (NumberFormatException e) {
            throw new IllegalStateException("예상하지 못한 queue poll 결과입니다: " + result, e);
        }
    }
}
