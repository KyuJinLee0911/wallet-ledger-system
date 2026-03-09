package com.example.walletledger.service;

import com.example.walletledger.domain.transaction.TransactionType;
import com.example.walletledger.domain.transaction.WalletTransaction;
import com.example.walletledger.repository.TransactionRepository;
import java.math.BigDecimal;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 멱등 키 기반 시작 거래(insert) 전용 서비스.
 *
 * UNIQUE 충돌이 발생할 수 있는 insert 시도를 별도 트랜잭션으로 분리해
 * 외부 트랜잭션/세션이 rollback-only 로 오염되지 않도록 한다.
 */
@Service
class StartedTransactionInsertService {

    private final TransactionRepository transactionRepository;

    StartedTransactionInsertService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /**
     * 시작 거래를 신규 저장한다.
     *
     * REQUIRES_NEW 경계에서 UNIQUE 충돌을 처리해 외부 트랜잭션 안정성을 보장한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    WalletTransaction insertStartedTransaction(String idempotencyKey, TransactionType type, BigDecimal amount) {
        try {
            return transactionRepository.save(WalletTransaction.start(idempotencyKey, type, amount));
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateIdempotencyKeyException(ex);
        }
    }

    static class DuplicateIdempotencyKeyException extends RuntimeException {
        DuplicateIdempotencyKeyException(Throwable cause) {
            super(cause);
        }
    }
}
