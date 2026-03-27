package com.jumunhasyeo.ratelimiter.queue.controller;

import com.jumunhasyeo.ratelimiter.queue.config.AdmissionProperties;
import com.jumunhasyeo.ratelimiter.queue.dto.ApiErrorResponse;
import com.jumunhasyeo.ratelimiter.queue.http.QueueHttpHeaders;
import com.jumunhasyeo.ratelimiter.queue.resilience.QueueBypassSignalException;
import com.jumunhasyeo.ratelimiter.queue.resilience.QueueTemporarilyUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ApiExceptionHandler 단위 테스트")
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler(
            new AdmissionProperties(true, 100, 1_000, 5, 1)
    );

    @Test
    @DisplayName("QueueTemporarilyUnavailableException은 503과 Retry-After를 반환한다")
    void QueueTemporarilyUnavailableException은_503과_RetryAfter를_반환한다() {
        ResponseEntity<ApiErrorResponse> response = handler.handleTemporarilyUnavailable(
                new QueueTemporarilyUnavailableException(new RuntimeException("timeout"))
        );

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(response.getBody().code()).isEqualTo("QUEUE_TEMPORARILY_UNAVAILABLE");
    }

    @Test
    @DisplayName("QueueBypassSignalException은 bypass 헤더와 함께 503을 반환한다")
    void QueueBypassSignalException은_bypass_헤더와_함께_503을_반환한다() {
        ResponseEntity<ApiErrorResponse> response = handler.handleBypass(
                new QueueBypassSignalException(new RuntimeException("open"))
        );

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeaders().getFirst(QueueHttpHeaders.QUEUE_MODE)).isEqualTo(QueueHttpHeaders.BYPASS);
        assertThat(response.getHeaders().getFirst(QueueHttpHeaders.QUEUE_STATE)).isEqualTo(QueueHttpHeaders.BYPASS);
        assertThat(response.getBody().code()).isEqualTo("QUEUE_BYPASS");
    }
}
