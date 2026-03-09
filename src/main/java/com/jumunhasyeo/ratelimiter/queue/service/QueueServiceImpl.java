package com.jumunhasyeo.ratelimiter.queue.service;

import com.jumunhasyeo.ratelimiter.queue.config.QueueProperties;
import com.jumunhasyeo.ratelimiter.queue.config.QueueRuntimeConfig;
import com.jumunhasyeo.ratelimiter.queue.dto.QueueEntryRequest;
import com.jumunhasyeo.ratelimiter.queue.dto.QueueEntryResponse;
import com.jumunhasyeo.ratelimiter.queue.dto.QueuePollResponse;
import com.jumunhasyeo.ratelimiter.queue.enums.QueueResult;
import com.jumunhasyeo.ratelimiter.queue.repository.WaitingQueueRedisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class QueueServiceImpl implements QueueService {

    private final WaitingQueueRedisRepository repository;
    private final QueueProperties properties;
    private final QueueRuntimeConfig runtimeConfig;

    @Override
    public QueueEntryResponse enter(QueueEntryRequest request) {
        String activeToken = UUID.randomUUID().toString();
        int maxActiveTokens = runtimeConfig.maxActiveTokens();
        String result = repository.enterOrQueue(
                request.userId(),
                activeToken,
                maxActiveTokens,
                properties.activeTtlSeconds()
        );

        QueueResult status = QueueResult.from(result);

        if (status == QueueResult.ALREADY_ACTIVE) {
            return QueueEntryResponse.allowed(null);
        }
        if (status == QueueResult.ACTIVE) {
            return QueueEntryResponse.allowed(QueueResult.extractPayload(result));
        }

        int rank;
        if (status == QueueResult.QUEUED) {
            QueueResult.QueuedPayload queuedPayload = QueueResult.extractQueuedPayload(result);
            rank = queuedPayload.rank() + 1; // 0-based → 1-based
        } else if (QueueResult.isRank(result)) {
            // Backward compatibility: old Lua may still return rank-only response.
            log.warn("Legacy queue-enter result format received. result={}", result);
            rank = Integer.parseInt(result) + 1; // 0-based → 1-based
        } else {
            throw new IllegalStateException("예상하지 못한 queue enter 결과입니다: " + result);
        }

        int estimatedWait = estimateWaitSeconds(rank, maxActiveTokens);
        int pollIntervalSeconds = calculatePollIntervalSeconds(estimatedWait);
        return QueueEntryResponse.queued(rank, estimatedWait, pollIntervalSeconds);
    }

    @Override
    public QueuePollResponse poll(String userId) {
        String activeToken = UUID.randomUUID().toString();
        int maxActiveTokens = runtimeConfig.maxActiveTokens();
        String result = repository.poll(
                userId,
                activeToken,
                maxActiveTokens,
                properties.activeTtlSeconds()
        );

        QueueResult status = QueueResult.from(result);

        if (status == QueueResult.ALREADY_ACTIVE) {
            return QueuePollResponse.admitted(null);
        }
        if (status == QueueResult.NOT_IN_QUEUE) {
            throw new IllegalStateException("대기열에 존재하지 않는 사용자입니다.");
        }
        if (status == QueueResult.ADMITTED) {
            return QueuePollResponse.admitted(QueueResult.extractPayload(result));
        }

        int rank = Integer.parseInt(result) + 1;
        int estimatedWait = estimateWaitSeconds(rank, maxActiveTokens);
        int pollIntervalSeconds = calculatePollIntervalSeconds(estimatedWait);
        return QueuePollResponse.waiting(rank, estimatedWait, pollIntervalSeconds);
    }

    private int estimateWaitSeconds(int rank, int maxActiveTokens) {
        double throughputPerSecond = (double) maxActiveTokens
                / properties.estimatedProcessingSeconds();
        double waitSeconds = rank / throughputPerSecond;
        return Math.max(1, (int) Math.ceil(waitSeconds));
    }

    private int calculatePollIntervalSeconds(int estimatedWaitSeconds) {
        if (estimatedWaitSeconds >= 600) { // 10분 이상
            return 30;
        }
        if (estimatedWaitSeconds >= 300) { // 5분 이상
            return 20;
        }
        if (estimatedWaitSeconds >= 60) { // 1분 이상
            return 10;
        }
        return 5;
    }

    @Override
    public String handlePaymentCallback(String userId, String activeToken, String status) {
        return repository.handlePaymentCallback(
                userId,
                activeToken,
                status,
                properties.activeTtlSeconds()
        );
    }
}
