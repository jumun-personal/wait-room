package com.jumunhasyeo.ratelimiter.queue.controller;

import com.jumunhasyeo.ratelimiter.queue.dto.QueueEntryRequest;
import com.jumunhasyeo.ratelimiter.queue.dto.QueueEntryResponse;
import com.jumunhasyeo.ratelimiter.queue.dto.QueuePaymentCallbackRequest;
import com.jumunhasyeo.ratelimiter.queue.dto.QueuePaymentCallbackResponse;
import com.jumunhasyeo.ratelimiter.queue.dto.QueuePollResponse;
import com.jumunhasyeo.ratelimiter.queue.service.QueueService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/queue")
@RequiredArgsConstructor
public class QueueController {

    private final QueueService queueService;

    @PostMapping("/enter")
    public ResponseEntity<QueueEntryResponse> enter(@Valid @RequestBody QueueEntryRequest request) {
        QueueEntryResponse response = queueService.enter(request);
        if (response.allowed()) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.accepted().body(response);
    }

    @GetMapping("/poll")
    public ResponseEntity<QueuePollResponse> poll(
            @RequestParam String userId
    ) {
        QueuePollResponse response = queueService.poll(userId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/payment/callback")
    public ResponseEntity<QueuePaymentCallbackResponse> paymentCallback(
            @Valid @RequestBody QueuePaymentCallbackRequest request
    ) {
        String status = request.status().trim().toUpperCase();
        if (!status.equals("SUCCESS") && !status.equals("FAIL")) {
            return ResponseEntity.badRequest().body(new QueuePaymentCallbackResponse("INVALID_STATUS"));
        }

        String result = queueService.handlePaymentCallback(request.userId(), request.activeToken(), status);
        return switch (result) {
            case "REMOVED", "REFRESHED" -> ResponseEntity.ok(new QueuePaymentCallbackResponse(result));
            case "NOT_FOUND" -> ResponseEntity.status(404).body(new QueuePaymentCallbackResponse(result));
            case "MISMATCH", "NOT_ACTIVE" -> ResponseEntity.status(409).body(new QueuePaymentCallbackResponse(result));
            case "INVALID_ACTION" -> ResponseEntity.badRequest().body(new QueuePaymentCallbackResponse(result));
            default -> ResponseEntity.status(500).body(new QueuePaymentCallbackResponse("ERROR"));
        };
    }
}
