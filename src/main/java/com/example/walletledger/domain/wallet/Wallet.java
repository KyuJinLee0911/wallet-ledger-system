package com.example.walletledger.domain.wallet;

import com.example.walletledger.domain.common.BaseEntity;
import com.example.walletledger.exception.ErrorCode;
import com.example.walletledger.exception.WalletBusinessException;
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
@Table(name = "wallets")
public class Wallet extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long memberId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    @Column(nullable = false, length = 10)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WalletStatus status;

    protected Wallet() {
    }

    private Wallet(Long memberId, String currency) {
        this.memberId = memberId;
        this.currency = currency;
        this.balance = BigDecimal.ZERO;
        this.status = WalletStatus.ACTIVE;
    }

    public static Wallet create(Long memberId, String currency) {
        return new Wallet(memberId, currency);
    }

    public void deposit(BigDecimal amount) {
        validateActive();
        validateAmount(amount);
        this.balance = this.balance.add(amount);
    }

    public void withdraw(BigDecimal amount) {
        validateActive();
        validateAmount(amount);
        if (this.balance.compareTo(amount) < 0) {
            throw new WalletBusinessException(ErrorCode.INSUFFICIENT_BALANCE, "출금 가능한 잔액이 부족합니다.");
        }
        this.balance = this.balance.subtract(amount);
    }

    public void freeze() {
        this.status = WalletStatus.FROZEN;
    }

    private void validateActive() {
        if (this.status != WalletStatus.ACTIVE) {
            throw new WalletBusinessException(ErrorCode.WALLET_NOT_ACTIVE, "비활성 지갑에서는 거래를 처리할 수 없습니다.");
        }
    }

    private void validateAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new WalletBusinessException(ErrorCode.INVALID_AMOUNT, "거래 금액은 0보다 커야 합니다.");
        }
    }

    public Long getId() {
        return id;
    }

    public Long getMemberId() {
        return memberId;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public String getCurrency() {
        return currency;
    }

    public WalletStatus getStatus() {
        return status;
    }
}
