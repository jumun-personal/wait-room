package com.jumunhasyeo.ratelimiter.queue.service;

import com.jumunhasyeo.ratelimiter.queue.config.QueueProperties;
import com.jumunhasyeo.ratelimiter.queue.config.QueueRuntimeConfig;
import com.jumunhasyeo.ratelimiter.queue.dto.QueueEntryRequest;
import com.jumunhasyeo.ratelimiter.queue.dto.QueueEntryResponse;
import com.jumunhasyeo.ratelimiter.queue.dto.QueuePollResponse;
import com.jumunhasyeo.ratelimiter.queue.enums.QueueResult;
import com.jumunhasyeo.ratelimiter.queue.repository.WaitingQueueRedisRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class QueueServiceImpl implements QueueService {

    private final WaitingQueueRedisRepository repository;
    private final QueueProperties properties;
    private final QueueRuntimeConfig runtimeConfig;

    @Override
    public QueueEntryResponse enter(QueueEntryRequest request) {
        String queueToken = UUID.randomUUID().toString();
        int maxActiveTokens = runtimeConfig.maxActiveTokens();
        String result = repository.enterOrQueue(
                request.userId(),
                queueToken,
                maxActiveTokens,
                properties.metaTtlSeconds(),
                properties.activeTtlSeconds()
        );

        QueueResult status = QueueResult.from(result);

        if (status == QueueResult.ALREADY_ACTIVE) {
            return QueueEntryResponse.allowed(null);
        }
        if (status == QueueResult.ACTIVE) {
            return QueueEntryResponse.allowed(QueueResult.extractPayload(result));
        }

        int rank = Integer.parseInt(result) + 1; // 0-based → 1-based
        int estimatedWait = estimateWaitSeconds(rank, maxActiveTokens);
        int pollIntervalSeconds = calculatePollIntervalSeconds(estimatedWait);
        return QueueEntryResponse.queued(rank, estimatedWait, pollIntervalSeconds, queueToken);
    }

    @Override
    public QueuePollResponse poll(String userId, String token) {
        String activeToken = UUID.randomUUID().toString();
        int maxActiveTokens = runtimeConfig.maxActiveTokens();
        String result = repository.poll(
                userId,
                token,
                activeToken,
                maxActiveTokens,
                properties.metaTtlSeconds(),
                properties.activeTtlSeconds()
        );

        QueueResult status = QueueResult.from(result);

        if (status == QueueResult.ALREADY_ACTIVE) {
            return QueuePollResponse.admitted(null);
        }
        if (status == QueueResult.INVALID_TOKEN) {
            throw new IllegalArgumentException("유효하지 않은 대기열 토큰입니다.");
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
