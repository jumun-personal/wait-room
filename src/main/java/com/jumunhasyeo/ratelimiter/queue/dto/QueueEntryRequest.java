package com.jumunhasyeo.ratelimiter.queue.dto;

import jakarta.validation.constraints.NotBlank;

public record QueueEntryRequest(
        @NotBlank String userId
) {
}
