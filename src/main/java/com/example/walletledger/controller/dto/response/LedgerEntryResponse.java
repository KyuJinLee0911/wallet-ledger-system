package com.example.walletledger.controller.dto.response;

import com.example.walletledger.domain.ledger.LedgerEntry;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 지갑 원장 내역 조회 응답 DTO.
 *
 * 원장 엔트리 상세 정보를 API 응답 형태로 전달한다.
 */
public record LedgerEntryResponse(
    Long ledgerEntryId,
    Long walletId,
    Long transactionId,
    String type,
    BigDecimal amount,
    BigDecimal balanceAfter,
    String description,
    Instant createdAt
) {
    /**
     * 원장 엔트리를 API 응답 DTO로 변환한다.
     */
    public static LedgerEntryResponse from(LedgerEntry ledgerEntry) {
        return new LedgerEntryResponse(
            ledgerEntry.getId(),
            ledgerEntry.getWalletId(),
            ledgerEntry.getTransactionId(),
            ledgerEntry.getType().name(),
            ledgerEntry.getAmount(),
            ledgerEntry.getBalanceAfter(),
            ledgerEntry.getDescription(),
            ledgerEntry.getCreatedAt()
        );
    }
}
