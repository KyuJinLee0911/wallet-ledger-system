package com.example.walletledger.service;

import com.example.walletledger.domain.transaction.WalletTransaction;
import com.example.walletledger.domain.wallet.Wallet;
import com.example.walletledger.service.dto.CreateWalletCommand;
import com.example.walletledger.service.dto.MoneyCommand;
import com.example.walletledger.service.dto.TransferCommand;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * 지갑 원장 핵심 유스케이스를 처리하는 서비스.
 *
 * 이 서비스는 지갑 생성, 입금, 출금, 이체를 담당하며
 * 트랜잭션 경계 안에서 잔액 변경과 원장 기록이 함께 커밋되도록 보장한다.
 */
public interface WalletLedgerService {

    /**
     * 회원 소유의 지갑을 생성한다.
     *
     * 회원 존재 여부를 검증한 뒤 초기 잔액 0으로 지갑을 저장한다.
     */
    Wallet createWallet(CreateWalletCommand command);

    /**
     * 지갑 ID로 지갑 상세 정보를 조회한다.
     *
     * 조회 전용 유스케이스로 변경 없이 현재 지갑 상태를 반환한다.
     */
    Wallet getWallet(Long walletId);

    /**
     * 지갑에 금액을 입금한다.
     *
     * 지갑 행에 비관적 락을 획득하여 동시 요청에서 잔액 정합성을 보장한다.
     */
    WalletTransaction deposit(MoneyCommand command);

    /**
     * 지갑에서 금액을 출금한다.
     *
     * 지갑 행에 비관적 락을 획득하고 도메인 검증으로 잔액 부족을 차단한다.
     */
    WalletTransaction withdraw(MoneyCommand command);

    /**
     * 두 지갑 사이에서 금액을 이체한다.
     *
     * 데드락 방지를 위해 지갑 식별자 오름차순으로 락 순서를 고정한다.
     */
    WalletTransaction transfer(TransferCommand command);

    /**
     * 거래 목록을 페이지 단위로 조회한다.
     *
     * 대량 데이터 환경에서 전체 조회를 방지하고 조회 비용을 제어하기 위해 페이징을 사용한다.
     */
    Page<WalletTransaction> getTransactions(Pageable pageable);
}
