# API Specification

## 개요

지갑 생성, 입금, 출금, 이체, 거래 조회 기능을 제공하는 REST API다.

모든 응답은 `ApiResponse<T>` 래퍼로 감싸져 일관된 구조를 유지한다.
금전성 요청(입금, 출금, 이체)은 `Idempotency-Key` 헤더가 필수다.

### 공통 응답 구조

**성공**
```json
{
  "success": true,
  "data": { ... },
  "error": null,
  "timestamp": "2024-01-15T10:30:00Z"
}
```

**실패**
```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "INSUFFICIENT_BALANCE",
    "message": "잔액이 부족합니다.",
    "detail": null
  },
  "timestamp": "2024-01-15T10:30:00Z"
}
```

---

## 엔드포인트 목록

| Method | Path | 설명 |
|--------|------|------|
| POST | `/wallets` | 지갑 생성 |
| POST | `/wallets/{walletId}/deposit` | 입금 |
| POST | `/wallets/{walletId}/withdraw` | 출금 |
| POST | `/transfers` | 이체 |
| GET | `/transactions` | 거래 목록 조회 |

---

## 엔드포인트 상세

### 지갑 생성

```
POST /wallets
```

회원 ID를 기반으로 지갑을 생성한다. 동일 회원이 여러 지갑을 가질 수 있다.

**Request Body**

| 필드 | 타입 | 필수 | 검증 규칙 |
|------|------|------|-----------|
| memberId | Long | Y | 1 이상 |
| currency | String | N | 3자리 대문자 통화 코드 (기본값 `KRW`) |

```json
{
  "memberId": 1,
  "currency": "KRW"
}
```

**Response** `201 Created`

```json
{
  "success": true,
  "data": {
    "walletId": 10,
    "memberId": 1,
    "balance": 0.0000,
    "currency": "KRW",
    "status": "ACTIVE",
    "createdAt": "2024-01-15T10:30:00Z"
  },
  "error": null,
  "timestamp": "2024-01-15T10:30:00Z"
}
```

**오류 케이스**

| HTTP | 코드 | 원인 |
|------|------|------|
| 400 | `INVALID_REQUEST` | memberId 누락 또는 유효하지 않은 값 |
| 400 | `VALIDATION_ERROR` | currency 형식 오류 |
| 404 | `MEMBER_NOT_FOUND` | 존재하지 않는 회원 |

---

### 입금

```
POST /wallets/{walletId}/deposit
Idempotency-Key: {고유 키}
```

지갑에 금액을 입금한다. 동일 `Idempotency-Key`로 재요청 시 중복 처리 없이 원본 거래 결과를 반환한다.

**Path Variable**

| 파라미터 | 설명 |
|----------|------|
| walletId | 입금 대상 지갑 ID (1 이상) |

**Request Header**

| 헤더 | 필수 | 설명 |
|------|------|------|
| Idempotency-Key | Y | 클라이언트가 생성한 고유 요청 식별자 (예: UUID) |

**Request Body**

| 필드 | 타입 | 필수 | 검증 규칙 |
|------|------|------|-----------|
| amount | BigDecimal | Y | 0.0001 이상, 최대 19자리 정수 + 4자리 소수 |
| description | String | N | 최대 500자 |

```json
{
  "amount": 10000.0000,
  "description": "포인트 충전"
}
```

**Response** `200 OK`

```json
{
  "success": true,
  "data": {
    "transactionId": 101,
    "type": "DEPOSIT",
    "status": "COMPLETED",
    "amount": 10000.0000,
    "completedAt": "2024-01-15T10:30:00Z"
  },
  "error": null,
  "timestamp": "2024-01-15T10:30:00Z"
}
```

**오류 케이스**

| HTTP | 코드 | 원인 |
|------|------|------|
| 400 | `MISSING_HEADER` | Idempotency-Key 헤더 누락 |
| 400 | `INVALID_AMOUNT` | 금액이 0 이하 |
| 400 | `WALLET_NOT_ACTIVE` | 비활성(FROZEN) 지갑 |
| 404 | `WALLET_NOT_FOUND` | 존재하지 않는 지갑 |
| 409 | `IDEMPOTENCY_KEY_CONFLICT` | 동일 키로 다른 타입의 거래가 이미 존재 |

---

### 출금

```
POST /wallets/{walletId}/withdraw
Idempotency-Key: {고유 키}
```

지갑에서 금액을 출금한다. 잔액이 부족하면 거래가 거부된다. 동시에 여러 출금이 들어와도 비관적 락으로 잔액 정합성을 보장한다.

**Path Variable**

| 파라미터 | 설명 |
|----------|------|
| walletId | 출금 대상 지갑 ID (1 이상) |

**Request Header**

| 헤더 | 필수 | 설명 |
|------|------|------|
| Idempotency-Key | Y | 클라이언트가 생성한 고유 요청 식별자 |

**Request Body**

| 필드 | 타입 | 필수 | 검증 규칙 |
|------|------|------|-----------|
| amount | BigDecimal | Y | 0.0001 이상, 최대 19자리 정수 + 4자리 소수 |
| description | String | N | 최대 500자 |

```json
{
  "amount": 5000.0000,
  "description": "결제"
}
```

**Response** `200 OK`

```json
{
  "success": true,
  "data": {
    "transactionId": 102,
    "type": "WITHDRAW",
    "status": "COMPLETED",
    "amount": 5000.0000,
    "completedAt": "2024-01-15T10:31:00Z"
  },
  "error": null,
  "timestamp": "2024-01-15T10:31:00Z"
}
```

**오류 케이스**

| HTTP | 코드 | 원인 |
|------|------|------|
| 400 | `MISSING_HEADER` | Idempotency-Key 헤더 누락 |
| 400 | `INSUFFICIENT_BALANCE` | 출금액 > 현재 잔액 |
| 400 | `INVALID_AMOUNT` | 금액이 0 이하 |
| 400 | `WALLET_NOT_ACTIVE` | 비활성(FROZEN) 지갑 |
| 404 | `WALLET_NOT_FOUND` | 존재하지 않는 지갑 |
| 409 | `IDEMPOTENCY_KEY_CONFLICT` | 동일 키로 다른 타입의 거래가 이미 존재 |

---

### 이체

```
POST /transfers
Idempotency-Key: {고유 키}
```

두 지갑 간 금액을 이체한다. 출금과 입금이 단일 트랜잭션으로 처리되므로 한쪽만 반영되는 상황이 발생하지 않는다. 교차 이체 요청에서 데드락이 발생하지 않도록 락 획득 순서가 내부적으로 고정된다.

**Request Header**

| 헤더 | 필수 | 설명 |
|------|------|------|
| Idempotency-Key | Y | 클라이언트가 생성한 고유 요청 식별자 |

**Request Body**

| 필드 | 타입 | 필수 | 검증 규칙 |
|------|------|------|-----------|
| fromWalletId | Long | Y | 1 이상, toWalletId와 달라야 함 |
| toWalletId | Long | Y | 1 이상, fromWalletId와 달라야 함 |
| amount | BigDecimal | Y | 0.0001 이상, 최대 19자리 정수 + 4자리 소수 |
| description | String | N | 최대 500자 |

```json
{
  "fromWalletId": 10,
  "toWalletId": 20,
  "amount": 3000.0000,
  "description": "송금"
}
```

**Response** `200 OK`

```json
{
  "success": true,
  "data": {
    "transactionId": 103,
    "type": "TRANSFER",
    "status": "COMPLETED",
    "amount": 3000.0000,
    "completedAt": "2024-01-15T10:32:00Z"
  },
  "error": null,
  "timestamp": "2024-01-15T10:32:00Z"
}
```

**오류 케이스**

| HTTP | 코드 | 원인 |
|------|------|------|
| 400 | `MISSING_HEADER` | Idempotency-Key 헤더 누락 |
| 400 | `SAME_WALLET_TRANSFER` | fromWalletId == toWalletId |
| 400 | `INSUFFICIENT_BALANCE` | 출금 지갑 잔액 부족 |
| 400 | `WALLET_NOT_ACTIVE` | 송신 또는 수신 지갑이 비활성 |
| 404 | `WALLET_NOT_FOUND` | 송신 또는 수신 지갑이 존재하지 않음 |
| 409 | `IDEMPOTENCY_KEY_CONFLICT` | 동일 키로 다른 타입의 거래가 이미 존재 |

---

### 거래 목록 조회

```
GET /transactions?page=0&size=20&sort=id,desc
```

전체 거래 내역을 페이지 단위로 조회한다.

**Query Parameters**

| 파라미터 | 기본값 | 설명 |
|----------|--------|------|
| page | 0 | 페이지 번호 (0부터 시작) |
| size | 20 | 페이지당 항목 수 |
| sort | id,desc | 정렬 기준 |

**Response** `200 OK`

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "transactionId": 103,
        "type": "TRANSFER",
        "status": "COMPLETED",
        "amount": 3000.0000,
        "completedAt": "2024-01-15T10:32:00Z"
      },
      {
        "transactionId": 102,
        "type": "WITHDRAW",
        "status": "COMPLETED",
        "amount": 5000.0000,
        "completedAt": "2024-01-15T10:31:00Z"
      }
    ],
    "totalElements": 103,
    "totalPages": 6,
    "size": 20,
    "number": 0
  },
  "error": null,
  "timestamp": "2024-01-15T10:33:00Z"
}
```

---

## 설계 노트

### Idempotency-Key를 헤더로 받는 이유

요청 본문(body)은 같은 내용이지만 서버 상태에 따라 처리 결과가 달라질 수 있다. 멱등 키는 "이 요청이 이전 요청과 동일한 것인가"를 식별하는 메타데이터이므로, 비즈니스 데이터인 body와 분리해 헤더로 전달한다.

서버는 동일 키로 이미 완료된 거래가 있으면 재처리 없이 원본 결과를 반환한다. 클라이언트는 타임아웃 후 동일 키로 재시도하면 된다.

### 금전성 요청의 트랜잭션 보장

입금, 출금, 이체 요청은 내부적으로 다음 단계를 단일 DB 트랜잭션으로 처리한다.

```
1. 지갑 행 잠금 (SELECT FOR UPDATE)
2. 거래 레코드 저장 (PENDING)
3. 잔액 변경
4. 거래 상태 완료 (COMPLETED)
5. 원장 레코드 저장
```

어느 단계에서든 예외가 발생하면 전체가 롤백된다. 거래와 원장이 불일치하는 상태는 발생하지 않는다.

### 거래 조회 페이지네이션

거래가 누적될수록 전체 조회는 응답 크기와 DB 부하 문제가 생긴다. 기본 페이지 크기(20건)와 `id DESC` 정렬을 적용해 최신 거래를 우선 반환하고 과도한 조회를 방지한다.
