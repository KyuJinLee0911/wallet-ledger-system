# Wallet Ledger System - 프로젝트 기획 문서

> Java/Spring 기반 백엔드 개발자 포트폴리오 프로젝트
> 대상: 한국 SI 기업 / 은행·금융권 IT 직무

---

## 1. 프로젝트 개요

### 시스템 설명

Wallet Ledger System은 사용자별 전자 지갑(Wallet)을 관리하고, 포인트 적립·차감·이체 등의 거래(Transaction)를 이중 원장(Double-Entry Ledger) 방식으로 기록·관리하는 백엔드 시스템이다.

모든 잔액 변동은 불변(immutable) 원장 이벤트로 영구 보존되며, 현재 잔액은 원장 합산을 통해 도출된다. 이는 전통적인 은행 계정원장(Account Ledger) 설계 방식과 동일하다.

### 어떤 문제를 해결하는 시스템인가

| 문제 | 해결 방식 |
|------|-----------|
| 잔액 변경 시 Race Condition 발생 | 비관적 락 / 분산 락으로 동시성 제어 |
| 중복 결제·중복 요청 처리 | Idempotency Key 기반 중복 방지 |
| 잔액 조작·위변조 불투명성 | 불변 원장 + 감사 로그(Audit Log) 영구 보존 |
| 분산 환경에서의 데이터 정합성 | 트랜잭션 경계 명확화 + 보상 트랜잭션 설계 |
| 배치 정산 처리 누락 | Spring Batch 기반 일별 정산 파이프라인 |

---

## 2. 비즈니스 문제 정의

### 현실적인 사용 시나리오

1. **포인트 적립/소멸**: 쇼핑몰에서 구매 시 포인트 적립, 유효기간 만료 시 자동 소멸
2. **포인트 결제**: 보유 포인트를 상품 구매 대금으로 차감 처리
3. **지갑 간 이체**: A 사용자 지갑에서 B 사용자 지갑으로 잔액 이동
4. **환불 처리**: 결제 취소 시 차감된 포인트 원상복구 (보상 트랜잭션)
5. **일별 정산**: 당일 전체 거래 집계 및 이상 거래 탐지

### 금융/결제 시스템에서 왜 필요한가

금융 시스템의 핵심 요건은 **ACID 보장**과 **감사 가능성(Auditability)**이다.

- 단순 UPDATE로 잔액을 수정하면 이력이 사라지고, 장애 발생 시 복구 불가
- 동시에 동일 지갑에 요청이 들어오면 잔액이 음수로 떨어지는 Race Condition 발생 가능
- 은행·금융 감독 규정상 모든 자금 이동은 추적 가능한 원장 기록이 법적으로 요구됨
- 네트워크 재시도(Retry)로 인한 중복 처리는 실제 금융 사고로 이어짐

따라서 이 시스템은 이러한 실무 금융 도메인의 핵심 문제를 직접 해결하는 구조로 설계한다.

---

## 3. 핵심 기능

### MVP 기능 (Phase 1~2에서 구현)

| 기능 | 설명 |
|------|------|
| 지갑 생성/조회 | 사용자별 지갑 개설, 잔액 조회 |
| 포인트 입금 | 외부 시스템으로부터 포인트 적립 |
| 포인트 출금 | 잔액 범위 내 포인트 차감 |
| 지갑 간 이체 | 원자적 송금/수금 처리 |
| 거래 내역 조회 | 페이지네이션 기반 원장 이력 조회 |
| Idempotency 처리 | 동일 요청 키로 중복 요청 시 멱등 응답 |
| 감사 로그 | 모든 잔액 변동에 대한 변경 이력 기록 |
| 예외 처리 | 잔액 부족, 존재하지 않는 지갑 등 도메인 예외 |

### 확장 기능 (Phase 3 고도화)

| 기능 | 설명 |
|------|------|
| 포인트 유효기간 관리 | 만료 예정 포인트 사전 알림 + 자동 소멸 배치 |
| 일별 정산 배치 | Spring Batch로 일 마감 집계 리포트 생성 |
| 이상 거래 탐지 | 단시간 대량 출금 패턴 감지 후 플래그 처리 |
| 환불/보상 트랜잭션 | SAGA 패턴 기반 실패 시 보상 처리 |
| 지갑 동결/해제 | 관리자 기능으로 특정 지갑 거래 차단 |
| 다중 통화 지원 | KRW / USD 포인트 별도 관리 (환율 연동 불포함) |

---

## 4. 도메인 모델 설계

### Member (회원)

- **역할**: 지갑 소유 주체. 인증/인가의 기준 단위
- **주요 필드**: `id`, `username`, `email`, `status(ACTIVE/SUSPENDED)`, `createdAt`
- **관계**: 1:N → Wallet

### Wallet (지갑)

- **역할**: 사용자의 잔액 보유 단위. 잔액의 현재 상태를 캐싱하는 뷰 역할
- **주요 필드**: `id`, `memberId`, `balance`, `currency`, `status(ACTIVE/FROZEN)`, `version`
- **관계**: N:1 → Member, 1:N → LedgerEntry
- **설계 이유**: `balance`는 성능을 위한 캐시 값이며, 실제 정합성은 LedgerEntry 합산이 기준이다. `version` 필드로 낙관적 락을 지원한다.

### LedgerEntry (원장 항목)

- **역할**: 모든 잔액 변동의 불변 기록. 삭제·수정 불가
- **주요 필드**: `id`, `walletId`, `transactionId`, `type(CREDIT/DEBIT)`, `amount`, `balanceAfter`, `description`, `createdAt`
- **관계**: N:1 → Wallet, N:1 → Transaction
- **설계 이유**: Double-Entry 원칙에 따라 이체 시 출금 측 DEBIT + 입금 측 CREDIT 두 건이 동시 생성된다.

### Transaction (거래)

- **역할**: 하나의 비즈니스 이벤트(이체, 결제, 환불 등)를 묶는 논리 단위
- **주요 필드**: `id`, `idempotencyKey`, `type(TRANSFER/DEPOSIT/WITHDRAW/REFUND)`, `status(PENDING/COMPLETED/FAILED)`, `referenceId`, `requestedAt`, `completedAt`
- **관계**: 1:N → LedgerEntry
- **설계 이유**: `idempotencyKey`에 UNIQUE 제약을 걸어 중복 요청을 DB 레벨에서 차단한다.

### AuditLog (감사 로그)

- **역할**: 누가, 언제, 어떤 엔티티를, 어떻게 변경했는지 추적
- **주요 필드**: `id`, `entityType`, `entityId`, `action(CREATE/UPDATE/DELETE)`, `beforeValue(JSON)`, `afterValue(JSON)`, `actorId`, `createdAt`
- **관계**: 독립 테이블 (어떤 엔티티와도 FK 없이 논리적 참조만)
- **설계 이유**: FK 제약을 두지 않아 엔티티 삭제 후에도 감사 기록이 남는다.

### IdempotencyRecord (멱등성 레코드)

- **역할**: 처리 완료된 요청 키와 응답을 저장하여 중복 요청 시 동일 응답 반환
- **주요 필드**: `idempotencyKey`, `responseStatus`, `responseBody(JSON)`, `expiredAt`
- **설계 이유**: Redis에 TTL과 함께 저장하여 일정 기간 후 자동 만료. DB 부하 최소화.

---

## 5. 시스템 아키텍처

### 레이어 구조

```
[ Client / API Gateway ]
         |
[ Controller Layer ]
  - 요청 검증 (Bean Validation)
  - Idempotency Key 헤더 추출
  - DTO 변환
         |
[ Service Layer ]
  - 트랜잭션 경계 관리 (@Transactional)
  - 비즈니스 규칙 적용 (잔액 검증, 상태 검증)
  - 동시성 제어 진입점 (락 획득)
  - 이벤트 발행
         |
[ Domain Layer ]
  - 엔티티 (Wallet, LedgerEntry, Transaction ...)
  - 도메인 예외 (InsufficientBalanceException, WalletFrozenException ...)
  - 값 객체 (Money, Currency ...)
         |
[ Repository Layer ]
  - Spring Data JPA Repository
  - QueryDSL (복잡한 조회 쿼리)
  - @Lock 어노테이션으로 비관적 락 적용
         |
[ Infrastructure Layer ]
  - PostgreSQL (주 데이터 저장소)
  - Redis (분산 락, Idempotency 캐시, 세션)
  - Spring Batch (정산 배치)
  - Spring Events / (선택) Kafka (비동기 알림)
```

### 외부 컴포넌트

| 컴포넌트 | 역할 | 선택 이유 |
|----------|------|-----------|
| PostgreSQL | 트랜잭션 데이터 영구 저장 | ACID 완전 지원, FOR UPDATE 락 지원 |
| Redis | 분산 락(Redisson), 멱등성 캐시 | TTL 자동 만료, 원자적 SETNX 연산 |
| Spring Batch | 일별 정산, 포인트 만료 배치 | 재시작·청크 처리·모니터링 내장 |
| Docker Compose | 로컬 개발 환경 구성 | PostgreSQL + Redis 컨테이너화 |

---

## 6. 동시성 문제 분석

### Race Condition 발생 지점

#### 시나리오: 동시 출금 요청
```
사용자 A 지갑 잔액: 10,000원

Thread 1: 잔액 조회 → 10,000원 확인 → 8,000원 출금 처리 시작
Thread 2: 잔액 조회 → 10,000원 확인 → 8,000원 출금 처리 시작

결과: 잔액 -6,000원 (Lost Update 발생)
```

이 문제는 **출금(WITHDRAW)**, **이체(TRANSFER)** API에서 반드시 발생한다.

### 해결 전략

#### 전략 1: 비관적 락 (Pessimistic Lock) - 메인 전략

```java
// Repository
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT w FROM Wallet w WHERE w.id = :id")
Optional<Wallet> findByIdWithLock(@Param("id") Long id);

// Service
@Transactional
public void withdraw(Long walletId, BigDecimal amount) {
    Wallet wallet = walletRepository.findByIdWithLock(walletId)
        .orElseThrow(WalletNotFoundException::new);
    wallet.withdraw(amount); // 도메인 로직에서 잔액 검증
    ledgerEntryRepository.save(LedgerEntry.debit(wallet, amount));
}
```

- **선택 이유**: 금융 시스템에서는 충돌 발생 시 재시도보다 대기 후 처리가 안전하다. 롤백 비용이 높은 환경에서 비관적 락이 적합하다.
- **단점**: 처리량(Throughput)이 낙관적 락 대비 낮음. 데드락 위험 존재.
- **데드락 방지**: 이체 시 항상 작은 walletId부터 락 획득 (락 순서 일관성 유지)

#### 전략 2: 낙관적 락 (Optimistic Lock) - 저경합 환경 대안

```java
// Entity
@Version
private Long version;

// 충돌 발생 시 OptimisticLockException → 재시도 또는 실패 응답
```

- **선택 이유**: 읽기가 압도적으로 많고 동시 수정이 드문 경우 성능 우위
- **단점**: 충돌 시 재시도 로직 필요, 높은 경합 환경에서 성능 저하

#### 전략 3: 분산 락 (Distributed Lock via Redisson) - 확장 시 도입

```java
RLock lock = redissonClient.getLock("wallet:lock:" + walletId);
try {
    if (lock.tryLock(3, 5, TimeUnit.SECONDS)) {
        // 임계 구역 처리
    }
} finally {
    lock.unlock();
}
```

- **선택 이유**: 다중 서버(Scale-Out) 환경에서 DB 락만으로는 분산 정합성 보장 불가
- **단점**: Redis 장애 시 락 획득 불가 → Circuit Breaker 연동 필요

### 전략 선택 기준 (면접 설명용)

> "MVP 단계에서는 비관적 락을 기본 전략으로 채택했습니다. 금융 도메인은 충돌 발생 시 재시도보다 명시적 대기가 안전하고, 잘못된 처리의 롤백 비용이 크기 때문입니다. 서비스 규모가 커져 다중 서버로 확장될 경우 Redisson 기반 분산 락으로 전환하는 것을 고려합니다."

---

## 7. 트랜잭션 설계

### 트랜잭션이 필요한 서비스 로직

#### 이체(Transfer) - 가장 중요한 트랜잭션 경계

```
BEGIN TRANSACTION
  1. 송신 지갑 락 획득 (PESSIMISTIC_WRITE)
  2. 수신 지갑 락 획득 (PESSIMISTIC_WRITE)
  3. 송신 지갑 잔액 검증
  4. Transaction 레코드 생성 (PENDING)
  5. 송신 지갑 LedgerEntry(DEBIT) 생성
  6. 수신 지갑 LedgerEntry(CREDIT) 생성
  7. 송신 지갑 balance 차감
  8. 수신 지갑 balance 증가
  9. Transaction 상태 COMPLETED 업데이트
COMMIT
```

- **원칙**: 두 지갑 잔액 변경과 원장 기록이 **반드시 원자적**으로 처리되어야 한다. 중간 실패 시 전체 롤백.
- **데드락 방지**: 락 획득 순서를 `MIN(walletId, targetWalletId)` → `MAX(walletId, targetWalletId)` 순으로 고정

#### 입금(Deposit) - 단순 트랜잭션

```
BEGIN TRANSACTION
  1. 지갑 락 획득
  2. Idempotency 키 확인
  3. LedgerEntry(CREDIT) 생성
  4. balance 증가
  5. Transaction 레코드 COMPLETED
COMMIT
```

#### 트랜잭션 전파(Propagation) 설계

| 상황 | 전파 설정 | 이유 |
|------|-----------|------|
| 비즈니스 서비스 메인 로직 | `REQUIRED` (기본값) | 기존 트랜잭션에 참여 |
| 감사 로그 기록 | `REQUIRES_NEW` | 메인 트랜잭션 롤백과 무관하게 감사 로그는 보존 |
| 읽기 전용 조회 | `READONLY = true` | 영속성 컨텍스트 flush 생략, 성능 최적화 |
| Idempotency 체크 | `REQUIRES_NEW` | 중복 확인은 별도 트랜잭션에서 원자적으로 처리 |

---

## 8. API 설계 초안

### 지갑 관리

```
POST   /api/v1/wallets
       지갑 생성 (memberId 기반)

GET    /api/v1/wallets/{walletId}
       지갑 정보 및 현재 잔액 조회

GET    /api/v1/wallets/{walletId}/balance
       실시간 잔액 조회 (원장 합산 방식 - 정합성 우선)

PATCH  /api/v1/wallets/{walletId}/status
       지갑 동결/해제 (관리자 전용)
```

### 거래 처리

```
POST   /api/v1/wallets/{walletId}/deposit
       포인트 입금
       Header: Idempotency-Key (필수)
       Body: { amount, description }

POST   /api/v1/wallets/{walletId}/withdraw
       포인트 출금
       Header: Idempotency-Key (필수)
       Body: { amount, description }

POST   /api/v1/transfers
       지갑 간 이체
       Header: Idempotency-Key (필수)
       Body: { fromWalletId, toWalletId, amount, description }

POST   /api/v1/transactions/{transactionId}/refund
       거래 환불 처리
```

### 조회

```
GET    /api/v1/wallets/{walletId}/ledger
       원장 이력 조회 (페이지네이션)
       Query: page, size, type, from, to

GET    /api/v1/transactions/{transactionId}
       단건 거래 조회

GET    /api/v1/wallets/{walletId}/transactions
       지갑별 거래 목록 조회
```

### 공통 응답 형식

```json
{
  "success": true,
  "data": { ... },
  "error": null,
  "timestamp": "2025-01-01T00:00:00Z"
}
```

### 에러 응답 형식

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "INSUFFICIENT_BALANCE",
    "message": "잔액이 부족합니다.",
    "detail": "요청 금액: 10,000 / 현재 잔액: 5,000"
  },
  "timestamp": "2025-01-01T00:00:00Z"
}
```

---

## 9. DB 설계 방향

### 주요 테이블

#### members

```sql
CREATE TABLE members (
    id          BIGSERIAL PRIMARY KEY,
    username    VARCHAR(50)  NOT NULL UNIQUE,
    email       VARCHAR(100) NOT NULL UNIQUE,
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT now()
);
```

#### wallets

```sql
CREATE TABLE wallets (
    id          BIGSERIAL PRIMARY KEY,
    member_id   BIGINT       NOT NULL REFERENCES members(id),
    balance     NUMERIC(19,4) NOT NULL DEFAULT 0,
    currency    VARCHAR(10)  NOT NULL DEFAULT 'KRW',
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    version     BIGINT       NOT NULL DEFAULT 0,  -- 낙관적 락용
    created_at  TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT chk_balance_non_negative CHECK (balance >= 0)
);
```

- **설계 포인트**: `CHECK (balance >= 0)` 제약으로 DB 레벨에서 음수 잔액 방지 (최후 방어선)

#### transactions

```sql
CREATE TABLE transactions (
    id               BIGSERIAL PRIMARY KEY,
    idempotency_key  VARCHAR(100) NOT NULL UNIQUE,
    type             VARCHAR(30)  NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    reference_id     VARCHAR(100),
    requested_at     TIMESTAMP    NOT NULL DEFAULT now(),
    completed_at     TIMESTAMP
);
```

- **설계 포인트**: `idempotency_key UNIQUE` 제약으로 DB 레벨 중복 방지

#### ledger_entries

```sql
CREATE TABLE ledger_entries (
    id              BIGSERIAL PRIMARY KEY,
    wallet_id       BIGINT        NOT NULL REFERENCES wallets(id),
    transaction_id  BIGINT        NOT NULL REFERENCES transactions(id),
    type            VARCHAR(10)   NOT NULL,  -- CREDIT / DEBIT
    amount          NUMERIC(19,4) NOT NULL,
    balance_after   NUMERIC(19,4) NOT NULL,
    description     VARCHAR(500),
    created_at      TIMESTAMP     NOT NULL DEFAULT now()
);
-- 수정/삭제 금지: 애플리케이션 레벨에서 UPDATE/DELETE 차단
```

#### audit_logs

```sql
CREATE TABLE audit_logs (
    id            BIGSERIAL PRIMARY KEY,
    entity_type   VARCHAR(50)  NOT NULL,
    entity_id     VARCHAR(50)  NOT NULL,
    action        VARCHAR(20)  NOT NULL,
    before_value  JSONB,
    after_value   JSONB,
    actor_id      BIGINT,
    created_at    TIMESTAMP    NOT NULL DEFAULT now()
);
```

### 중요 제약조건

| 제약 | 위치 | 목적 |
|------|------|------|
| `balance >= 0` | wallets | DB 최후 방어선 |
| `idempotency_key UNIQUE` | transactions | 중복 거래 방지 |
| `amount > 0` | ledger_entries | 0원 거래 차단 |
| ledger_entries UPDATE/DELETE 차단 | 애플리케이션 | 원장 불변성 보장 |

### 인덱스 전략

```sql
-- 지갑별 원장 조회 (가장 빈번한 쿼리)
CREATE INDEX idx_ledger_wallet_created ON ledger_entries(wallet_id, created_at DESC);

-- 거래 타입별 조회
CREATE INDEX idx_ledger_wallet_type ON ledger_entries(wallet_id, type);

-- 감사 로그 엔티티별 조회
CREATE INDEX idx_audit_entity ON audit_logs(entity_type, entity_id, created_at DESC);

-- 멤버별 지갑 조회
CREATE INDEX idx_wallet_member ON wallets(member_id);

-- Idempotency Key 조회 (이미 UNIQUE로 인덱스 자동 생성)
```

---

## 10. 개발 단계 계획

### Phase 1 - 기반 구축 (1~2주)

**목표**: 기본 동작하는 CRUD + 트랜잭션 골격 완성

- [ ] 프로젝트 세팅 (Spring Boot + JPA + PostgreSQL + Docker Compose)
- [ ] 도메인 엔티티 구현 (Member, Wallet, Transaction, LedgerEntry)
- [ ] 기본 Repository 구현
- [ ] 지갑 생성 / 잔액 조회 API
- [ ] 입금(Deposit) API + 단순 트랜잭션 처리
- [ ] 글로벌 예외 처리기 (GlobalExceptionHandler)
- [ ] 단위 테스트 작성 (도메인 로직)

**완료 기준**: 지갑 생성 후 입금 → 잔액 조회가 정상 동작

### Phase 2 - 핵심 비즈니스 로직 (2~3주)

**목표**: 동시성 제어 + 멱등성 + 이체 + 감사 로그 완성

- [ ] 출금(Withdraw) API + 비관적 락 적용
- [ ] 이체(Transfer) API + 데드락 방지 락 순서 구현
- [ ] Idempotency Key 처리 (Redis 기반)
- [ ] AuditLog 구현 (JPA @EntityListeners 또는 Spring AOP)
- [ ] 동시성 테스트 (CountDownLatch 기반)
- [ ] 통합 테스트 (Testcontainers + PostgreSQL)
- [ ] 환불(Refund) API

**완료 기준**: 100개 동시 출금 요청 시 잔액 정합성 보장 확인

### Phase 3 - 고도화 및 배치 (2주)

**목표**: 배치 처리 + 조회 최적화 + 문서화

- [ ] Spring Batch 일별 정산 배치 구현
- [ ] 포인트 만료 처리 배치
- [ ] 원장 조회 페이지네이션 최적화 (Cursor-Based)
- [ ] Swagger/OpenAPI 문서 자동화
- [ ] README 작성 (아키텍처 다이어그램 포함)
- [ ] Docker Compose 전체 구성 완성
- [ ] 부하 테스트 결과 문서화

**완료 기준**: README만 읽어도 시스템 전체 이해 가능한 수준

---

## 11. 테스트 전략

### 단위 테스트 (Unit Test)

**대상**: 도메인 로직, 서비스 비즈니스 규칙

```java
// 예시: 잔액 부족 시 예외 발생 검증
@Test
void 잔액_부족_시_출금_예외() {
    Wallet wallet = Wallet.create(member, new Money(5000, Currency.KRW));
    assertThatThrownBy(() -> wallet.withdraw(new Money(10000, Currency.KRW)))
        .isInstanceOf(InsufficientBalanceException.class);
}
```

- Repository는 Mockito로 Mocking
- 도메인 메서드의 경계값(0원, 최대값, 음수) 집중 테스트
- 목표 커버리지: 도메인/서비스 레이어 80% 이상

### 통합 테스트 (Integration Test)

**대상**: 실제 DB 연동 + 트랜잭션 동작 검증

```java
// Testcontainers로 PostgreSQL 실제 컨테이너 실행
@Testcontainers
@SpringBootTest
class WalletIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");

    @Test
    void 이체_후_양측_잔액_정합성_검증() { ... }

    @Test
    void 이체_실패_시_전체_롤백_검증() { ... }
}
```

- 실제 트랜잭션 롤백 동작 확인
- Idempotency Key 중복 처리 시나리오 검증
- 감사 로그 자동 생성 여부 확인

### 동시성 테스트 (Concurrency Test)

**대상**: 비관적 락 / 분산 락의 Race Condition 방지 검증

```java
@Test
void 동시_100건_출금_시_잔액_정합성() throws InterruptedException {
    // 잔액 100,000원 지갑에 1,000원씩 100개 동시 출금 시도
    // 기대 결과: 정확히 100건 성공 또는 잔액 부족으로 일부 실패
    //            최종 잔액은 반드시 0 이상

    int threadCount = 100;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch latch = new CountDownLatch(threadCount);

    for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
            try { walletService.withdraw(walletId, BigDecimal.valueOf(1000)); }
            catch (Exception ignored) {}
            finally { latch.countDown(); }
        });
    }

    latch.await();
    Wallet result = walletRepository.findById(walletId).orElseThrow();
    assertThat(result.getBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
}
```

- 정상 락 동작 시: 최종 잔액 0원 (100건 모두 성공)
- 잔액 부족 처리 시: 일부 실패 + 잔액 >= 0 보장

---

## 12. 면접에서 설명할 포인트

### 기술적 역량 어필 포인트

#### 1. 트랜잭션 정합성 설계

> "단순 balance UPDATE가 아닌 이중 원장(Double-Entry Ledger) 방식을 채택했습니다. 모든 잔액 변동은 불변 레코드로 기록되며, 현재 잔액은 원장 합산으로 검증 가능합니다. 실제 은행 계정원장과 동일한 설계입니다."

#### 2. 동시성 제어 선택 근거

> "비관적 락을 선택한 이유는 금융 도메인 특성 때문입니다. 낙관적 락은 충돌 시 재시도가 필요한데, 금융 거래에서 재시도 로직은 멱등성 처리가 더 복잡해집니다. 비관적 락은 대기 후 순차 처리를 보장하므로 더 안전합니다. 이체 시 데드락을 막기 위해 walletId 오름차순으로 락 획득 순서를 고정했습니다."

#### 3. Idempotency 설계

> "네트워크 타임아웃으로 클라이언트가 재시도할 때 중복 처리를 막기 위해 Idempotency Key 패턴을 적용했습니다. Redis에 키와 응답을 TTL과 함께 저장하고, DB의 UNIQUE 제약을 최후 방어선으로 사용하는 이중 구조로 설계했습니다."

#### 4. 감사 로그 설계

> "금융 감독 규정상 모든 자금 이동은 추적 가능해야 합니다. 감사 로그는 `REQUIRES_NEW` 트랜잭션 전파로 메인 트랜잭션 롤백과 독립적으로 보존됩니다. FK 없이 논리적 참조만 사용해 엔티티 삭제 후에도 감사 기록이 남도록 설계했습니다."

#### 5. 테스트 전략

> "CountDownLatch로 실제 Race Condition을 재현하는 동시성 테스트를 작성했습니다. Testcontainers로 실제 PostgreSQL 환경에서 통합 테스트를 실행하여 Mocking의 한계를 극복했습니다. 단위 테스트는 도메인 로직의 경계값을 집중적으로 검증합니다."

#### 6. 배치 처리

> "Spring Batch의 청크 기반 처리로 대량 포인트 만료 처리를 구현했습니다. Step 단위 재시작을 지원하여 배치 실패 시 처음부터 재실행할 필요 없이 실패한 청크부터 재개됩니다."

### 예상 면접 질문 및 답변 준비

| 질문 | 핵심 답변 키워드 |
|------|-----------------|
| 동시성을 어떻게 처리했나요? | 비관적 락, 락 순서, 데드락 방지 |
| 중복 결제를 어떻게 막나요? | Idempotency Key, Redis TTL, DB UNIQUE |
| 트랜잭션 롤백 시 로그는 어떻게 되나요? | REQUIRES_NEW, 감사 로그 독립 보존 |
| 잔액 정합성을 어떻게 보장하나요? | CHECK 제약, 원장 합산 검증, 락 |
| 확장성은 어떻게 고려했나요? | 분산 락(Redisson) 전환 가능 구조 |
| 테스트를 어떻게 작성했나요? | 동시성 테스트, Testcontainers, 경계값 |

---

*문서 최종 수정: 2026-03-06*
*작성자: Planner Agent (Claude Code)*
