package com.example.walletledger.service;

import com.example.walletledger.domain.ledger.LedgerEntry;
import com.example.walletledger.domain.member.Member;
import com.example.walletledger.domain.transaction.TransactionStatus;
import com.example.walletledger.domain.transaction.TransactionType;
import com.example.walletledger.domain.transaction.WalletTransaction;
import com.example.walletledger.domain.wallet.Wallet;
import com.example.walletledger.exception.ErrorCode;
import com.example.walletledger.exception.WalletBusinessException;
import com.example.walletledger.repository.LedgerEntryRepository;
import com.example.walletledger.repository.MemberRepository;
import com.example.walletledger.repository.TransactionRepository;
import com.example.walletledger.repository.WalletRepository;
import com.example.walletledger.service.dto.CreateWalletCommand;
import com.example.walletledger.service.dto.MoneyCommand;
import com.example.walletledger.service.dto.TransferCommand;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지갑 원장 비즈니스 로직 구현체.
 *
 * 모든 금전성 연산은 단일 트랜잭션에서 처리하며,
 * 비관적 락을 통해 동시 요청에서도 음수 잔액이나 원장 누락이 발생하지 않도록 설계한다.
 */
@Service
public class WalletLedgerServiceImpl implements WalletLedgerService {

    private final MemberRepository memberRepository;
    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public WalletLedgerServiceImpl(MemberRepository memberRepository,
                                   WalletRepository walletRepository,
                                   TransactionRepository transactionRepository,
                                   LedgerEntryRepository ledgerEntryRepository) {
        this.memberRepository = memberRepository;
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    /**
     * 회원 존재를 검증한 뒤 새 지갑을 생성한다.
     *
     * 생성 자체는 단순 저장이지만 회원-지갑 무결성을 유지하기 위해 트랜잭션으로 감싼다.
     */
    @Override
    @Transactional
    public Wallet createWallet(CreateWalletCommand command) {
        if (command == null || command.memberId() == null) {
            throw new WalletBusinessException(ErrorCode.INVALID_REQUEST, "지갑 생성에는 회원 식별자가 필요합니다.");
        }

        Member member = memberRepository.findById(command.memberId())
            .orElseThrow(() -> new WalletBusinessException(ErrorCode.MEMBER_NOT_FOUND));

        String currency = (command.currency() == null || command.currency().isBlank()) ? "KRW" : command.currency();
        Wallet wallet = Wallet.create(member.getId(), currency);
        return walletRepository.save(wallet);
    }

    /**
     * 지갑에 입금 거래를 수행한다.
     *
     * 동일 지갑으로 동시 입금이 들어올 수 있으므로 지갑 행을 비관적 락으로 점유한 뒤
     * 잔액 변경, 거래 저장, 원장 저장을 하나의 트랜잭션으로 처리한다.
     */
    @Override
    @Transactional
    public WalletTransaction deposit(MoneyCommand command) {
        validateMoneyCommand(command);
        validateIdempotencyKey(command.idempotencyKey());

        Optional<WalletTransaction> replayTransaction = findReplayableTransaction(command.idempotencyKey(), TransactionType.DEPOSIT);
        if (replayTransaction.isPresent()) {
            return replayTransaction.get();
        }

        Wallet wallet = walletRepository.findByIdForUpdate(command.walletId())
            .orElseThrow(() -> new WalletBusinessException(ErrorCode.WALLET_NOT_FOUND));

        WalletTransaction tx = saveStartedTransaction(command.idempotencyKey(), TransactionType.DEPOSIT, command.amount());
        if (tx.getStatus() == TransactionStatus.COMPLETED) {
            // 동일 멱등 키의 정상 재시도인 경우 기존 성공 결과를 그대로 반환해 멱등성을 보장한다.
            return tx;
        }
        wallet.deposit(command.amount());
        tx.complete();

        ledgerEntryRepository.save(
            LedgerEntry.credit(wallet.getId(), tx.getId(), command.amount(), wallet.getBalance(), command.description())
        );
        return tx;
    }

    /**
     * 지갑에서 출금 거래를 수행한다.
     *
     * 동시 출금 시점에 Race Condition이 가장 많이 발생하므로 반드시 비관적 락을 잡고
     * 도메인 출금 검증을 통과한 경우에만 원장을 기록한다.
     */
    @Override
    @Transactional
    public WalletTransaction withdraw(MoneyCommand command) {
        validateMoneyCommand(command);
        validateIdempotencyKey(command.idempotencyKey());

        Optional<WalletTransaction> replayTransaction = findReplayableTransaction(command.idempotencyKey(), TransactionType.WITHDRAW);
        if (replayTransaction.isPresent()) {
            return replayTransaction.get();
        }

        Wallet wallet = walletRepository.findByIdForUpdate(command.walletId())
            .orElseThrow(() -> new WalletBusinessException(ErrorCode.WALLET_NOT_FOUND));

        WalletTransaction tx = saveStartedTransaction(command.idempotencyKey(), TransactionType.WITHDRAW, command.amount());
        if (tx.getStatus() == TransactionStatus.COMPLETED) {
            // 동시 재시도 상황에서 UNIQUE 충돌 후 기존 완료 거래를 읽은 경우 추가 처리 없이 반환한다.
            return tx;
        }
        wallet.withdraw(command.amount());
        tx.complete();

        ledgerEntryRepository.save(
            LedgerEntry.debit(wallet.getId(), tx.getId(), command.amount(), wallet.getBalance(), command.description())
        );
        return tx;
    }

    /**
     * 두 지갑 간 이체 거래를 수행한다.
     *
     * 이체는 출금과 입금이 동시에 성공해야 의미가 있으므로 단일 트랜잭션으로 처리하며
     * 락 획득 순서를 고정해 교차 요청에서 데드락을 방지한다.
     */
    @Override
    @Transactional
    public WalletTransaction transfer(TransferCommand command) {
        validateTransferCommand(command);
        validateIdempotencyKey(command.idempotencyKey());

        Optional<WalletTransaction> replayTransaction = findReplayableTransaction(command.idempotencyKey(), TransactionType.TRANSFER);
        if (replayTransaction.isPresent()) {
            return replayTransaction.get();
        }

        Long firstLockId = Math.min(command.fromWalletId(), command.toWalletId());
        Long secondLockId = Math.max(command.fromWalletId(), command.toWalletId());

        // 데드락 방지를 위해 모든 이체 요청에서 동일한 순서로 락을 획득한다.
        Wallet firstLockedWallet = walletRepository.findByIdForUpdate(firstLockId)
            .orElseThrow(() -> new WalletBusinessException(ErrorCode.WALLET_NOT_FOUND, "송신 또는 수신 지갑이 존재하지 않습니다."));
        Wallet secondLockedWallet = walletRepository.findByIdForUpdate(secondLockId)
            .orElseThrow(() -> new WalletBusinessException(ErrorCode.WALLET_NOT_FOUND, "송신 또는 수신 지갑이 존재하지 않습니다."));

        Wallet fromWallet = resolveById(firstLockedWallet, secondLockedWallet, command.fromWalletId());
        Wallet toWallet = resolveById(firstLockedWallet, secondLockedWallet, command.toWalletId());

        WalletTransaction tx = saveStartedTransaction(command.idempotencyKey(), TransactionType.TRANSFER, command.amount());
        if (tx.getStatus() == TransactionStatus.COMPLETED) {
            // 이미 성공한 이체 재요청은 원거래 결과만 반환하고 잔액/원장 재반영을 막는다.
            return tx;
        }

        fromWallet.withdraw(command.amount());
        toWallet.deposit(command.amount());
        tx.complete();

        // 출금/입금 원장을 같은 트랜잭션에서 저장해 한쪽만 기록되는 불일치 상태를 차단한다.
        ledgerEntryRepository.save(
            LedgerEntry.debit(fromWallet.getId(), tx.getId(), command.amount(), fromWallet.getBalance(), command.description())
        );
        ledgerEntryRepository.save(
            LedgerEntry.credit(toWallet.getId(), tx.getId(), command.amount(), toWallet.getBalance(), command.description())
        );

        return tx;
    }

    /**
     * 거래 목록을 페이지 단위로 조회한다.
     *
     * 읽기 전용 트랜잭션으로 조회해 불필요한 변경 감지를 줄이고
     * 대량 거래 데이터에서도 안정적으로 조회할 수 있도록 한다.
     */
    @Override
    @Transactional(readOnly = true)
    public Page<WalletTransaction> getTransactions(Pageable pageable) {
        return transactionRepository.findAll(pageable);
    }

    private Wallet resolveById(Wallet first, Wallet second, Long targetId) {
        if (Objects.equals(first.getId(), targetId)) {
            return first;
        }
        if (Objects.equals(second.getId(), targetId)) {
            return second;
        }
        throw new WalletBusinessException(ErrorCode.WALLET_NOT_FOUND);
    }

    private WalletTransaction saveStartedTransaction(String idempotencyKey, TransactionType type, java.math.BigDecimal amount) {
        try {
            return transactionRepository.save(WalletTransaction.start(idempotencyKey, type, amount));
        } catch (DataIntegrityViolationException ex) {
            Optional<WalletTransaction> existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent() && existing.get().getStatus() == TransactionStatus.COMPLETED && existing.get().getType() == type) {
                // DB UNIQUE 제약 충돌이 발생해도 기존 완료 거래를 반환해 재시도 요청을 멱등하게 처리한다.
                return existing.get();
            }
            throw new WalletBusinessException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT, "이미 처리 중이거나 충돌하는 멱등 키입니다.");
        }
    }

    private Optional<WalletTransaction> findReplayableTransaction(String idempotencyKey, TransactionType type) {
        return transactionRepository.findByIdempotencyKey(idempotencyKey)
            .filter(tx -> tx.getStatus() == TransactionStatus.COMPLETED && tx.getType() == type);
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            // 필수 입력 누락은 충돌이 아니라 잘못된 요청이므로 BAD_REQUEST 계열로 처리한다.
            throw new WalletBusinessException(ErrorCode.INVALID_REQUEST, "멱등 키는 필수입니다.");
        }
    }

    private void validateMoneyCommand(MoneyCommand command) {
        if (command == null || command.walletId() == null) {
            throw new WalletBusinessException(ErrorCode.INVALID_REQUEST, "지갑 식별자가 필요합니다.");
        }
        if (command.amount() == null || command.amount().signum() <= 0) {
            throw new WalletBusinessException(ErrorCode.INVALID_AMOUNT, "거래 금액은 0보다 커야 합니다.");
        }
    }

    private void validateTransferCommand(TransferCommand command) {
        if (command == null || command.fromWalletId() == null || command.toWalletId() == null) {
            throw new WalletBusinessException(ErrorCode.INVALID_REQUEST, "송신/수신 지갑 식별자가 필요합니다.");
        }
        if (command.amount() == null || command.amount().signum() <= 0) {
            throw new WalletBusinessException(ErrorCode.INVALID_AMOUNT, "거래 금액은 0보다 커야 합니다.");
        }
        if (Objects.equals(command.fromWalletId(), command.toWalletId())) {
            throw new WalletBusinessException(ErrorCode.SAME_WALLET_TRANSFER);
        }
    }
}
