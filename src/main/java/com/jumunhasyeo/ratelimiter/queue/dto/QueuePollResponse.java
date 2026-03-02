package com.jumunhasyeo.ratelimiter.queue.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record QueuePollResponse(
        boolean allowed,
        String activeToken,
        Integer rank,
        Integer estimatedWaitSeconds,
        Integer pollIntervalSeconds
) {

    public static QueuePollResponse admitted(String activeToken) {
        return new QueuePollResponse(true, activeToken, null, null, null);
    }

    public static QueuePollResponse waiting(int rank, int estimatedWaitSeconds, int pollIntervalSeconds) {
        return new QueuePollResponse(false, null, rank, estimatedWaitSeconds, pollIntervalSeconds);
    }
}
