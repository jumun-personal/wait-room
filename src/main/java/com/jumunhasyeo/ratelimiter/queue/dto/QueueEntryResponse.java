package com.jumunhasyeo.ratelimiter.queue.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record QueueEntryResponse(
        boolean allowed,
        String activeToken,
        Integer rank,
        Integer pollIntervalSeconds
) {

    public static QueueEntryResponse allowed(String activeToken) {
        return new QueueEntryResponse(true, activeToken, null, null);
    }

    public static QueueEntryResponse queued(int rank, int pollIntervalSeconds) {
        return new QueueEntryResponse(false, null, rank, pollIntervalSeconds);
    }
}
