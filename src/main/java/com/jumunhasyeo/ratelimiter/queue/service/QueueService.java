package com.jumunhasyeo.ratelimiter.queue.service;

import com.jumunhasyeo.ratelimiter.queue.dto.QueueEntryRequest;
import com.jumunhasyeo.ratelimiter.queue.dto.QueueEntryResponse;
import com.jumunhasyeo.ratelimiter.queue.dto.QueuePollResponse;

public interface QueueService {

    QueueEntryResponse enter(QueueEntryRequest request);

    QueuePollResponse poll(String userId);

    String handlePaymentCallback(String userId, String activeToken, String status);
}
