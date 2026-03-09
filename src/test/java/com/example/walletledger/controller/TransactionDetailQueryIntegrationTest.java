package com.example.walletledger.controller;

import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.walletledger.domain.member.Member;
import com.example.walletledger.domain.transaction.WalletTransaction;
import com.example.walletledger.domain.wallet.Wallet;
import com.example.walletledger.repository.LedgerEntryRepository;
import com.example.walletledger.repository.MemberRepository;
import com.example.walletledger.repository.TransactionRepository;
import com.example.walletledger.repository.WalletRepository;
import com.example.walletledger.service.WalletLedgerService;
import com.example.walletledger.service.dto.MoneyCommand;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class TransactionDetailQueryIntegrationTest {

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
    private MockMvc mockMvc;

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

    @AfterEach
    void cleanUp() {
        // 테스트 간 데이터 간섭을 막기 위해 저장 순서의 역순으로 정리한다.
        ledgerEntryRepository.deleteAllInBatch();
        transactionRepository.deleteAllInBatch();
        walletRepository.deleteAllInBatch();
        memberRepository.deleteAllInBatch();
    }

    @Test
    void 존재하는_거래_ID로_단건_조회하면_거래_상세를_반환한다() throws Exception {
        // 조회 대상 거래를 만들기 위해 회원/지갑을 준비하고 입금 거래를 1건 생성한다.
        Member member = memberRepository.save(new Member("member_tx_query_001", "member_tx_query_001@test.local"));
        Wallet wallet = walletRepository.save(Wallet.create(member.getId(), "KRW"));

        WalletTransaction transaction = walletLedgerService.deposit(
            new MoneyCommand(
                wallet.getId(),
                BigDecimal.valueOf(1500),
                "거래 상세 조회 테스트 입금",
                "tx-query-" + UUID.randomUUID()
            )
        );

        mockMvc.perform(get("/transactions/{id}", transaction.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.transactionId").value(transaction.getId()))
            .andExpect(jsonPath("$.data.type").value("DEPOSIT"))
            .andExpect(jsonPath("$.data.status").value("COMPLETED"))
            .andExpect(jsonPath("$.data.amount").value(transaction.getAmount().doubleValue()))
            .andExpect(jsonPath("$.data.completedAt", notNullValue()))
            .andExpect(jsonPath("$.error").value(nullValue()))
            .andExpect(jsonPath("$.timestamp", notNullValue()));
    }

    @Test
    void 존재하지_않는_거래_ID로_조회하면_TRANSACTION_NOT_FOUND를_반환한다() throws Exception {
        Long notExistingTransactionId = 999_999L;

        mockMvc.perform(get("/transactions/{id}", notExistingTransactionId))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.data").value(nullValue()))
            .andExpect(jsonPath("$.error.code").value("TRANSACTION_NOT_FOUND"))
            .andExpect(jsonPath("$.error.message", notNullValue()))
            .andExpect(jsonPath("$.timestamp", notNullValue()));
    }
}
