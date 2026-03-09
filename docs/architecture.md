# Architecture

## 목차

1. [레이어드 아키텍처](#1-레이어드-아키텍처)
2. [도메인 모델](#2-도메인-모델)
3. [트랜잭션 설계](#3-트랜잭션-설계)
4. [동시성 제어](#4-동시성-제어)
5. [멱등성 처리](#5-멱등성-처리)
6. [원장 설계](#6-원장-설계)
7. [예외 처리](#7-예외-처리)
8. [테스트 전략](#8-테스트-전략)

---

## 1. 레이어드 아키텍처

```
┌────────────────────────────────────────────────┐
│  Controller Layer                              │
│  WalletCommandController                       │
│  - HTTP 요청/응답 변환                          │
│  - 입력 유효성 검증 (@Valid, @Positive)         │
│  - Idempotency-Key 헤더 수신                   │
│  - 비즈니스 로직 없음                           │
└────────────────┬───────────────────────────────┘
                 │ Command/Query 객체로 전달
┌────────────────▼───────────────────────────────┐
│  Service Layer                                 │
│  WalletLedgerServiceImpl                       │
│  - @Transactional 경계 정의                    │
│  - 비관적 락 획득 순서 결정                     │
│  - 멱등성 판단 및 처리                          │
│  - 도메인 객체 조율 (Orchestration)             │
└──────┬─────────────────────┬───────────────────┘
       │                     │
┌──────▼──────┐     ┌────────▼──────────────────┐
│  Domain     │     │  Repository Layer          │
│  Wallet     │     │  WalletRepository          │
│  WalletTx   │     │  TransactionRepository     │
│  LedgerEntry│     │  LedgerEntryRepository     │
│             │     │  MemberRepository          │
│  - 잔액 변경│     │  - JPA 영속성              │
│  - 상태 전이│     │  - 비관적 락 쿼리 분리     │
└─────────────┘     └──────────────┬─────────────┘
                                   │
                    ┌──────────────▼─────────────┐
                    │  PostgreSQL                 │
                    │  - wallets                  │
                    │  - transactions             │
                    │  - ledger_entries           │
                    │  - members                  │
                    └────────────────────────────┘
```

### 레이어별 책임 경계

**Controller**: HTTP 관심사만 처리한다. 비즈니스 예외를 직접 처리하지 않고 `GlobalExceptionHandler`가 일괄 변환한다.

**Service**: 트랜잭션 단위 조정자(Orchestrator) 역할이다. 도메인 객체에 작업을 위임하고 저장소 저장 순서를 조율한다. 도메인 규칙을 직접 구현하지 않는다.

**Domain**: 잔액 증감, 상태 전이 등 핵심 비즈니스 규칙을 담당한다. 프레임워크 의존성이 없어 단위 테스트가 쉽다.

**Repository**: 영속성 기술 선택(JPA)과 락 쿼리를 캡슐화한다. `findByIdForUpdate()`를 별도 메서드로 분리해 비관적 락 사용 여부를 호출부에서 명시적으로 선택하게 한다.

### 요청 흐름: 출금 예시

```
POST /wallets/{id}/withdraw
  │
  ▼ Controller
  - @Positive(walletId), @NotBlank(Idempotency-Key), @Valid(body) 검증
  - MoneyCommand 생성 후 서비스 위임
  │
  ▼ Service (@Transactional)
  1. validateIdempotencyKey()
  2. findReplayableTransaction() → 완료된 거래 있으면 즉시 반환
  3. walletRepository.findByIdForUpdate()  ← SELECT FOR UPDATE
  4. saveStartedTransaction()  ← PENDING 거래 삽입
  5. wallet.withdraw()  ← 도메인 검증 + 잔액 차감
  6. tx.complete()  ← COMPLETED 상태 전이
  7. ledgerEntryRepository.save(LedgerEntry.debit())
  │
  ▼ 응답
  TransactionResponse (id, type, status, amount, completedAt)
```

---

## 2. 도메인 모델

### Wallet

잔액 변경의 주체다. 잔액 검증과 상태 검증을 도메인 내부에서 처리해 서비스 계층이 검증 로직을 알 필요가 없다.

```java
// 잔액 차감 — 검증 후 변경
public void withdraw(BigDecimal amount) {
    validateActive();   // 비활성 지갑 거부
    validateAmount(amount);  // 0 이하 금액 거부
    if (this.balance.compareTo(amount) < 0) {
        throw new WalletBusinessException(ErrorCode.INSUFFICIENT_BALANCE, ...);
    }
    this.balance = this.balance.subtract(amount);
}
```

`@Version`(낙관적 락)은 사용하지 않는다. 비관적 락으로 동시성을 처리하기 때문에 낙관적 락과의 충돌 가능성을 제거한다.

### WalletTransaction

거래 한 건의 생명주기를 관리한다. `PENDING` → `COMPLETED` 단방향 전이만 허용한다.

```
[생성]  start(idempotencyKey, type, amount)  → status: PENDING
[완료]  complete()                            → status: COMPLETED, completedAt: Instant.now()
```

`requestedAt`, `completedAt` 모두 `Instant`로 저장해 서버 타임존에 무관한 절대 시각을 기록한다.

### LedgerEntry

잔액 변화의 불변 기록이다. 생성 후 수정되지 않는다. 정적 팩토리 메서드로 타입을 명시한다.

```java
LedgerEntry.credit(walletId, txId, amount, balanceAfter, description)  // CREDIT
LedgerEntry.debit(walletId, txId, amount, balanceAfter, description)   // DEBIT
```

---

## 3. 트랜잭션 설계

### 단일 트랜잭션 원칙

입금/출금/이체의 모든 단계—락 획득, 거래 생성, 잔액 변경, 원장 저장—는 하나의 `@Transactional` 경계 안에서 완료된다.

```
@Transactional
deposit() {
    findByIdForUpdate()         ← 락 획득
    saveStartedTransaction()    ← PENDING 거래 저장
    wallet.deposit()            ← 잔액 증가
    tx.complete()               ← COMPLETED 전이
    ledgerEntryRepository.save() ← 원장 기록
}
```

이 중 어느 단계에서든 예외가 발생하면 전체가 롤백된다. 거래 레코드가 존재하면 반드시 원장 레코드도 존재하고, 원장 레코드가 존재하면 반드시 잔액이 반영되어 있다.

### 조회는 readOnly

```java
@Transactional(readOnly = true)
public Page<WalletTransaction> getTransactions(Pageable pageable) { ... }
```

불필요한 변경 감지(dirty checking)를 차단해 읽기 성능을 확보한다.

---

## 4. 동시성 제어

### 비관적 락 선택 이유

| 전략 | 충돌 처리 방식 | 원장 롤백 처리 |
|------|--------------|--------------|
| 낙관적 락 | 충돌 시 예외 → 재시도 필요 | PENDING 거래 롤백 후 재시도 처리 복잡 |
| 비관적 락 | 충돌 자체 방지 (직렬 처리) | 재시도 불필요, 원자적 처리 |

잔액 차감은 충돌 빈도가 높고, 충돌 후 원장 정리 로직이 복잡해진다. 단일 서버 MVP 환경에서는 비관적 락이 단순하고 안전하다.

### 구현

```java
// WalletRepository
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select w from Wallet w where w.id = :walletId")
Optional<Wallet> findByIdForUpdate(@Param("walletId") Long walletId);
```

`PESSIMISTIC_WRITE`는 `SELECT ... FOR UPDATE`로 변환된다. 트랜잭션이 커밋되거나 롤백될 때까지 다른 트랜잭션은 해당 행을 수정할 수 없다.

### 이체 데드락 방지

A→B 이체와 B→A 이체가 동시에 실행되면 서로 상대방의 락을 기다리며 교착 상태가 발생한다.

```
Thread 1 (A→B): lock(A) ──→ wait(B)  ← 교착
Thread 2 (B→A): lock(B) ──→ wait(A)  ← 교착
```

지갑 ID의 오름차순으로 항상 같은 순서로 락을 획득하면 두 스레드가 같은 순서로 락을 요청하므로 교착이 발생하지 않는다.

```
Thread 1 (A→B): lock(A) → lock(B)
Thread 2 (B→A): lock(A) → lock(B)  ← 동일 순서, Thread 1 완료 후 진행
```

```java
Long firstLockId  = Math.min(command.fromWalletId(), command.toWalletId());
Long secondLockId = Math.max(command.fromWalletId(), command.toWalletId());

walletRepository.findByIdForUpdate(firstLockId).orElseThrow(...);
walletRepository.findByIdForUpdate(secondLockId).orElseThrow(...);
```

---

## 5. 멱등성 처리

### 문제: 동일 요청의 중복 도달

클라이언트가 타임아웃 후 재시도하면 서버에 동일 요청이 두 번 도달할 수 있다. 단순 처리 시 잔액이 두 번 차감된다.

### 설계: 2단 방어

#### 1단계 — Soft Check (순차 재시도 처리)

트랜잭션 시작 직후 동일 멱등 키의 완료된 거래를 DB에서 조회한다. 있으면 원본 결과를 즉시 반환하고 이후 로직을 건너뛴다.

```java
Optional<WalletTransaction> replay =
    findReplayableTransaction(command.idempotencyKey(), TransactionType.DEPOSIT);
if (replay.isPresent()) {
    return replay.get();  // 추가 처리 없이 원본 반환
}
```

#### 2단계 — UNIQUE Constraint Catch (동시 재시도 처리)

두 요청이 동시에 1단계를 통과(둘 다 "키 없음" 확인)하더라도, 거래 저장 시 `idempotency_key UNIQUE` 제약 위반이 발생한다. 이를 잡아 기존 완료 거래를 반환한다.

```java
try {
    return transactionRepository.save(WalletTransaction.start(key, type, amount));
} catch (DataIntegrityViolationException ex) {
    return transactionRepository.findByIdempotencyKey(key)
        .filter(tx -> tx.getStatus() == COMPLETED && tx.getType() == type)
        .orElseThrow(() -> new WalletBusinessException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT, ...));
}
```

### 처리 흐름 요약

```
요청 도달
  │
  ▼
findReplayableTransaction()
  ├─ 완료된 거래 있음 → 즉시 반환 (1단계, 순차 재시도)
  └─ 없음 ──────────────────────────────────────────┐
                                                    ▼
                                        saveStartedTransaction()
                                          ├─ 성공 → PENDING 거래 생성
                                          └─ UNIQUE 충돌 → 기존 완료 거래 반환 (2단계, 동시 재시도)
```

### 멱등 키 충돌 케이스 구분

| 상황 | 처리 |
|------|------|
| 완료된 동일 타입 거래 존재 | 원본 거래 결과 반환 (멱등 처리) |
| PENDING 상태 거래 존재 | `IDEMPOTENCY_KEY_CONFLICT` 예외 (처리 중) |
| 다른 타입의 완료 거래 존재 | `IDEMPOTENCY_KEY_CONFLICT` 예외 (타입 불일치) |

### REQUIRES_NEW와 PENDING 잔존 트레이드오프
멱등 키 UNIQUE 충돌 시 현재 트랜잭션/세션 오염을 피하기 위해 시작 거래(PENDING) insert를 REQUIRES_NEW 경계에서 처리한다.
이 구조에서는 외부 트랜잭션이 이후 단계에서 롤백되더라도 REQUIRES_NEW로 커밋된 PENDING 행이 DB에 남을 수 있다.
하지만 재시도(replay) 판단은 COMPLETED 상태만 대상으로 하므로, PENDING 잔존은 중복 성공 응답을 만들지 않으며 정합성을 깨지 않는다.
운영 환경에서는 오래된 PENDING 정리 배치, 상태 기준 TTL 정책, 모니터링 알림으로 잔존 레코드를 관리할 수 있다.
---

## 6. 원장 설계

### 두 테이블의 역할 분리

```
transactions                    ledger_entries
────────────────────────        ──────────────────────────────
id                              id
idempotency_key (UNIQUE)        wallet_id
type (DEPOSIT/WITHDRAW/TRANSFER) transaction_id
status (PENDING/COMPLETED)      type (CREDIT/DEBIT)
amount                          amount
requested_at                    balance_after
completed_at                    description
                                created_at
```

`transactions`는 "거래가 일어났다"는 사실과 그 상태를 관리한다.
`ledger_entries`는 "잔액이 어떻게 변했다"는 사실을 불변 레코드로 기록한다.

### balance_after 설계

각 원장 레코드에 `balance_after`를 기록하는 이유:

- 잔액 필드(`wallets.balance`)와 독립적으로 임의 시점의 잔액을 재계산할 수 있다.
- 잔액 필드 값의 정합성을 원장 레코드와 교차 검증할 수 있다.
- 원장이 증거 역할을 하므로 잔액 필드가 오염되더라도 복구 기반이 된다.

### 이체의 원장 기록

이체 한 건은 하나의 `WalletTransaction`과 두 개의 `LedgerEntry`로 기록된다.

```
WalletTransaction (id=99, type=TRANSFER)
  ├── LedgerEntry (wallet=A, type=DEBIT,  amount=5000, balance_after=5000)
  └── LedgerEntry (wallet=B, type=CREDIT, amount=5000, balance_after=15000)
```

두 원장 레코드는 같은 트랜잭션에서 저장되므로 한쪽만 기록되는 상황이 발생하지 않는다.

### DB 제약으로 불변식 강제

```sql
CONSTRAINT chk_wallet_balance_non_negative CHECK (balance >= 0)
CONSTRAINT chk_ledger_amount_positive      CHECK (amount > 0)
```

애플리케이션 코드 버그로 제약을 우회하려 해도 DB 수준에서 거부된다.

---

## 7. 예외 처리

### WalletBusinessException + ErrorCode

비즈니스 예외는 `WalletBusinessException` 단일 타입으로 통일한다. HTTP 상태 코드는 `ErrorCode` enum이 보유한다.

```java
public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, ...),
    INSUFFICIENT_BALANCE(HttpStatus.BAD_REQUEST, ...),
    IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT, ...),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, ...),
    ...
}
```

서비스 계층은 HTTP 상태 코드를 알 필요가 없다. `ErrorCode`만 던지면 `GlobalExceptionHandler`가 상태 코드로 변환한다.

### GlobalExceptionHandler 처리 범위

| 예외 타입 | HTTP | 발생 원인 |
|-----------|------|-----------|
| `WalletBusinessException` | `ErrorCode`에 따라 | 비즈니스 규칙 위반 |
| `MethodArgumentNotValidException` | 400 | `@Valid` 검증 실패 |
| `ConstraintViolationException` | 400 | `@Positive`, `@NotBlank` 등 위반 |
| `MissingRequestHeaderException` | 400 | 필수 헤더 누락 |
| `HttpMessageNotReadableException` | 400 | JSON 파싱 실패 |
| `Exception` | 500 | 처리되지 않은 예외 (스택 트레이스 로깅) |

500 응답에서는 내부 구현 정보를 응답 본문에 포함하지 않는다. 스택 트레이스는 서버 로그에만 기록한다.

### 공통 응답 구조

```json
// 성공
{ "success": true, "data": { ... } }

// 실패
{ "success": false, "error": { "code": "INSUFFICIENT_BALANCE", "message": "잔액이 부족합니다." } }
```

모든 API가 동일한 응답 구조를 유지하므로 클라이언트가 성공/실패를 일관된 방식으로 판별할 수 있다.

---

## 8. MVP 알려진 한계

### 트랜잭션 조회 스코프 미완성

#### 현재 상태

`WalletTransaction` 엔티티와 `transactions` 테이블에 `wallet_id` 컬럼이 없다.

```
transactions
────────────────────────
id
idempotency_key (UNIQUE)
type
status
amount
requested_at
completed_at
```

`GET /transactions` API는 `findAll(pageable)`로 전체 거래를 반환하며, 특정 지갑의 거래 내역을 직접 조회할 수 없다.

#### 이 상태로 MVP를 출시한 이유

이 프로젝트의 핵심 검증 목표는 세 가지였다.

1. 비관적 락으로 동시 출금 시 잔액 정합성 보장
2. 2단 방어(Soft Check + UNIQUE Constraint)로 멱등성 처리
3. `transactions` / `ledger_entries` 분리로 불변 감사 원장 구현

지갑 기준 거래 조회는 위 세 목표와 직교(orthogonal)하는 기능이다. 스키마를 단순하게 유지해 핵심 설계 결정에 집중하기 위해 MVP 범위에서 제외했다.

잔액 변화 이력은 `ledger_entries.wallet_id`를 통해 지갑별로 조회 가능하므로(`GET /wallets/{walletId}/ledger`), 감사 추적 기능 자체에는 공백이 없다.

#### 개선 방향

**스키마 변경**

```sql
-- transactions 테이블에 wallet_id 추가
ALTER TABLE transactions ADD COLUMN wallet_id BIGINT REFERENCES wallets(id);

-- 이체는 두 지갑을 참조하므로 별도 컬럼 분리
ALTER TABLE transactions ADD COLUMN from_wallet_id BIGINT REFERENCES wallets(id);
ALTER TABLE transactions ADD COLUMN to_wallet_id   BIGINT REFERENCES wallets(id);
```

단순 입출금은 `wallet_id`로, 이체는 `from_wallet_id` / `to_wallet_id`로 참조하고 `wallet_id`는 NULL로 두는 방식이 적절하다. 또는 거래 타입별로 테이블을 분리하는 방향도 고려할 수 있다.

**쿼리 변경**

```java
// TransactionRepository
Page<WalletTransaction> findByWalletId(Long walletId, Pageable pageable);

// 이체의 경우 — 해당 지갑이 송신 또는 수신 측인 거래 모두 포함
@Query("SELECT t FROM WalletTransaction t " +
       "WHERE t.fromWalletId = :walletId OR t.toWalletId = :walletId")
Page<WalletTransaction> findByParticipatingWallet(Long walletId, Pageable pageable);
```

**API 변경**

현재 `GET /transactions` (전체 조회) 대신, 지갑 컨텍스트를 필수로 요구하는 엔드포인트로 전환한다.

```
GET /wallets/{walletId}/transactions?page=0&size=20
```

---

## 9. 테스트 전략

### 테스트 환경

H2 인메모리 DB 대신 Testcontainers + PostgreSQL 15 컨테이너를 사용한다. 이유:

- PostgreSQL의 `SELECT FOR UPDATE` 동작을 실제 DB에서 검증한다.
- `UNIQUE` 제약 충돌(`DataIntegrityViolationException`) 동작이 H2와 다를 수 있다.
- Flyway 마이그레이션 실행 경로를 통합 테스트에서 검증한다.

```java
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class WalletLedgerServiceConcurrencyIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        ...
    }
}
```

### 멱등성 통합 테스트

**목적**: 순차 재시도(1단계 방어)가 정상 동작하는지 검증한다.

```
사전 조건: 회원/지갑 준비, DB에 해당 멱등 키 없음
행동:      동일 멱등 키로 deposit() 2회 순차 호출
검증:
  - second.getId() == first.getId()          // 동일 거래 반환
  - second.getCompletedAt() == first.getCompletedAt()  // 원본 시각
  - COUNT(transactions WHERE idempotency_key = ?) == 1  // DB 1건
  - COUNT(ledger_entries WHERE transaction_id = ?) == 1  // 원장 1건
  - wallet.balance == 5000                    // 중복 반영 없음
```

### 동시성 통합 테스트

**목적**: 비관적 락이 실제 경쟁 상태에서 잔액 정합성을 보장하는지 검증한다.

```
사전 조건: 잔액 10,000원 지갑, 각 스레드가 고유 멱등 키 사용
행동:      CyclicBarrier(20)로 출금 스레드 20개를 동시에 출발
검증:
  - finalBalance == 0
  - finalBalance >= 0 (절대 음수 없음)
  - successCount == 10, failCount == 10
  - unexpectedErrors.isEmpty()
  - COMPLETED 거래 수 == successCount + 1 (초기 입금 포함)
  - DEBIT 원장 수 == successCount
```

`CyclicBarrier`는 모든 스레드가 `await()`에 도달할 때까지 대기한 뒤 동시에 출발시킨다. 이를 통해 실제 DB 락 경쟁 상황을 재현한다.

### 테스트 격리

멀티스레드 테스트는 트랜잭션 롤백으로 정리할 수 없다. 각 스레드의 트랜잭션이 독립적으로 커밋되기 때문이다. `@AfterEach`에서 FK 참조 순서에 맞춰 직접 삭제한다.

```java
@AfterEach
void cleanUp() {
    ledgerEntryRepository.deleteAllInBatch();    // 1. 원장 (transactions 참조)
    transactionRepository.deleteAllInBatch();    // 2. 거래 (wallets 참조)
    walletRepository.deleteAllInBatch();         // 3. 지갑 (members 참조)
    memberRepository.deleteAllInBatch();         // 4. 회원
}
```
