package com.example.walletledger.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.walletledger.domain.member.Member;
import com.example.walletledger.exception.ErrorCode;
import com.example.walletledger.exception.WalletBusinessException;
import com.example.walletledger.repository.LedgerEntryRepository;
import com.example.walletledger.repository.MemberRepository;
import com.example.walletledger.repository.TransactionRepository;
import com.example.walletledger.repository.WalletRepository;
import com.example.walletledger.service.dto.CreateWalletCommand;
import com.example.walletledger.service.dto.MoneyCommand;
import com.example.walletledger.service.dto.TransferCommand;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class WalletLedgerServiceConcurrencyIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    private WalletLedgerService walletLedgerService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private EntityManager entityManager;

    @AfterEach
    void cleanUp() {
        // 멀티스레드 테스트는 트랜잭션 롤백으로 정리되지 않으므로 배치 삭제로 정리한다.
        ledgerEntryRepository.deleteAllInBatch();
        transactionRepository.deleteAllInBatch();
        walletRepository.deleteAllInBatch();
        memberRepository.deleteAllInBatch();
    }

    @Test
    void 동일_지갑_동시_출금_시_잔액_정합성이_깨지지_않아야_한다() throws Exception {
        // 문서 시나리오 1 조건: 잔액 10,000인 동일 지갑에 1,000원 출금 20건을 동시 요청한다.
        Member member = memberRepository.save(new Member("member_conc_001", "member_conc_001@test.local"));
        Long walletId = walletLedgerService.createWallet(new CreateWalletCommand(member.getId(), "KRW")).getId();
        walletLedgerService.deposit(new MoneyCommand(walletId, BigDecimal.valueOf(10_000), "동시성 테스트 초기 충전", "init-deposit-001"));

        int threadCount = 20;
        BigDecimal withdrawAmount = BigDecimal.valueOf(1_000);
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();
        Queue<Exception> unexpectedErrors = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    // 모든 스레드를 같은 시점에 출발시켜 실제 동시성 경쟁 상황을 만든다.
                    barrier.await(10, TimeUnit.SECONDS);
                    walletLedgerService.withdraw(
                        new MoneyCommand(
                            walletId,
                            withdrawAmount,
                            "동시 출금",
                            "concurrent-withdraw-" + UUID.randomUUID()
                        )
                    );
                    successCount.incrementAndGet();
                } catch (WalletBusinessException ex) {
                    if (ex.getErrorCode() == ErrorCode.INSUFFICIENT_BALANCE) {
                        failCount.incrementAndGet();
                    } else {
                        unexpectedErrors.add(ex);
                    }
                } catch (Exception ex) {
                    unexpectedErrors.add(ex);
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean finished = latch.await(30, TimeUnit.SECONDS);
        executorService.shutdownNow();
        assertThat(finished).isTrue();

        BigDecimal finalBalance = walletRepository.findById(walletId).orElseThrow().getBalance();

        // 핵심 불변식 1: 잔액은 절대 음수가 되면 안 된다.
        assertThat(finalBalance.compareTo(BigDecimal.ZERO)).isGreaterThanOrEqualTo(0);
        // 핵심 불변식 2: 잔액 10,000 / 출금 1,000 조건에서는 정확히 10건 성공, 10건 실패가 기대된다.
        assertThat(successCount.get()).isEqualTo(10);
        assertThat(failCount.get()).isEqualTo(10);
        assertThat(successCount.get() + failCount.get()).isEqualTo(threadCount);
        assertThat(unexpectedErrors).isEmpty();
        assertThat(finalBalance.compareTo(BigDecimal.ZERO)).isEqualTo(0);

        Long completedTxCount = ((Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM transactions WHERE status = 'COMPLETED'")
            .getSingleResult()).longValue();
        Long debitLedgerCount = ((Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM ledger_entries WHERE wallet_id = :walletId AND type = 'DEBIT'")
            .setParameter("walletId", walletId)
            .getSingleResult()).longValue();

        // DB 관점에서도 성공 건수와 거래/원장 기록 건수가 정확히 일치해야 한다.
        assertThat(completedTxCount).isEqualTo(successCount.get() + 1L); // 초기 충전(DEPOSIT) 1건 포함
        assertThat(debitLedgerCount).isEqualTo(successCount.get());
    }

    @Test
    void 역방향_동시_이체에서도_데드락이_발생하지_않고_결과가_일관적이다() throws Exception {
        // 문서 시나리오 2: 지갑 A->B, B->A를 같은 시점에 요청해 데드락이 없는지 검증한다.
        Member member = memberRepository.save(new Member("member_conc_transfer_001", "member_conc_transfer_001@test.local"));
        Long wallet1Id = walletLedgerService.createWallet(new CreateWalletCommand(member.getId(), "KRW")).getId();
        Long wallet2Id = walletLedgerService.createWallet(new CreateWalletCommand(member.getId(), "KRW")).getId();

        walletLedgerService.deposit(new MoneyCommand(wallet1Id, BigDecimal.valueOf(10_000), "초기 충전 A", "init-a-" + UUID.randomUUID()));
        walletLedgerService.deposit(new MoneyCommand(wallet2Id, BigDecimal.valueOf(10_000), "초기 충전 B", "init-b-" + UUID.randomUUID()));

        ExecutorService executorService = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        CountDownLatch latch = new CountDownLatch(2);
        AtomicInteger successCount = new AtomicInteger();
        Queue<Exception> unexpectedErrors = new ConcurrentLinkedQueue<>();

        executorService.submit(() -> {
            try {
                barrier.await(10, TimeUnit.SECONDS);
                walletLedgerService.transfer(
                    new TransferCommand(
                        wallet1Id,
                        wallet2Id,
                        BigDecimal.valueOf(3_000),
                        "A to B",
                        "transfer-a-to-b-" + UUID.randomUUID()
                    )
                );
                successCount.incrementAndGet();
            } catch (Exception ex) {
                unexpectedErrors.add(ex);
            } finally {
                latch.countDown();
            }
        });

        executorService.submit(() -> {
            try {
                barrier.await(10, TimeUnit.SECONDS);
                walletLedgerService.transfer(
                    new TransferCommand(
                        wallet2Id,
                        wallet1Id,
                        BigDecimal.valueOf(2_000),
                        "B to A",
                        "transfer-b-to-a-" + UUID.randomUUID()
                    )
                );
                successCount.incrementAndGet();
            } catch (Exception ex) {
                unexpectedErrors.add(ex);
            } finally {
                latch.countDown();
            }
        });

        boolean finished = latch.await(30, TimeUnit.SECONDS);
        executorService.shutdownNow();

        // 데드락이 없다면 지정시간 내 스레드가 종료되어야 한다.
        assertThat(finished).isTrue();
        assertThat(unexpectedErrors).isEmpty();
        assertThat(successCount.get()).isEqualTo(2);

        BigDecimal wallet1Balance = walletRepository.findById(wallet1Id).orElseThrow().getBalance();
        BigDecimal wallet2Balance = walletRepository.findById(wallet2Id).orElseThrow().getBalance();

        // A: 10,000 - 3,000 + 2,000 = 9,000 / B: 10,000 - 2,000 + 3,000 = 11,000
        assertThat(wallet1Balance.compareTo(BigDecimal.valueOf(9_000))).isZero();
        assertThat(wallet2Balance.compareTo(BigDecimal.valueOf(11_000))).isZero();

        Long completedTransferTxCount = ((Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM transactions WHERE type = 'TRANSFER' AND status = 'COMPLETED'")
            .getSingleResult()).longValue();
        Long transferLedgerCount = ((Number) entityManager.createNativeQuery(
                "SELECT COUNT(le.id) FROM ledger_entries le JOIN transactions t ON le.transaction_id = t.id WHERE t.type = 'TRANSFER'")
            .getSingleResult()).longValue();

        // 이체 2건은 각각 출금/입금 원장 2개씩 기록되므로 총 4개가 기록된다.
        assertThat(completedTransferTxCount).isEqualTo(2L);
        assertThat(transferLedgerCount).isEqualTo(4L);
    }
}
