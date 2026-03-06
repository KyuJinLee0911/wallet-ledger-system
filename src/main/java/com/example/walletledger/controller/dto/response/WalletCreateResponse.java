package com.example.walletledger.controller.dto.response;

import com.example.walletledger.domain.wallet.Wallet;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 지갑 생성 API 응답 DTO.
 *
 * 도메인 엔티티를 외부로 직접 노출하지 않고,
 * API 계약에 필요한 값만 전달하기 위한 출력 전용 모델이다.
 */
public record WalletCreateResponse(
    Long walletId,
    Long memberId,
    BigDecimal balance,
    String currency,
    String status,
    Instant createdAt
) {
    /**
     * 도메인 Wallet 객체를 API 응답 형태로 변환한다.
     */
    public static WalletCreateResponse from(Wallet wallet) {
        return new WalletCreateResponse(
            wallet.getId(),
            wallet.getMemberId(),
            wallet.getBalance(),
            wallet.getCurrency(),
            wallet.getStatus().name(),
            wallet.getCreatedAt()
        );
    }
}
