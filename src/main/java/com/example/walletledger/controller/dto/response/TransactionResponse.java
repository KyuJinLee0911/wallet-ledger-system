package com.example.walletledger.controller.dto.response;

import com.example.walletledger.domain.transaction.WalletTransaction;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 거래 공통 응답 DTO.
 *
 * 입금/출금/이체 결과에서 공통으로 사용되는 거래 메타데이터를 캡슐화한다.
 */
public record TransactionResponse(
    Long transactionId,
    String type,
    String status,
    BigDecimal amount,
    Instant completedAt
) {
    /**
     * 도메인 WalletTransaction을 응답 DTO로 변환한다.
     *
     * 완료 시각은 Instant 기반으로 반환해 클라이언트가 타임존에 맞춰 일관되게 해석할 수 있다.
     */
    public static TransactionResponse from(WalletTransaction transaction) {
        return new TransactionResponse(
            transaction.getId(),
            transaction.getType().name(),
            transaction.getStatus().name(),
            transaction.getAmount(),
            transaction.getCompletedAt()
        );
    }
}
