# Wallet Ledger System

지갑 기반 거래 백엔드 — 동시성 제어, 멱등성 처리, 이중 원장 설계를 다루는 포트폴리오 프로젝트

---

**기술 포인트 요약**

- 비관적 락(`SELECT FOR UPDATE`)으로 동시 출금 시 잔액 정합성 보장
- `Math.min/max` 락 순서 고정으로 이체 데드락 원천 차단
- Soft Check + DB UNIQUE 제약 조합으로 순차/동시 재시도 멱등성 처리
- `transactions` / `ledger_entries` 분리로 불변 감사 원장 구현
- `CyclicBarrier` 기반 동시성 통합 테스트로 실제 경쟁 상태 검증

---

## 프로젝트 소개

금융·SI 백엔드에서 실제로 마주치는 문제들을 직접 다루기 위해 만들었습니다.

- **동시성**: 같은 지갑에 출금 요청이 동시에 도달하면 잔액 검증과 차감 사이에 Race Condition이 발생합니다.
- **멱등성**: 네트워크 재시도로 동일 요청이 두 번 처리되면 잔액이 틀어집니다.
- **감사 추적**: 잔액 필드만 수정하는 방식은 이력이 남지 않아 이상 탐지나 거래 검증이 불가능합니다.

기능 구현보다 각 문제에 대한 설계 결정과 그 이유에 집중한 프로젝트입니다.

---

## 설계 및 테스트 문서

이 프로젝트의 상세 설계와 테스트 전략은 아래 문서를 참고할 수 있습니다.

- [System Architecture](docs/architecture.md)
- [ERD](docs/erd.md)
- [API Specification](docs/api-spec.md)
- [Idempotency Test Scenario](docs/test-scenario-idempotency.md)
- [Concurrency Test Scenario](docs/test-scenario-concurrency.md)

---

## 기술 스택

| 분류 | 기술 |
|------|------|
| Language | Java 17 |
| Framework | Spring Boot 3, Spring Data JPA |
| Database | PostgreSQL 15 |
| Migration | Flyway |
| Test | JUnit 5, Testcontainers |
| Build | Gradle |

---

## 시스템 아키텍처

```
[ Client ]
    |
    v
[ Controller ]      입력 검증, Idempotency-Key 헤더 수신
    |
    v
[ Service ]         트랜잭션 경계, 락 획득 순서 결정, 멱등성 판단
    |
    +---> [ Domain ]        Wallet(잔액 변경), WalletTransaction(상태 전이)
    |
    +---> [ Repository ]
              |
              +-- wallets         (PESSIMISTIC_WRITE 락)
              +-- transactions    (UNIQUE: idempotency_key)
              +-- ledger_entries  (불변 원장 레코드)
              |
              v
        [ PostgreSQL ]
```

- **Controller**: 요청 유효성 검증, Idempotency-Key 헤더 추출
- **Service**: 트랜잭션 단위 조정, 락 획득, 멱등성 판단, 원장 저장 조율
- **Domain**: 잔액 증감·검증 로직, 거래 상태 전이 (PENDING → COMPLETED)
- **Repository**: JPA 영속성, 비관적 락 쿼리 분리

---

## 핵심 설계 결정

### 1. 비관적 락을 선택한 이유

낙관적 락은 충돌 시 예외를 던지고 재시도를 상위 레이어에 위임합니다. 잔액 차감처럼 충돌 빈도가 높은 연산에서는 재시도 로직이 복잡해지고, 실패한 거래의 원장 처리도 어렵습니다.

비관적 락은 `SELECT FOR UPDATE`로 행을 점유해 직렬 처리합니다. 충돌 자체가 발생하지 않으므로 트랜잭션 내에서 잔액 검증·차감·원장 저장이 원자적으로 완료됩니다.

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select w from Wallet w where w.id = :walletId")
Optional<Wallet> findByIdForUpdate(@Param("walletId") Long walletId);
```

### 2. 이체 데드락 방지

A→B 이체와 B→A 이체가 동시에 들어오면 각각 상대방의 락을 기다리며 교착 상태가 됩니다. 지갑 ID를 항상 오름차순으로 잠그도록 강제하면 두 요청이 같은 순서로 락을 요청하므로 데드락이 발생하지 않습니다.

```java
Long firstLockId  = Math.min(command.fromWalletId(), command.toWalletId());
Long secondLockId = Math.max(command.fromWalletId(), command.toWalletId());

walletRepository.findByIdForUpdate(firstLockId).orElseThrow(...);
walletRepository.findByIdForUpdate(secondLockId).orElseThrow(...);
```

### 3. 멱등성: 2단 방어

단순 조회 후 삽입(Check-Then-Act)은 두 요청이 동시에 "키 없음"을 확인하고 각각 삽입을 시도하는 TOCTOU 경쟁 상태가 생깁니다.

| 단계 | 처리 방식 |
|------|-----------|
| 1단계 (Soft Check) | 트랜잭션 시작 시 완료된 거래를 먼저 조회. 존재하면 원본 결과 반환 |
| 2단계 (UNIQUE Catch) | 삽입 시 `idempotency_key UNIQUE` 충돌을 `DataIntegrityViolationException`으로 잡아 기존 거래 반환 |

순차 재시도(1단계에서 처리)와 동시 재시도(2단계에서 처리) 두 경우를 모두 커버합니다.

### 4. 거래와 원장 분리

| 테이블 | 역할 |
|--------|------|
| `transactions` | 멱등성 키 기준 중복 방지, 거래 상태(PENDING / COMPLETED) 추적 |
| `ledger_entries` | 잔액 변화마다 `type(CREDIT/DEBIT)`, `amount`, `balance_after`를 불변 레코드로 기록 |

잔액 필드만 수정하는 방식과 달리, 원장 레코드만으로 임의 시점의 잔액을 재계산할 수 있습니다. 이는 이중 기입 원장(Double-Entry Ledger)의 단순화된 형태입니다.

### 5. `LocalDateTime` 대신 `Instant`

`LocalDateTime`은 타임존 정보가 없어 서버 환경에 따라 같은 시각이 다르게 해석될 수 있습니다. `Instant`는 UTC 기반 절대 시각으로, 환경이 달라져도 기록 시각의 의미가 변하지 않습니다.

---

## 테스트 전략

실제 PostgreSQL 컨테이너(Testcontainers)를 사용해 H2 메모리 DB와의 동작 차이를 방지합니다.

### 멱등성 통합 테스트

동일 멱등 키로 입금을 2회 순차 호출한 뒤 DB 수준에서 검증합니다.

```
검증 항목
  - 두 번째 응답의 거래 ID == 첫 번째 거래 ID
  - transactions 테이블에 해당 키 레코드 1건만 존재
  - 최종 잔액 5,000원 (중복 반영 없음)
```

### 동시성 테스트

잔액 10,000원 지갑에 1,000원 출금 요청 20건을 `CyclicBarrier`로 동시에 출발시켜 실제 경쟁 상태를 재현합니다.

```
검증 항목
  - 최종 잔액 0원 (정확히 10건 성공, 10건 실패)
  - 잔액 음수 없음
  - 예상 외 예외 없음
  - DB COMPLETED 거래 수 == 성공 카운트 (원장 누락 없음)
```

비관적 락의 직렬화 보장을 코드 분석이 아닌 실행 결과로 검증합니다.

---

## 실행 방법

### 사전 조건

- Java 17
- Docker (Testcontainers 및 로컬 DB 실행)

### 로컬 DB 실행

```bash
docker run -d \
  --name wallet-postgres \
  -e POSTGRES_DB=wallet_ledger \
  -e POSTGRES_USER=wallet \
  -e POSTGRES_PASSWORD=wallet \
  -p 5432:5432 \
  postgres:15
```

### 애플리케이션 실행

`src/main/resources/application-local.yml`을 생성합니다.

```yaml
spring:
  datasource:
    username: wallet
    password: wallet
```

```bash
./gradlew bootRun
```

### 테스트 실행

Docker가 실행 중이어야 통합 테스트가 동작합니다.

```bash
# 전체 테스트
./gradlew test

# 동시성 / 멱등성 테스트만
./gradlew test --tests "*.WalletLedgerServiceConcurrencyIntegrationTest"
./gradlew test --tests "*.WalletLedgerServiceIdempotencyIntegrationTest"
```

---

## 개선 및 확장 포인트

현재 MVP는 단일 서버·단일 DB 환경을 전제합니다.

| 항목 | 현재 | 확장 방향 |
|------|------|-----------|
| 락 전략 | 비관적 락 | 분산 환경: Redis 기반 분산 락 |
| 멱등성 저장소 | DB UNIQUE 제약 | Redis 캐시 레이어로 DB 부하 감소 |
| 인증/인가 | 없음 | Spring Security + JWT |
| 거래 조회 | 전체 조회 | 지갑별 필터링, 기간 범위 조회 |
| 통화 | KRW 단일 | 다중 통화 + 환율 적용 |
| 모니터링 | 없음 | Actuator + Micrometer |
