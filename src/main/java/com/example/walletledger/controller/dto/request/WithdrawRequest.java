package com.example.walletledger.controller.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 출금 API 요청 DTO.
 *
 * 입금과 동일한 금액 규칙을 적용해 API 레벨에서 일관된 입력 검증을 보장한다.
 */
public record WithdrawRequest(
    @NotNull(message = "amount는 필수입니다.")
    @DecimalMin(value = "0.0001", message = "amount는 0보다 커야 합니다.")
    @Digits(integer = 19, fraction = 4, message = "amount 형식이 올바르지 않습니다.")
    BigDecimal amount,

    @Size(max = 500, message = "description은 500자를 초과할 수 없습니다.")
    String description
) {
}

