package com.jumunhasyeo.ratelimiter.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 성공 응답을 위한 공통 래퍼.
 *
 * @param <T> 응답 데이터 타입
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {

    private boolean success;
    private T data;

    public static <T> ApiResponse<T> ok(T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .data(data)
                .build();
    }

    public static <T> ApiResponse<T> ok() {
        return ApiResponse.<T>builder()
                .success(true)
                .data(null)
                .build();
    }
}
