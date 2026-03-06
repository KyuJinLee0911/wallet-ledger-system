package com.example.walletledger.controller.dto.response;

import com.example.walletledger.domain.transaction.WalletTransaction;

/**
 * 이체 API 응답 DTO.
 *
 * 이체 처리 결과를 거래 응답으로 감싸 API 계약을 명확히 표현한다.
 */
public record TransferResponse(
    TransactionResponse transaction
) {
    /**
     * 도메인 거래 객체를 이체 응답 DTO로 변환한다.
     */
    public static TransferResponse from(WalletTransaction transaction) {
        return new TransferResponse(TransactionResponse.from(transaction));
    }
}

