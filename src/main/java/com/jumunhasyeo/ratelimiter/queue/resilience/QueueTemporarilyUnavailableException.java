package com.jumunhasyeo.ratelimiter.queue.resilience;

public class QueueTemporarilyUnavailableException extends RuntimeException {

    public QueueTemporarilyUnavailableException(Throwable cause) {
        super("Queue is temporarily unavailable", cause);
    }
}
