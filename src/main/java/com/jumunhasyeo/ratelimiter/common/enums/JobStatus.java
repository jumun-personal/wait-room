package com.jumunhasyeo.ratelimiter.common.enums;

/**
 * Job의 처리 상태를 나타내는 enum.
 * approve 요청이 job으로 큐잉된 후의 lifecycle을 추적한다.
 */
public enum JobStatus {
    /**
     * 큐에 대기 중인 상태.
     * 워커가 아직 이 job을 처리하지 않았다.
     */
    QUEUED,

    /**
     * 워커가 현재 이 job을 처리 중인 상태.
     * PG confirm 호출이 진행 중이다.
     */
    PROCESSING,

    /**
     * PG confirm이 성공적으로 완료된 상태.
     */
    SUCCEEDED,

    /**
     * job 처리가 실패한 상태.
     * FailType을 통해 실패 유형을 확인해야 한다.
     */
    FAILED
}
