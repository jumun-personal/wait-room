package com.jumunhasyeo.ratelimiter.common.enums;

/**
 * Job 실패 시 실패 유형을 구분하는 enum.
 * 이 구분에 따라 후속 처리(재시도 여부 등)가 결정된다.
 */
public enum FailType {
    /**
     * 시스템 오류로 인한 실패.
     * PG 5xx, 네트워크 오류, timeout 등이 해당.
     * 워커 내부에서 재시도 대상이 된다.
     */
    SYSTEM,

    /**
     * 비시스템 오류로 인한 실패.
     * 인증 만료, 잔액 부족, 카드 한도 초과 등이 해당.
     * 재시도 불가 - 사용자가 다시 결제를 시도해야 한다.
     */
    NON_SYSTEM,

    /**
     * 실패가 아닌 상태.
     * JobStatus가 QUEUED, PROCESSING, SUCCEEDED일 때 사용.
     */
    NONE
}
