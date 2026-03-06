package com.example.walletledger.controller.dto.response;

import com.example.walletledger.domain.transaction.WalletTransaction;

/**
 * 출금 API 응답 DTO.
 *
 * 입금 응답과 동일한 구조를 유지해 클라이언트 처리 일관성을 높인다.
 */
public record WithdrawResponse(
    TransactionResponse transaction
) {
    /**
     * 도메인 거래 객체를 출금 응답 DTO로 변환한다.
     */
    public static WithdrawResponse from(WalletTransaction transaction) {
        return new WithdrawResponse(TransactionResponse.from(transaction));
    }
}

