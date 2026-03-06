package com.example.walletledger.controller.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 지갑 생성 API 요청 DTO.
 *
 * 도메인 엔티티와 분리된 입력 전용 모델이며,
 * 요청 유효성 검증을 컨트롤러 계층에서 선제적으로 수행하기 위해 사용한다.
 */
public record WalletCreateRequest(
    @NotNull(message = "memberId는 필수입니다.")
    Long memberId,

    @Pattern(regexp = "^[A-Z]{3}$", message = "currency는 3자리 대문자 통화 코드여야 합니다.")
    String currency
) {
}

