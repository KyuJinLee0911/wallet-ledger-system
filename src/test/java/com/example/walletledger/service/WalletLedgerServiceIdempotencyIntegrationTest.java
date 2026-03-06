package com.example.walletledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.example.walletledger.domain.member.Member;
import com.example.walletledger.domain.transaction.WalletTransaction;
import com.example.walletledger.domain.wallet.Wallet;
import com.example.walletledger.repository.LedgerEntryRepository;
import com.example.walletledger.repository.MemberRepository;
import com.example.walletledger.repository.TransactionRepository;
import com.example.walletledger.repository.WalletRepository;
import com.example.walletledger.service.dto.CreateWalletCommand;
import com.example.walletledger.service.dto.MoneyCommand;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
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
class WalletLedgerServiceIdempotencyIntegrationTest {

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
        // 테스트 간 데이터 간섭을 막기 위해 원장 -> 거래 -> 지갑 -> 회원 순서로 정리한다.
        ledgerEntryRepository.deleteAllInBatch();
        transactionRepository.deleteAllInBatch();
        walletRepository.deleteAllInBatch();
        memberRepository.deleteAllInBatch();
    }

    @Test
    void 순차_중복_입금_요청시_원본_거래를_재반환하고_중복_데이터를_생성하지_않는다() {
        // 시나리오 1 사전 조건: 회원/지갑 준비, 멱등 키는 아직 DB에 없는 상태여야 한다.
        Member member = memberRepository.save(new Member("member_seq_001", "member_seq_001@test.local"));
        Wallet wallet = walletLedgerService.createWallet(new CreateWalletCommand(member.getId(), "KRW"));

        String idempotencyKey = "key-seq-001";
        MoneyCommand command = new MoneyCommand(wallet.getId(), BigDecimal.valueOf(5000), "순차 재시도 테스트", idempotencyKey);

        WalletTransaction first = walletLedgerService.deposit(command);
        WalletTransaction second = walletLedgerService.deposit(command);

        // 응답 동일성 검증: 두 번째 요청은 첫 번째 요청의 원본 거래 결과를 그대로 반환해야 한다.
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getAmount().compareTo(first.getAmount())).isZero();
        assertThat(second.getCompletedAt())
            .isCloseTo(first.getCompletedAt(), within(1, ChronoUnit.MICROS));

        // DB 불변성 검증: 동일 멱등 키 거래는 1건만 존재해야 한다.
        Long txCountByKey = ((Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM transactions WHERE idempotency_key = :key")
            .setParameter("key", idempotencyKey)
            .getSingleResult()).longValue();
        assertThat(txCountByKey).isEqualTo(1L);

        Long ledgerCountByTx = ((Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM ledger_entries WHERE transaction_id = :txId")
            .setParameter("txId", first.getId())
            .getSingleResult()).longValue();
        assertThat(ledgerCountByTx).isEqualTo(1L);

        // 잔액 정합성 검증: 중복 처리로 두 번 반영되지 않고 5,000만 반영되어야 한다.
        Wallet refreshedWallet = walletRepository.findById(wallet.getId()).orElseThrow();
        assertThat(refreshedWallet.getBalance().compareTo(BigDecimal.valueOf(5000))).isZero();
    }
}
