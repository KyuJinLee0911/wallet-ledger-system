package com.example.walletledger.service.dto;

import java.math.BigDecimal;

public record MoneyCommand(
    Long walletId,
    BigDecimal amount,
    String description,
    String idempotencyKey
) {
}

