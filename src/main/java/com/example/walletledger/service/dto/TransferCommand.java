package com.example.walletledger.service.dto;

import java.math.BigDecimal;

public record TransferCommand(
    Long fromWalletId,
    Long toWalletId,
    BigDecimal amount,
    String description,
    String idempotencyKey
) {
}

