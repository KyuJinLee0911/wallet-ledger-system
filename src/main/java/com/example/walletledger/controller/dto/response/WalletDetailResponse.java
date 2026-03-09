package com.example.walletledger.controller.dto.response;

import com.example.walletledger.domain.wallet.Wallet;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 지갑 상세 조회 API 응답 DTO.
 *
 * 조회 API 계약에 필요한 지갑 속성만 응답으로 노출한다.
 */
public record WalletDetailResponse(
    Long walletId,
    Long memberId,
    BigDecimal balance,
    String currency,
    String status,
    Instant createdAt,
    Instant updatedAt
) {
    /**
     * Wallet 엔티티를 지갑 상세 응답 DTO로 변환한다.
     */
    public static WalletDetailResponse from(Wallet wallet) {
        return new WalletDetailResponse(
            wallet.getId(),
            wallet.getMemberId(),
            wallet.getBalance(),
            wallet.getCurrency(),
            wallet.getStatus().name(),
            wallet.getCreatedAt(),
            wallet.getUpdatedAt()
        );
    }
}
