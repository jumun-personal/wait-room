package com.jumunhasyeo.ratelimiter.queue.service;

import com.jumunhasyeo.ratelimiter.queue.dto.QueuePollResponse;

public record QueuePollResult(
        QueuePollResponse response,
        boolean stale
) {

    public static QueuePollResult normal(QueuePollResponse response) {
        return new QueuePollResult(response, false);
    }

    public static QueuePollResult stale(QueuePollResponse response) {
        return new QueuePollResult(response, true);
    }
}
