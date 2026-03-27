package com.jumunhasyeo.ratelimiter.queue.http;

public final class QueueHttpHeaders {

    public static final String QUEUE_STATE = "X-Queue-State";
    public static final String QUEUE_MODE = "X-Queue-Mode";
    public static final String STALE = "STALE";
    public static final String BYPASS = "BYPASS";

    private QueueHttpHeaders() {
    }
}
