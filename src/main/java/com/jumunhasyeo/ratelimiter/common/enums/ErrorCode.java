package com.jumunhasyeo.ratelimiter.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * API 에러 코드 정의.
 * 각 에러 코드는 HTTP 상태 코드와 기본 메시지를 포함한다.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    /**
     * Admission Control에서 거절됨.
     * 큐 용량 초과 또는 처리율 대비 대기 시간이 길어 deadline 내 처리 불가.
     */
    APPROVE_ADMISSION_REJECTED(409, "Admission rejected: cannot guarantee processing within deadline"),

    /**
     * approve 요청이 너무 늦음.
     * 남은 시간(T_remain)이 Tmin(120초) 미만이어서 큐 수용 불가.
     */
    APPROVE_TOO_LATE(409, "Approve request too late: remaining time is below minimum threshold"),

    /**
     * 요청한 job을 찾을 수 없음.
     */
    JOB_NOT_FOUND(404, "Job not found"),

    /**
     * 잘못된 요청.
     * 필수 파라미터 누락, 형식 오류 등.
     */
    INVALID_REQUEST(400, "Invalid request"),

    /**
     * 인증 실패.
     */
    UNAUTHORIZED(401, "Unauthorized");

    private final int httpStatus;
    private final String defaultMessage;
}
