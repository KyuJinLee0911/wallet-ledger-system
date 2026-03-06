package com.example.walletledger.repository;

import com.example.walletledger.domain.wallet.Wallet;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WalletRepository extends JpaRepository<Wallet, Long> {

    /**
     * MVP에서는 비관적 락을 단일 전략으로 사용한다.
     *
     * 출금/이체 시 동일 지갑 동시 수정으로 인한 정합성 깨짐을 방지하기 위해
     * 지갑 행을 PESSIMISTIC_WRITE로 잠근 뒤 서비스 트랜잭션에서 처리한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.id = :walletId")
    Optional<Wallet> findByIdForUpdate(@Param("walletId") Long walletId);
}
