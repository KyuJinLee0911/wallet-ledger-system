package com.example.walletledger.controller;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.walletledger.domain.member.Member;
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
class WalletLedgerQueryIntegrationTest {

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
    void 특정_지갑_원장_이력을_페이지네이션으로_조회할_수_있고_다른_지갑_데이터는_섞이지_않는다() throws Exception {
        // 조회 대상 지갑/비대상 지갑을 만들고 각 지갑에 원장 데이터를 생성한다.
        Member member = memberRepository.save(new Member("member_ledger_query_001", "member_ledger_query_001@test.local"));
        Wallet targetWallet = walletRepository.save(Wallet.create(member.getId(), "KRW"));
        Wallet otherWallet = walletRepository.save(Wallet.create(member.getId(), "KRW"));

        walletLedgerService.deposit(new MoneyCommand(targetWallet.getId(), BigDecimal.valueOf(1000), "target-1", "k-" + UUID.randomUUID()));
        walletLedgerService.withdraw(new MoneyCommand(targetWallet.getId(), BigDecimal.valueOf(200), "target-2", "k-" + UUID.randomUUID()));
        walletLedgerService.deposit(new MoneyCommand(targetWallet.getId(), BigDecimal.valueOf(300), "target-3", "k-" + UUID.randomUUID()));

        // 다른 지갑 데이터가 섞이지 않는지 확인하기 위한 비교군 원장 데이터.
        walletLedgerService.deposit(new MoneyCommand(otherWallet.getId(), BigDecimal.valueOf(999), "other-1", "k-" + UUID.randomUUID()));

        mockMvc.perform(get("/wallets/{id}/ledger", targetWallet.getId())
                .param("page", "0")
                .param("size", "2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.content.length()").value(2))
            .andExpect(jsonPath("$.data.totalElements").value(3))
            .andExpect(jsonPath("$.data.totalPages").value(2))
            .andExpect(jsonPath("$.data.number").value(0))
            .andExpect(jsonPath("$.data.size").value(2))
            .andExpect(jsonPath("$.data.content[*].walletId", everyItem(is(targetWallet.getId().intValue()))))
            .andExpect(jsonPath("$.data.content[0].ledgerEntryId", notNullValue()))
            .andExpect(jsonPath("$.data.content[0].transactionId", notNullValue()))
            .andExpect(jsonPath("$.data.content[0].type", notNullValue()))
            .andExpect(jsonPath("$.data.content[0].amount", notNullValue()))
            .andExpect(jsonPath("$.data.content[0].balanceAfter", notNullValue()))
            .andExpect(jsonPath("$.data.content[0].createdAt", notNullValue()))
            .andExpect(jsonPath("$.error").value(nullValue()));

        // 다음 페이지에서도 같은 지갑 데이터만 조회되는지 확인한다.
        mockMvc.perform(get("/wallets/{id}/ledger", targetWallet.getId())
                .param("page", "1")
                .param("size", "2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.content.length()").value(1))
            .andExpect(jsonPath("$.data.totalElements").value(3))
            .andExpect(jsonPath("$.data.number").value(1))
            .andExpect(jsonPath("$.data.content[*].walletId", everyItem(is(targetWallet.getId().intValue()))));
    }

    @Test
    void 존재하지_않는_지갑_ID로_원장_조회하면_WALLET_NOT_FOUND를_반환한다() throws Exception {
        Long notExistingWalletId = 999_999L;

        mockMvc.perform(get("/wallets/{id}/ledger", notExistingWalletId)
                .param("page", "0")
                .param("size", "20"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.data").value(nullValue()))
            .andExpect(jsonPath("$.error.code").value("WALLET_NOT_FOUND"))
            .andExpect(jsonPath("$.error.message", notNullValue()))
            .andExpect(jsonPath("$.timestamp", notNullValue()));
    }
}
