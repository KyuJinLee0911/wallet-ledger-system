package com.example.walletledger.controller.dto.response;

import com.example.walletledger.domain.transaction.WalletTransaction;

/**
 * 입금 API 응답 DTO.
 *
 * 거래 결과를 래핑해 입금 응답 계약을 명시적으로 분리한다.
 */
public record DepositResponse(
    TransactionResponse transaction
) {
    /**
     * 도메인 거래 객체를 입금 응답 DTO로 변환한다.
     */
    public static DepositResponse from(WalletTransaction transaction) {
        return new DepositResponse(TransactionResponse.from(transaction));
    }
}

