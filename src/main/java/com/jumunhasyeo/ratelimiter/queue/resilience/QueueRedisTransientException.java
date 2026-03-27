package com.jumunhasyeo.ratelimiter.queue.resilience;

public class QueueRedisTransientException extends RuntimeException {

    public QueueRedisTransientException(String operation, Throwable cause) {
        super("Temporary Redis failure during queue operation: " + operation, cause);
    }
}
