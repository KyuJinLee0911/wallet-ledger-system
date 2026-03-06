package com.example.walletledger.controller.dto.response;

import java.time.Instant;

/**
 * 지갑 시스템 공통 API 응답 래퍼.
 *
 * 모든 성공/실패 응답을 동일한 구조로 감싸서
 * 컨트롤러와 예외 처리기에서 일관된 응답 형식을 유지하기 위해 사용한다.
 *
 * success=true  : data 필드 사용
 * success=false : error 필드 사용
 */
public record ApiResponse<T>(
    boolean success,
    T data,
    ApiError error,
    Instant timestamp
) {

    /**
     * 데이터가 있는 성공 응답을 생성한다.
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null, Instant.now());
    }

    /**
     * 본문 데이터가 필요 없는 성공 응답을 생성한다.
     */
    public static ApiResponse<Void> success() {
        return new ApiResponse<>(true, null, null, Instant.now());
    }

    /**
     * 공통 실패 응답을 생성한다.
     */
    public static <T> ApiResponse<T> failure(ApiError error) {
        return new ApiResponse<>(false, null, error, Instant.now());
    }

    /**
     * 코드와 메시지만으로 실패 응답을 생성한다.
     */
    public static <T> ApiResponse<T> failure(String code, String message) {
        return failure(ApiError.of(code, message));
    }

    /**
     * 코드, 메시지, 상세정보를 포함한 실패 응답을 생성한다.
     */
    public static <T> ApiResponse<T> failure(String code, String message, String detail) {
        return failure(ApiError.of(code, message, detail));
    }
}

