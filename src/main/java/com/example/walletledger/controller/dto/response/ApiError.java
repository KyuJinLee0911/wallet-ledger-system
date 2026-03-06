package com.example.walletledger.controller.dto.response;

/**
 * 공통 실패 응답에서 사용하는 에러 정보 DTO.
 *
 * 코드, 메시지, 상세 정보를 분리해 클라이언트가
 * 사용자 메시지와 개발자 디버깅 정보를 구분해서 처리할 수 있도록 한다.
 */
public record ApiError(
    String code,
    String message,
    String detail
) {

    /**
     * 상세 정보 없이 에러 객체를 생성한다.
     */
    public static ApiError of(String code, String message) {
        return new ApiError(code, message, null);
    }

    /**
     * 상세 정보를 포함한 에러 객체를 생성한다.
     */
    public static ApiError of(String code, String message, String detail) {
        return new ApiError(code, message, detail);
    }
}

