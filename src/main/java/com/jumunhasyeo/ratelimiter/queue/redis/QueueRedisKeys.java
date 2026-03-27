package com.jumunhasyeo.ratelimiter.queue.redis;

public final class QueueRedisKeys {

    private QueueRedisKeys() {
    }

    /** ZSET — 대기열 (score = epochMillis, member = userId) */
    public static final String WAITING_QUEUE = "q:waitroom:waiting";

    /** ZSET — 현재 활성 사용자 (score = expireAtMillis, member = userId) */
    public static final String ACTIVE_SET = "q:waitroom:active";

    /** ZSET — 폴링 추적기 (score = lastPolledAtMillis, member = userId) */
    public static final String POLL_TRACKER = "q:waitroom:poll-tracker";

    /** String token — 활성 사용자 토큰  */
    private static final String ACTIVE_TOKEN_PREFIX = "q:waitroom:active-token:";


    /** STRING — 스케줄러 분산 락 */
    public static final String CLEANUP_LOCK = "q:waitroom:lock:cleanup";

    /** STRING — 승격 스케줄러 분산 락 */
    public static final String PROMOTION_LOCK = "q:waitroom:lock:promotion";

    /** STRING — 동적 설정 (max active tokens) */
    public static final String MAX_ACTIVE_TOKENS = "q:waitroom:max-active-tokens";

    public static String activeTokenKey(String userId) {
        return ACTIVE_TOKEN_PREFIX + userId;
    }

    public static String activeTokenPrefix() {
        return ACTIVE_TOKEN_PREFIX;
    }
}
