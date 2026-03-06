package com.example.walletledger.domain.transaction;

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
import java.time.Instant;

@Entity
@Table(name = "transactions")
public class WalletTransaction extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 100)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionStatus status;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected WalletTransaction() {
    }

    private WalletTransaction(String idempotencyKey, TransactionType type, BigDecimal amount) {
        this.idempotencyKey = idempotencyKey;
        this.type = type;
        this.status = TransactionStatus.PENDING;
        this.amount = amount;
        // 서버 로컬 타임존 영향 없이 절대 시각으로 기록해 환경별 시간 해석 차이를 방지한다.
        this.requestedAt = Instant.now();
    }

    public static WalletTransaction start(String idempotencyKey, TransactionType type, BigDecimal amount) {
        return new WalletTransaction(idempotencyKey, type, amount);
    }

    public void complete() {
        this.status = TransactionStatus.COMPLETED;
        this.completedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public TransactionType getType() {
        return type;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
