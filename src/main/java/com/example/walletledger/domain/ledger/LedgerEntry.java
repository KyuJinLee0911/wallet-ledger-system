package com.example.walletledger.domain.ledger;

import com.example.walletledger.domain.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "ledger_entries")
public class LedgerEntry extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "wallet_id", nullable = false)
    private Long walletId;

    @Column(name = "transaction_id", nullable = false)
    private Long transactionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private LedgerEntryType type;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, precision = 19, scale = 4)
    private BigDecimal balanceAfter;

    @Column(length = 500)
    private String description;

    protected LedgerEntry() {
    }

    private LedgerEntry(Long walletId, Long transactionId, LedgerEntryType type,
                        BigDecimal amount, BigDecimal balanceAfter, String description) {
        this.walletId = walletId;
        this.transactionId = transactionId;
        this.type = type;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.description = description;
    }

    public static LedgerEntry credit(Long walletId, Long transactionId, BigDecimal amount,
                                     BigDecimal balanceAfter, String description) {
        return new LedgerEntry(walletId, transactionId, LedgerEntryType.CREDIT, amount, balanceAfter, description);
    }

    public static LedgerEntry debit(Long walletId, Long transactionId, BigDecimal amount,
                                    BigDecimal balanceAfter, String description) {
        return new LedgerEntry(walletId, transactionId, LedgerEntryType.DEBIT, amount, balanceAfter, description);
    }
}

