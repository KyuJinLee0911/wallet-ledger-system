package com.example.walletledger.controller;

import com.example.walletledger.controller.dto.request.DepositRequest;
import com.example.walletledger.controller.dto.request.TransferRequest;
import com.example.walletledger.controller.dto.request.WalletCreateRequest;
import com.example.walletledger.controller.dto.request.WithdrawRequest;
import com.example.walletledger.controller.dto.response.ApiResponse;
import com.example.walletledger.controller.dto.response.TransactionResponse;
import com.example.walletledger.controller.dto.response.WalletCreateResponse;
import com.example.walletledger.domain.transaction.WalletTransaction;
import com.example.walletledger.service.WalletLedgerService;
import com.example.walletledger.service.dto.CreateWalletCommand;
import com.example.walletledger.service.dto.MoneyCommand;
import com.example.walletledger.service.dto.TransferCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 지갑 명령/조회 API 컨트롤러.
 *
 * 컨트롤러는 HTTP 요청/응답 변환만 담당하고,
 * 핵심 비즈니스 로직은 서비스 계층에 위임한다.
 */
@RestController
@Validated
public class WalletCommandController {

    private final WalletLedgerService walletLedgerService;

    public WalletCommandController(WalletLedgerService walletLedgerService) {
        this.walletLedgerService = walletLedgerService;
    }

    /**
     * 지갑 생성 요청을 처리한다.
     *
     * 요청 DTO를 서비스 커맨드로 변환한 뒤 공통 성공 응답으로 감싼다.
     */
    @PostMapping("/wallets")
    public ApiResponse<WalletCreateResponse> createWallet(@Valid @RequestBody WalletCreateRequest request) {
        return ApiResponse.success(
            WalletCreateResponse.from(
                walletLedgerService.createWallet(new CreateWalletCommand(request.memberId(), request.currency()))
            )
        );
    }

    /**
     * 지갑 입금 요청을 처리한다.
     *
     * 멱등 키 헤더와 요청 본문을 서비스에 전달하고,
     * 서비스 결과를 응답 DTO로 변환한다.
     */
    @PostMapping("/wallets/{id}/deposit")
    public ApiResponse<TransactionResponse> deposit(@PathVariable("id") @Positive(message = "walletId는 1 이상이어야 합니다.") Long walletId,
                                     @RequestHeader("Idempotency-Key") @NotBlank(message = "Idempotency-Key는 필수입니다.") String idempotencyKey,
                                     @Valid @RequestBody DepositRequest request) {
        WalletTransaction transaction = walletLedgerService.deposit(
            new MoneyCommand(walletId, request.amount(), request.description(), idempotencyKey)
        );
        // 입금/출금/이체는 모두 거래 단위 결과를 반환하므로 공통 거래 응답 DTO를 사용한다.
        return ApiResponse.success(TransactionResponse.from(transaction));
    }

    /**
     * 지갑 출금 요청을 처리한다.
     *
     * 컨트롤러는 유효성 검증 및 데이터 전달만 수행하고,
     * 잔액 검증/동시성 제어는 서비스 계층에 위임한다.
     */
    @PostMapping("/wallets/{id}/withdraw")
    public ApiResponse<TransactionResponse> withdraw(@PathVariable("id") @Positive(message = "walletId는 1 이상이어야 합니다.") Long walletId,
                                      @RequestHeader("Idempotency-Key") @NotBlank(message = "Idempotency-Key는 필수입니다.") String idempotencyKey,
                                      @Valid @RequestBody WithdrawRequest request) {
        WalletTransaction transaction = walletLedgerService.withdraw(
            new MoneyCommand(walletId, request.amount(), request.description(), idempotencyKey)
        );
        return ApiResponse.success(TransactionResponse.from(transaction));
    }

    /**
     * 지갑 간 이체 요청을 처리한다.
     *
     * 데드락 방지 락 순서 및 원장 정합성 보장은 서비스 트랜잭션에서 처리된다.
     */
    @PostMapping("/transfer")
    public ApiResponse<TransactionResponse> transfer(@RequestHeader("Idempotency-Key") @NotBlank(message = "Idempotency-Key는 필수입니다.") String idempotencyKey,
                                      @Valid @RequestBody TransferRequest request) {
        WalletTransaction transaction = walletLedgerService.transfer(
            new TransferCommand(
                request.fromWalletId(),
                request.toWalletId(),
                request.amount(),
                request.description(),
                idempotencyKey
            )
        );
        return ApiResponse.success(TransactionResponse.from(transaction));
    }

    /**
     * 거래 목록 조회를 처리한다.
     *
     * 기본 페이지 크기와 정렬을 적용해 대량 데이터 조회 시 과도한 응답을 방지한다.
     */
    @GetMapping("/transactions")
    public ApiResponse<Page<TransactionResponse>> getTransactions(
        @PageableDefault(size = 20, sort = "id", direction = org.springframework.data.domain.Sort.Direction.DESC)
        Pageable pageable
    ) {
        Page<TransactionResponse> transactions = walletLedgerService.getTransactions(pageable)
            .map(TransactionResponse::from);
        return ApiResponse.success(transactions);
    }
}
