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
@Testcontainers
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
}

