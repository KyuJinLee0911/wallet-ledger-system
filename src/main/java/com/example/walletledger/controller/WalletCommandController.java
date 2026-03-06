package com.example.walletledger.controller;

import com.example.walletledger.controller.dto.request.DepositRequest;
import com.example.walletledger.controller.dto.request.TransferRequest;
import com.example.walletledger.controller.dto.request.WalletCreateRequest;
import com.example.walletledger.controller.dto.request.WithdrawRequest;
import com.example.walletledger.controller.dto.response.ApiResponse;
import com.example.walletledger.controller.dto.response.DepositResponse;
import com.example.walletledger.controller.dto.response.TransactionResponse;
import com.example.walletledger.controller.dto.response.TransferResponse;
import com.example.walletledger.controller.dto.response.WalletCreateResponse;
import com.example.walletledger.controller.dto.response.WithdrawResponse;
import com.example.walletledger.domain.transaction.WalletTransaction;
import com.example.walletledger.repository.TransactionRepository;
import com.example.walletledger.service.WalletLedgerService;
import com.example.walletledger.service.dto.CreateWalletCommand;
import com.example.walletledger.service.dto.MoneyCommand;
import com.example.walletledger.service.dto.TransferCommand;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.data.domain.Sort;

/**
 * 지갑 명령/조회 API 컨트롤러.
 *
 * 컨트롤러는 HTTP 요청/응답 변환만 담당하고,
 * 핵심 비즈니스 로직은 서비스 계층에 위임한다.
 */
@RestController
public class WalletCommandController {

    private final WalletLedgerService walletLedgerService;
    private final TransactionRepository transactionRepository;

    public WalletCommandController(WalletLedgerService walletLedgerService,
                                   TransactionRepository transactionRepository) {
        this.walletLedgerService = walletLedgerService;
        this.transactionRepository = transactionRepository;
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
    public ApiResponse<DepositResponse> deposit(@PathVariable("id") Long walletId,
                                     @RequestHeader("Idempotency-Key") String idempotencyKey,
                                     @Valid @RequestBody DepositRequest request) {
        WalletTransaction transaction = walletLedgerService.deposit(
            new MoneyCommand(walletId, request.amount(), request.description(), idempotencyKey)
        );
        return ApiResponse.success(DepositResponse.from(transaction));
    }

    /**
     * 지갑 출금 요청을 처리한다.
     *
     * 컨트롤러는 유효성 검증 및 데이터 전달만 수행하고,
     * 잔액 검증/동시성 제어는 서비스 계층에 위임한다.
     */
    @PostMapping("/wallets/{id}/withdraw")
    public ApiResponse<WithdrawResponse> withdraw(@PathVariable("id") Long walletId,
                                      @RequestHeader("Idempotency-Key") String idempotencyKey,
                                      @Valid @RequestBody WithdrawRequest request) {
        WalletTransaction transaction = walletLedgerService.withdraw(
            new MoneyCommand(walletId, request.amount(), request.description(), idempotencyKey)
        );
        return ApiResponse.success(WithdrawResponse.from(transaction));
    }

    /**
     * 지갑 간 이체 요청을 처리한다.
     *
     * 데드락 방지 락 순서 및 원장 정합성 보장은 서비스 트랜잭션에서 처리된다.
     */
    @PostMapping("/transfer")
    public ApiResponse<TransferResponse> transfer(@RequestHeader("Idempotency-Key") String idempotencyKey,
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
        return ApiResponse.success(TransferResponse.from(transaction));
    }

    /**
     * 거래 목록 조회를 처리한다.
     *
     * 비즈니스 규칙 변경 없이 저장된 거래를 최신순으로 조회해
     * 공통 응답 구조로 반환한다.
     */
    @GetMapping("/transactions")
    public ApiResponse<List<TransactionResponse>> getTransactions() {
        List<TransactionResponse> transactions = transactionRepository.findAll(Sort.by(Sort.Direction.DESC, "id"))
            .stream()
            .map(TransactionResponse::from)
            .toList();
        return ApiResponse.success(transactions);
    }
}
