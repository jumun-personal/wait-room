package com.jumunhasyeo.ratelimiter.queue.redis;

public final class QueueRedisKeys {

    private QueueRedisKeys() {
    }

    /** ZSET — 대기열 (score = epochMillis, member = userId) */
    public static final String WAITING_QUEUE = "q:waitroom:waiting";

    /** ZSET — 현재 주문서 진입 중인 사용자 (score = entryMillis, member = userId) */
    public static final String ACTIVE_SET = "q:waitroom:active";

    /** ZSET — 폴링 추적기 (score = lastPolledAtMillis, member = userId) */
    public static final String POLL_TRACKER = "q:waitroom:poll-tracker";

    /** HASH prefix — 활성 사용자 메타 (userId). TTL 부여 */
    private static final String ACTIVE_META_PREFIX = "q:waitroom:active-meta:";

    /** STRING — 스케줄러 분산 락 */
    public static final String CLEANUP_LOCK = "q:waitroom:lock:cleanup";

    /** STRING — 동적 설정 (max active tokens) */
    public static final String MAX_ACTIVE_TOKENS = "q:waitroom:max-active-tokens";

    public static String activeMetaKey(String activeToken) {
        return ACTIVE_META_PREFIX + activeToken;
    }
}
