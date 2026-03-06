package com.example.walletledger.controller.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 이체 API 요청 DTO.
 *
 * 송신/수신 지갑 식별자와 금액 검증을 수행하며,
 * 동일 지갑 간 이체 같은 사전 차단 가능한 케이스를 API 입력 단계에서 걸러낸다.
 */
public record TransferRequest(
    @NotNull(message = "fromWalletId는 필수입니다.")
    Long fromWalletId,

    @NotNull(message = "toWalletId는 필수입니다.")
    Long toWalletId,

    @NotNull(message = "amount는 필수입니다.")
    @DecimalMin(value = "0.0001", message = "amount는 0보다 커야 합니다.")
    @Digits(integer = 19, fraction = 4, message = "amount 형식이 올바르지 않습니다.")
    BigDecimal amount,

    @Size(max = 500, message = "description은 500자를 초과할 수 없습니다.")
    String description
) {
    /**
     * 동일 지갑으로의 이체를 요청 레벨에서 방지하기 위한 검증 규칙.
     */
    @AssertTrue(message = "fromWalletId와 toWalletId는 서로 달라야 합니다.")
    public boolean isDifferentWallet() {
        if (fromWalletId == null || toWalletId == null) {
            return true;
        }
        return !fromWalletId.equals(toWalletId);
    }
}

