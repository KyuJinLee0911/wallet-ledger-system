package com.example.walletledger.controller.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 입금 API 요청 DTO.
 *
 * 금액과 설명 필드를 API 스펙에 맞게 검증하며,
 * 비즈니스 로직 진입 전에 명백한 입력 오류를 차단한다.
 */
public record DepositRequest(
    @NotNull(message = "amount는 필수입니다.")
    @DecimalMin(value = "0.0001", message = "amount는 0보다 커야 합니다.")
    @Digits(integer = 19, fraction = 4, message = "amount 형식이 올바르지 않습니다.")
    BigDecimal amount,

    @Size(max = 500, message = "description은 500자를 초과할 수 없습니다.")
    String description
) {
}

