package com.jumunhasyeo.ratelimiter.queue.controller;

import com.jumunhasyeo.ratelimiter.queue.config.AdmissionProperties;
import com.jumunhasyeo.ratelimiter.queue.dto.ApiErrorResponse;
import com.jumunhasyeo.ratelimiter.queue.http.QueueHttpHeaders;
import com.jumunhasyeo.ratelimiter.queue.resilience.QueueBypassSignalException;
import com.jumunhasyeo.ratelimiter.queue.resilience.QueueTemporarilyUnavailableException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@RequiredArgsConstructor
public class ApiExceptionHandler {

    private final AdmissionProperties admissionProperties;

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("BAD_REQUEST", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiErrorResponse> handleConflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of("CONFLICT", e.getMessage()));
    }

    @ExceptionHandler(QueueTemporarilyUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleTemporarilyUnavailable(QueueTemporarilyUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", String.valueOf(admissionProperties.retryAfterSeconds()))
                .body(ApiErrorResponse.queueTemporarilyUnavailable(admissionProperties.retryAfterSeconds()));
    }

    @ExceptionHandler(QueueBypassSignalException.class)
    public ResponseEntity<ApiErrorResponse> handleBypass(QueueBypassSignalException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(QueueHttpHeaders.QUEUE_MODE, QueueHttpHeaders.BYPASS)
                .header(QueueHttpHeaders.QUEUE_STATE, QueueHttpHeaders.BYPASS)
                .body(ApiErrorResponse.queueBypass());
    }
}
