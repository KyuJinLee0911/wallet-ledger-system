package com.example.walletledger.controller;

import com.example.walletledger.domain.transaction.WalletTransaction;
import com.example.walletledger.domain.wallet.Wallet;
import com.example.walletledger.service.WalletLedgerService;
import com.example.walletledger.service.dto.CreateWalletCommand;
import com.example.walletledger.service.dto.MoneyCommand;
import com.example.walletledger.service.dto.TransferCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class WalletCommandController {

    private final WalletLedgerService walletLedgerService;

    public WalletCommandController(WalletLedgerService walletLedgerService) {
        this.walletLedgerService = walletLedgerService;
    }

    @PostMapping("/wallets")
    public Wallet createWallet(@Valid @RequestBody CreateWalletRequest request) {
        return walletLedgerService.createWallet(new CreateWalletCommand(request.memberId(), request.currency()));
    }

    @PostMapping("/wallets/{walletId}/deposit")
    public WalletTransaction deposit(@PathVariable Long walletId,
                                     @RequestHeader("Idempotency-Key") String idempotencyKey,
                                     @Valid @RequestBody MoneyRequest request) {
        return walletLedgerService.deposit(new MoneyCommand(walletId, request.amount(), request.description(), idempotencyKey));
    }

    @PostMapping("/wallets/{walletId}/withdraw")
    public WalletTransaction withdraw(@PathVariable Long walletId,
                                      @RequestHeader("Idempotency-Key") String idempotencyKey,
                                      @Valid @RequestBody MoneyRequest request) {
        return walletLedgerService.withdraw(new MoneyCommand(walletId, request.amount(), request.description(), idempotencyKey));
    }

    @PostMapping("/transfers")
    public WalletTransaction transfer(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                      @Valid @RequestBody TransferRequest request) {
        return walletLedgerService.transfer(
            new TransferCommand(
                request.fromWalletId(),
                request.toWalletId(),
                request.amount(),
                request.description(),
                idempotencyKey
            )
        );
    }

    public record CreateWalletRequest(
        @NotNull Long memberId,
        String currency
    ) {
    }

    public record MoneyRequest(
        @NotNull @Positive BigDecimal amount,
        String description
    ) {
    }

    public record TransferRequest(
        @NotNull Long fromWalletId,
        @NotNull Long toWalletId,
        @NotNull @Positive BigDecimal amount,
        @NotBlank String description
    ) {
    }
}

