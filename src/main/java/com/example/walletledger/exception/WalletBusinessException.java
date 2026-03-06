package com.example.walletledger.exception;

public class WalletBusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public WalletBusinessException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    public WalletBusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}

