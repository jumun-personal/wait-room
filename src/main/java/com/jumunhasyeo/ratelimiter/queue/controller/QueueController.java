package com.jumunhasyeo.ratelimiter.queue.controller;

import com.jumunhasyeo.ratelimiter.queue.dto.QueueEntryRequest;
import com.jumunhasyeo.ratelimiter.queue.dto.QueueEntryResponse;
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
            @RequestParam String userId,
            @RequestParam String token
    ) {
        QueuePollResponse response = queueService.poll(userId, token);
        return ResponseEntity.ok(response);
    }
}
