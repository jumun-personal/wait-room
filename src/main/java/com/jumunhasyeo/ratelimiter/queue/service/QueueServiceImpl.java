package com.jumunhasyeo.ratelimiter.queue.service;

import com.jumunhasyeo.ratelimiter.queue.config.QueueProperties;
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

    @Override
    public QueueEntryResponse enter(QueueEntryRequest request) {
        String queueToken = UUID.randomUUID().toString();
        String result = repository.enterOrQueue(
                request.userId(),
                queueToken,
                properties.maxActiveTokens(),
                properties.metaTtlSeconds()
        );

        QueueResult status = QueueResult.from(result);

        if (status == QueueResult.ALREADY_ACTIVE) {
            return QueueEntryResponse.allowed(null);
        }
        if (status == QueueResult.ACTIVE) {
            return QueueEntryResponse.allowed(QueueResult.extractPayload(result));
        }

        int rank = Integer.parseInt(result) + 1; // 0-based → 1-based
        int estimatedWait = estimateWaitSeconds(rank);
        return QueueEntryResponse.queued(rank, estimatedWait, queueToken);
    }

    @Override
    public QueuePollResponse poll(String userId, String token) {
        String activeToken = UUID.randomUUID().toString();
        String result = repository.poll(
                userId,
                token,
                activeToken,
                properties.maxActiveTokens(),
                properties.metaTtlSeconds()
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
        int estimatedWait = estimateWaitSeconds(rank);
        return QueuePollResponse.waiting(rank, estimatedWait);
    }

    private int estimateWaitSeconds(int rank) {
        double throughputPerSecond = (double) properties.maxActiveTokens()
                / properties.estimatedProcessingSeconds();
        double waitSeconds = rank / throughputPerSecond;
        return Math.max(1, (int) Math.ceil(waitSeconds));
    }
}
