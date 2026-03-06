package com.example.walletledger.service.dto;

public record CreateWalletCommand(
    Long memberId,
    String currency
) {
}

