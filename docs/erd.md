# ERD

## 개요

지갑 거래 시스템의 데이터 모델은 네 가지 핵심 개념으로 구성된다.

- **Member**: 지갑을 소유하는 주체
- **Wallet**: 잔액을 관리하는 지갑
- **WalletTransaction**: 거래 단위와 상태를 추적하는 레코드
- **LedgerEntry**: 잔액 변화를 불변으로 기록하는 원장 레코드

거래(transaction)와 원장(ledger)을 분리하는 것이 이 모델의 핵심 설계 결정이다.

---

## 테이블 관계

```
members
  │
  │ 1:N
  ▼
wallets ──────────────────────────── ledger_entries
                                         ▲
transactions ────────────────────────────┘
  (idempotency_key UNIQUE)
```

- `members` → `wallets`: 한 회원이 여러 지갑을 가질 수 있다 (1:N)
- `wallets` → `ledger_entries`: 한 지갑의 잔액 변화는 여러 원장 레코드로 기록된다 (1:N)
- `transactions` → `ledger_entries`: 한 거래에 연결된 원장 레코드가 1개 이상 존재한다 (1:N)
  - 입금/출금: 원장 1건 (CREDIT 또는 DEBIT)
  - 이체: 원장 2건 (출금 지갑 DEBIT + 입금 지갑 CREDIT)

---

## 핵심 테이블

### members

지갑 소유자를 나타내는 엔티티. 이 시스템에서 회원 관리는 최소 수준으로 구현되며, 지갑 생성 시 회원 존재 여부를 검증하는 용도로 사용된다.

| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | BIGSERIAL PK | 회원 식별자 |
| username | VARCHAR(50) UNIQUE | 사용자명 |
| email | VARCHAR(100) UNIQUE | 이메일 주소 |
| status | VARCHAR(20) | 회원 상태 (`ACTIVE`) |
| created_at | TIMESTAMPTZ | 생성 시각 (UTC 기준 절대 시각) |
| updated_at | TIMESTAMPTZ | 최종 수정 시각 (UTC 기준 절대 시각) |

---

### wallets

잔액과 통화를 관리하는 지갑 엔티티. 잔액은 항상 0 이상으로 유지되어야 하며, DB 제약(`CHECK (balance >= 0)`)으로 강제한다.

| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | BIGSERIAL PK | 지갑 식별자 |
| member_id | BIGINT FK | 소유 회원 (`members.id` 참조) |
| balance | NUMERIC(19,4) | 현재 잔액. `CHECK (balance >= 0)` 제약 |
| currency | VARCHAR(10) | 통화 코드 (기본값 `KRW`) |
| status | VARCHAR(20) | 지갑 상태 (`ACTIVE` / `FROZEN`) |
| created_at | TIMESTAMPTZ | 생성 시각 (UTC 기준 절대 시각) |
| updated_at | TIMESTAMPTZ | 최종 수정 시각 (UTC 기준 절대 시각) |

**인덱스**: `idx_wallet_member (member_id)` — 회원별 지갑 조회 성능

---

### transactions

거래 한 건의 메타데이터와 처리 상태를 관리하는 엔티티. `idempotency_key`의 UNIQUE 제약이 중복 거래를 DB 수준에서 방지하는 핵심 장치다.

| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | BIGSERIAL PK | 거래 식별자 |
| idempotency_key | VARCHAR(100) UNIQUE | 멱등 키. 동일 키 중복 삽입 방지 |
| type | VARCHAR(30) | 거래 유형 (`DEPOSIT` / `WITHDRAW` / `TRANSFER`) |
| status | VARCHAR(20) | 처리 상태 (`PENDING` → `COMPLETED`) |
| amount | NUMERIC(19,4) | 거래 금액 |
| requested_at | TIMESTAMPTZ | 거래 요청 시각 (UTC 기준 절대 시각) |
| completed_at | TIMESTAMPTZ | 거래 완료 시각 — PENDING 상태에서는 NULL |
| created_at | TIMESTAMPTZ | 레코드 생성 시각 (UTC 기준 절대 시각) |
| updated_at | TIMESTAMPTZ | 최종 수정 시각 (UTC 기준 절대 시각) |

---

### ledger_entries

잔액 변화를 기록하는 불변 원장 레코드. 한 번 저장된 레코드는 수정되지 않는다. `balance_after`를 기록해 임의 시점의 잔액을 원장 레코드만으로 재계산할 수 있다.

| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | BIGSERIAL PK | 원장 레코드 식별자 |
| wallet_id | BIGINT FK | 잔액이 변경된 지갑 (`wallets.id` 참조) |
| transaction_id | BIGINT FK | 연결된 거래 (`transactions.id` 참조) |
| type | VARCHAR(10) | 변화 방향 (`CREDIT`: 증가 / `DEBIT`: 감소) |
| amount | NUMERIC(19,4) | 변화 금액. `CHECK (amount > 0)` 제약 |
| balance_after | NUMERIC(19,4) | 이 레코드 기록 직후의 지갑 잔액 |
| description | VARCHAR(500) | 거래 설명 (선택) |
| created_at | TIMESTAMPTZ | 레코드 생성 시각 (UTC 기준 절대 시각) |
| updated_at | TIMESTAMPTZ | 최종 수정 시각 (UTC 기준 절대 시각) |

**인덱스**: `idx_ledger_wallet_created (wallet_id, created_at DESC)` — 지갑별 원장 이력 시간순 조회 성능

---

## 설계 결정

### 거래(transactions)와 원장(ledger_entries)을 분리한 이유

단일 테이블에 거래와 잔액 변화를 함께 저장하면 거래 상태 관리와 잔액 이력 관리가 뒤섞인다.

- `transactions`는 멱등성 제어(`idempotency_key UNIQUE`)와 거래 상태(`PENDING` → `COMPLETED`) 추적을 담당한다.
- `ledger_entries`는 잔액 변화의 사실을 불변 레코드로 기록하는 감사 원장 역할을 한다.

이 분리로 `ledger_entries`만으로도 임의 시점의 잔액을 재계산할 수 있고, `wallets.balance`와의 교차 검증이 가능하다.

### transactions 테이블에 wallet_id가 없는 이유

거래와 지갑의 관계는 `ledger_entries`가 중개한다. 입금/출금은 원장 1건, 이체는 원장 2건(DEBIT + CREDIT)으로 표현된다. 이 구조는 이체를 "두 지갑에 걸친 하나의 거래"로 자연스럽게 표현하며, transactions 테이블 스키마 변경 없이 N개의 지갑을 연결하는 거래 유형을 추가할 수 있다.

### balance_after를 원장에 저장하는 이유

각 원장 레코드에 거래 직후 잔액을 함께 저장하면:

1. 원장 레코드만으로 임의 시점의 잔액 재계산이 가능하다.
2. `wallets.balance` 값이 이상한 경우 원장 기반으로 정합성을 검증할 수 있다.
3. 잔액 필드 오염 시 원장 이력을 근거로 복구할 수 있다.

### FK 제약을 일부 테이블에서 사용하지 않는 이유

`transactions`는 `wallet_id` FK를 갖지 않는다. 거래와 지갑의 연결은 `ledger_entries`가 담당하기 때문이다. 이 설계는 거래 레코드가 지갑 삭제에 영향받지 않도록 하고, 향후 멀티 지갑 거래 유형 추가 시 스키마 변경을 최소화한다.
