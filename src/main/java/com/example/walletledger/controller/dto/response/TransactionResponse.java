package com.example.walletledger.controller.dto.response;

import com.example.walletledger.domain.transaction.WalletTransaction;

/**
 * 거래 공통 응답 DTO.
 *
 * 입금/출금/이체 결과에서 공통으로 사용되는 거래 메타데이터를 캡슐화한다.
 */
public record TransactionResponse(
    Long transactionId,
    String idempotencyKey,
    String type,
    String status
) {
    /**
     * 도메인 WalletTransaction을 응답 DTO로 변환한다.
     */
    public static TransactionResponse from(WalletTransaction transaction) {
        return new TransactionResponse(
            transaction.getId(),
            transaction.getIdempotencyKey(),
            transaction.getType().name(),
            transaction.getStatus().name()
        );
    }
}

