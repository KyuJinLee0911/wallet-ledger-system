package com.example.walletledger.repository;

import com.example.walletledger.domain.ledger.LedgerEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {
    Page<LedgerEntry> findByWalletId(Long walletId, Pageable pageable);
}
