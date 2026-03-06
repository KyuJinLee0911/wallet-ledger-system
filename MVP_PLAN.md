# Wallet Ledger System - MVP 범위 정의

> 혼자서 2~3주 안에 완성 가능한 범위
> 핵심 기술 역량 집중 시연 목적

---

## 컷 판단 기준

| 기준 | 설명 |
|------|------|
| 남긴다 | 면접에서 직접 설명할 수 있는 기술적 의사결정이 있는 것 |
| 뺀다 | 구현 시간 대비 포트폴리오 가치가 낮은 것 |
| 뺀다 | 추가 인프라(Redis, Kafka 등)가 필요한데 핵심이 아닌 것 |

---

## MVP에서 남기는 것 / 버리는 것

### 남기는 것

| 기능 | 이유 |
|------|------|
| 지갑 생성 / 잔액 조회 | 기본 도메인 |
| 입금(Deposit) | 트랜잭션 기본 구조 |
| 출금(Withdraw) + 비관적 락 | **동시성 제어 핵심 데모** |
| 이체(Transfer) + 데드락 방지 | **가장 강력한 면접 포인트** |
| Idempotency Key (DB 기반) | **중복 방지 설계 시연** |
| 불변 원장(LedgerEntry) | **이중 원장 설계 시연** |
| 감사 로그 (EntityListener) | **Audit 요건 시연** |
| 글로벌 예외 처리 | 실무 설계 완성도 |
| 동시성 테스트 | **설계 검증 증거** |
| 통합 테스트 (Testcontainers) | 테스트 전략 시연 |
| Docker Compose | 실행 환경 재현 |

### 버리는 것 (이유 포함)

| 제거 항목 | 제거 이유 |
|-----------|-----------|
| Redis | Idempotency는 DB UNIQUE로 충분. Redis는 Phase 2에서 추가 |
| Spring Batch 정산 | 2~3주 내 품질 있게 완성 어려움. 별도 Phase로 분리 |
| 포인트 유효기간 만료 | 배치 없이 의미 없음. 함께 제거 |
| 환불(Refund) / SAGA | 보상 트랜잭션은 설계가 복잡. 기본 취소 정도만 주석 처리 |
| 지갑 동결/해제 | CRUD 수준 추가 기능. 가치 낮음 |
| 다중 통화(Currency) | KRW 단일 고정. 복잡성 불필요 |
| QueryDSL | JPA JPQL로 충분. 도입 시간 낭비 |
| Swagger | README로 대체. 선택사항 |
| 분산 락(Redisson) | Redis 제거로 함께 제거. Phase 2 주제 |
| 이상 거래 탐지 | 금융 규칙이 복잡. 포트폴리오 가치 낮음 |
| Member 인증/인가 | Spring Security 범위 별도. 요청자 ID는 헤더로 단순 처리 |

---

## MVP 기능 목록 (최종)

```
[지갑]
  - 지갑 생성
  - 지갑 정보 + 잔액 조회

[거래]
  - 입금 (Deposit)       ← 트랜잭션 기본
  - 출금 (Withdraw)      ← 비관적 락
  - 이체 (Transfer)      ← 데드락 방지 락 순서

[조회]
  - 원장 이력 조회 (페이지네이션, offset 방식)
  - 단건 거래 조회

[공통]
  - Idempotency Key 처리 (DB UNIQUE + 결과 재반환)
  - 감사 로그 자동 기록
  - 도메인 예외 처리
```

---

## API (MVP 범위)

```
POST   /api/v1/wallets
       지갑 생성
       Body: { memberId }

GET    /api/v1/wallets/{walletId}
       지갑 정보 및 잔액 조회

POST   /api/v1/wallets/{walletId}/deposit
       입금
       Header: Idempotency-Key (필수)
       Body: { amount, description }

POST   /api/v1/wallets/{walletId}/withdraw
       출금
       Header: Idempotency-Key (필수)
       Body: { amount, description }

POST   /api/v1/transfers
       이체
       Header: Idempotency-Key (필수)
       Body: { fromWalletId, toWalletId, amount, description }

GET    /api/v1/wallets/{walletId}/ledger
       원장 이력 조회
       Query: page(default=0), size(default=20)

GET    /api/v1/transactions/{transactionId}
       단건 거래 조회
```

총 **7개 엔드포인트**.

---

## DB 테이블 (MVP 범위)

4개 테이블만 사용한다.

```
members         → 사용자 (인증 없이 ID만 존재)
wallets         → 지갑 (balance 캐시 + version for 낙관적 락 대비)
transactions    → 거래 단위 (idempotency_key UNIQUE)
ledger_entries  → 불변 원장 (CREDIT / DEBIT)
```

감사 로그는 별도 테이블 없이 **JPA `@EntityListeners`로 콘솔/파일 로그** 출력.
실무에서 DB 테이블 분리 이유는 README에 설명으로 대체한다.

---

## 3주 개발 일정

### Week 1 - 기반 구축 (5일)

| 일차 | 작업 |
|------|------|
| Day 1 | 프로젝트 생성, Docker Compose (PostgreSQL), 패키지 구조 확정 |
| Day 2 | Member, Wallet, Transaction, LedgerEntry 엔티티 + 플라이웨이 마이그레이션 |
| Day 3 | 지갑 생성 / 조회 API + 글로벌 예외 처리기 |
| Day 4 | 입금(Deposit) API + LedgerEntry 생성 + 트랜잭션 경계 설계 |
| Day 5 | 단위 테스트 (도메인 로직) + 통합 테스트 (Deposit happy path) |

**Week 1 완료 기준**: 지갑 생성 → 입금 → 잔액 조회 정상 동작

---

### Week 2 - 핵심 비즈니스 로직 (5일)

| 일차 | 작업 |
|------|------|
| Day 6 | 출금(Withdraw) API + 비관적 락 (`PESSIMISTIC_WRITE`) |
| Day 7 | 이체(Transfer) API + walletId 오름차순 락 순서 고정 |
| Day 8 | Idempotency Key 처리 (DB UNIQUE + `afterCommit` 결과 캐시) |
| Day 9 | 원장 조회 API + 단건 거래 조회 API |
| Day 10 | 동시성 테스트 (`CyclicBarrier` 100 스레드 출금) |

**Week 2 완료 기준**: 100개 동시 출금 → 잔액 정합성 보장 테스트 통과

---

### Week 3 - 마무리 (5일)

| 일차 | 작업 |
|------|------|
| Day 11 | 감사 로그 (`@EntityListeners` + `@PreUpdate` / `@PrePersist`) |
| Day 12 | 통합 테스트 (Testcontainers, 이체 정합성, Idempotency 중복) |
| Day 13 | 예외 케이스 보완 (잔액 부족, 존재하지 않는 지갑, 동결된 지갑 등) |
| Day 14 | README 작성 (아키텍처 설명, 실행 방법, 동시성 설계 설명) |
| Day 15 | 전체 동작 확인 + 코드 정리 + GitHub 정리 커밋 |

**Week 3 완료 기준**: README만 읽고 `docker-compose up` 후 API 전체 동작 확인

---

## 패키지 구조

```
src/main/java/com/example/wallet/
  ├── domain/
  │   ├── member/
  │   │   ├── Member.java
  │   │   └── MemberRepository.java
  │   ├── wallet/
  │   │   ├── Wallet.java
  │   │   ├── WalletRepository.java
  │   │   └── WalletService.java
  │   ├── transaction/
  │   │   ├── Transaction.java
  │   │   ├── TransactionRepository.java
  │   │   └── TransferService.java
  │   └── ledger/
  │       ├── LedgerEntry.java
  │       └── LedgerEntryRepository.java
  ├── api/
  │   ├── WalletController.java
  │   ├── TransferController.java
  │   └── dto/
  ├── common/
  │   ├── exception/
  │   │   ├── GlobalExceptionHandler.java
  │   │   ├── InsufficientBalanceException.java
  │   │   └── WalletNotFoundException.java
  │   ├── audit/
  │   │   └── AuditEntityListener.java
  │   └── idempotency/
  │       └── IdempotencyInterceptor.java
  └── WalletApplication.java
```

---

## 기술 스택 (MVP 확정)

| 항목 | 선택 | 제외 이유 |
|------|------|-----------|
| Java 17 | 사용 | |
| Spring Boot 3.x | 사용 | |
| Spring Data JPA | 사용 | |
| PostgreSQL 15 | 사용 | |
| Flyway | 사용 | DB 마이그레이션 이력 관리 |
| Testcontainers | 사용 | 실제 PostgreSQL 통합 테스트 |
| Docker Compose | 사용 | |
| Redis | **미사용** | MVP 범위 초과 |
| Spring Batch | **미사용** | MVP 범위 초과 |
| QueryDSL | **미사용** | JPA JPQL로 충분 |
| Swagger | **선택** | README로 우선 대체 |

---

## MVP에서 면접 어필 가능한 포인트

이 5가지만 완성해도 금융권 면접에서 충분히 설명 가능하다.

```
1. 비관적 락으로 동시 출금 Race Condition 방지
   → 코드 + 동시성 테스트 결과로 증명

2. 이체 시 데드락 방지를 위한 walletId 오름차순 락 순서 고정
   → 설계 이유 설명 가능

3. Idempotency Key로 네트워크 재시도 중복 처리 방지
   → DB UNIQUE 활용한 구현 설명 가능

4. 불변 원장(LedgerEntry) 기반 잔액 추적
   → 단순 balance UPDATE와의 차이 설명 가능

5. Testcontainers로 실제 PostgreSQL 환경 통합 테스트
   → H2 한계와 실제 락 동작 재현 필요성 설명 가능
```

---

## 이후 Phase 2 주제 (MVP 이후)

MVP 완성 후 여유가 생기면 아래 순서로 추가한다.

1. **Redis + Redisson 분산 락** — 멀티 서버 확장 대비 (2~3일)
2. **Spring Batch 정산 배치** — 일별 집계 파이프라인 (3~4일)
3. **포인트 유효기간 + 자동 소멸 배치** — Batch와 함께 구현 (2일)
4. **환불 API** — 단순 역방향 원장 추가 (1~2일)

---

*문서 최종 수정: 2026-03-06*
