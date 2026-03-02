package com.jumunhasyeo.ratelimiter.queue.dto;

import jakarta.validation.constraints.NotBlank;

public record QueuePaymentCallbackRequest(
        @NotBlank String userId,
        @NotBlank String activeToken,
        @NotBlank String status
) {
}
