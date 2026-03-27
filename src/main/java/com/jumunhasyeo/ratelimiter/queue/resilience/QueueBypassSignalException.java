package com.jumunhasyeo.ratelimiter.queue.resilience;

public class QueueBypassSignalException extends RuntimeException {

    public QueueBypassSignalException(Throwable cause) {
        super("Queue is temporarily bypassed", cause);
    }
}
