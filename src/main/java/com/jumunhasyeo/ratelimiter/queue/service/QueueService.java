package com.jumunhasyeo.ratelimiter.queue.service;

import com.jumunhasyeo.ratelimiter.queue.dto.QueueEntryRequest;
import com.jumunhasyeo.ratelimiter.queue.dto.QueueEntryResponse;

public interface QueueService {

    QueueEntryResponse enter(QueueEntryRequest request);

    QueuePollResult poll(String userId, Integer lastKnownRank);

    String handlePaymentCallback(String userId, String activeToken, String status);
}
