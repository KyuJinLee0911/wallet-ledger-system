package com.example.walletledger.domain.wallet;

import com.example.walletledger.exception.ErrorCode;
import com.example.walletledger.exception.WalletBusinessException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WalletTest {

    @Test
    void 유효한_금액으로_입금하면_잔액이_증가한다() {
        // Arrange: 활성 지갑과 입금 금액을 준비한다.
        Wallet wallet = Wallet.create(1L, "KRW");
        BigDecimal amount = new BigDecimal("1000.00");

        // Act: 지갑에 금액을 입금한다.
        wallet.deposit(amount);

        // Assert: 잔액이 입금 금액만큼 증가한다.
        assertEquals(new BigDecimal("1000.00"), wallet.getBalance());
    }

    @Test
    void null_금액으로_입금하면_INVALID_AMOUNT_예외가_발생한다() {
        // Arrange: 활성 지갑을 준비한다.
        Wallet wallet = Wallet.create(1L, "KRW");

        // Act: null 금액으로 입금을 시도한다.
        WalletBusinessException exception = assertThrows(
                WalletBusinessException.class,
                () -> wallet.deposit(null)
        );

        // Assert: 유효하지 않은 금액 예외가 발생한다.
        assertEquals(ErrorCode.INVALID_AMOUNT, exception.getErrorCode());
    }

    @Test
    void 제로_금액으로_입금하면_INVALID_AMOUNT_예외가_발생한다() {
        // Arrange: 활성 지갑을 준비한다.
        Wallet wallet = Wallet.create(1L, "KRW");

        // Act: 0원 입금을 시도한다.
        WalletBusinessException exception = assertThrows(
                WalletBusinessException.class,
                () -> wallet.deposit(BigDecimal.ZERO)
        );

        // Assert: 유효하지 않은 금액 예외가 발생한다.
        assertEquals(ErrorCode.INVALID_AMOUNT, exception.getErrorCode());
    }

    @Test
    void 음수_금액으로_입금하면_INVALID_AMOUNT_예외가_발생한다() {
        // Arrange: 활성 지갑을 준비한다.
        Wallet wallet = Wallet.create(1L, "KRW");

        // Act: 음수 금액 입금을 시도한다.
        WalletBusinessException exception = assertThrows(
                WalletBusinessException.class,
                () -> wallet.deposit(new BigDecimal("-1.00"))
        );

        // Assert: 유효하지 않은 금액 예외가 발생한다.
        assertEquals(ErrorCode.INVALID_AMOUNT, exception.getErrorCode());
    }

    @Test
    void 유효한_금액으로_출금하면_잔액이_감소한다() {
        // Arrange: 잔액이 있는 활성 지갑을 준비한다.
        Wallet wallet = Wallet.create(1L, "KRW");
        wallet.deposit(new BigDecimal("1000.00"));

        // Act: 일부 금액을 출금한다.
        wallet.withdraw(new BigDecimal("300.00"));

        // Assert: 잔액이 출금 금액만큼 감소한다.
        assertEquals(new BigDecimal("700.00"), wallet.getBalance());
    }

    @Test
    void 잔액보다_큰_금액을_출금하면_INSUFFICIENT_BALANCE_예외가_발생한다() {
        // Arrange: 잔액이 있는 활성 지갑을 준비한다.
        Wallet wallet = Wallet.create(1L, "KRW");
        wallet.deposit(new BigDecimal("500.00"));

        // Act: 잔액을 초과하는 출금을 시도한다.
        WalletBusinessException exception = assertThrows(
                WalletBusinessException.class,
                () -> wallet.withdraw(new BigDecimal("600.00"))
        );

        // Assert: 잔액 부족 예외가 발생한다.
        assertEquals(ErrorCode.INSUFFICIENT_BALANCE, exception.getErrorCode());
    }

    @Test
    void 잔액과_동일한_금액을_출금하면_잔액이_0이_된다() {
        // Arrange: 출금할 금액과 동일한 잔액을 가진 지갑을 준비한다.
        Wallet wallet = Wallet.create(1L, "KRW");
        wallet.deposit(new BigDecimal("750.00"));

        // Act: 전액을 출금한다.
        wallet.withdraw(new BigDecimal("750.00"));

        // Assert: 잔액이 정확히 0이 된다.
        assertTrue(wallet.getBalance().compareTo(BigDecimal.ZERO) == 0);
    }

    @Test
    void 동결된_지갑에_입금하면_WALLET_NOT_ACTIVE_예외가_발생한다() {
        // Arrange: 동결된 지갑을 준비한다.
        Wallet wallet = Wallet.create(1L, "KRW");
        wallet.freeze();

        // Act: 동결된 지갑에 입금을 시도한다.
        WalletBusinessException exception = assertThrows(
                WalletBusinessException.class,
                () -> wallet.deposit(new BigDecimal("100.00"))
        );

        // Assert: 비활성 지갑 예외가 발생한다.
        assertEquals(ErrorCode.WALLET_NOT_ACTIVE, exception.getErrorCode());
    }

    @Test
    void 동결된_지갑에서_출금하면_WALLET_NOT_ACTIVE_예외가_발생한다() {
        // Arrange: 동결된 지갑을 준비한다.
        Wallet wallet = Wallet.create(1L, "KRW");
        wallet.deposit(new BigDecimal("100.00"));
        wallet.freeze();

        // Act: 동결된 지갑에서 출금을 시도한다.
        WalletBusinessException exception = assertThrows(
                WalletBusinessException.class,
                () -> wallet.withdraw(new BigDecimal("50.00"))
        );

        // Assert: 비활성 지갑 예외가 발생한다.
        assertEquals(ErrorCode.WALLET_NOT_ACTIVE, exception.getErrorCode());
    }
}
