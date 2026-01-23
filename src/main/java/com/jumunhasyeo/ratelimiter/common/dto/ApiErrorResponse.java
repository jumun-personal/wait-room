package com.jumunhasyeo.ratelimiter.common.dto;

import com.jumunhasyeo.ratelimiter.common.enums.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 에러 응답을 위한 공통 포맷.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiErrorResponse {

    private boolean success;
    private String code;
    private String message;

    public static ApiErrorResponse of(ErrorCode errorCode) {
        return ApiErrorResponse.builder()
                .success(false)
                .code(errorCode.name())
                .message(errorCode.getDefaultMessage())
                .build();
    }

    public static ApiErrorResponse of(ErrorCode errorCode, String message) {
        return ApiErrorResponse.builder()
                .success(false)
                .code(errorCode.name())
                .message(message)
                .build();
    }
}
