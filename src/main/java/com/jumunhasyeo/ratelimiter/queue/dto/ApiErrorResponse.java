package com.jumunhasyeo.ratelimiter.queue.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(
        String code,
        String message,
        String reason,
        Integer retryAfterSeconds
) {

    public static ApiErrorResponse of(String code, String message) {
        return new ApiErrorResponse(code, message, null, null);
    }

    public static ApiErrorResponse tooManyRequests(String message, String reason, int retryAfterSeconds) {
        return new ApiErrorResponse("TOO_MANY_REQUESTS", message, reason, retryAfterSeconds);
    }

    public static ApiErrorResponse queueTemporarilyUnavailable(int retryAfterSeconds) {
        return new ApiErrorResponse(
                "QUEUE_TEMPORARILY_UNAVAILABLE",
                "Queue is temporarily unavailable",
                "redis_transient",
                retryAfterSeconds
        );
    }

    public static ApiErrorResponse queueBypass() {
        return new ApiErrorResponse(
                "QUEUE_BYPASS",
                "Queue temporarily bypassed",
                "request_circuit_open",
                null
        );
    }
}
