# 멱등성(Idempotency) 테스트 시나리오 설계서

> 대상 시스템: Wallet Ledger System
> 관련 구현: `WalletLedgerServiceImpl` — `deposit`, `withdraw`, `transfer`
> 테스트 환경 전제: JUnit 5 + Spring Boot Test + Testcontainers (PostgreSQL)

---

## 구현 방어 구조 요약

이 시스템의 멱등성은 **2단계 방어 구조**로 구현되어 있다.

```
요청 진입
  ↓
[1단계] findReplayableTransaction()
  → DB에서 키 조회 (비관적 락 없음, 빠른 경로)
  → COMPLETED + 동일 타입인 경우 즉시 기존 결과 반환
  ↓ (기존 없을 때만)
[2단계] saveStartedTransaction()
  → WalletTransaction INSERT 시도
  → DataIntegrityViolationException 발생 시:
       COMPLETED + 동일 타입 → 기존 결과 반환  (동시 재시도 처리)
       그 외 → IDEMPOTENCY_KEY_CONFLICT(409) 던짐
```

각 테스트 시나리오는 이 두 경로 중 어느 방어선이 동작하는지를 명시한다.

---

## 시나리오 1. 순차 중복 요청 — 원본 결과 반환

### 1. 테스트 목적

동일 멱등 키로 요청이 **순차적으로** 두 번 도달했을 때(첫 번째가 완전히 완료된 후 두 번째 도착),
새 거래를 생성하지 않고 첫 번째 거래의 원본 결과를 그대로 반환하는지 검증한다.

**작동 경로**: 1단계 — `findReplayableTransaction()` 히트

### 2. 사전 데이터 조건

| 항목 | 값 |
|------|----|
| 회원 | memberId = 1 |
| 지갑 | walletId = 1, balance = 0, status = ACTIVE |
| 멱등 키 | `"key-seq-001"` (DB에 없는 상태) |

### 3. 테스트 단계

```
Step 1. walletId=1, amount=5000, key="key-seq-001" 로 deposit 호출
        → 성공 응답 수신
        → 응답의 transactionId, amount, completedAt 기록

Step 2. 동일 파라미터로 deposit 재호출 (key 동일)

Step 3. Step 2의 응답 확인
```

### 4. 검증 포인트

```
[응답 동일성]
- Step 1 응답의 transactionId == Step 2 응답의 transactionId
- Step 1 응답의 amount       == Step 2 응답의 amount
- Step 1 응답의 completedAt  == Step 2 응답의 completedAt

[DB 불변성]
- transactions 테이블에서 idempotency_key = "key-seq-001" 인 레코드 수 == 1
- ledger_entries 테이블에서 해당 transaction_id 의 레코드 수 == 1

[잔액 정합성]
- wallets 테이블의 balance == 5000 (두 번 입금된 10000이 아님)
```

### 5. 구현 시 주의점

- 두 번째 요청에서 `findReplayableTransaction()`이 `Optional.isPresent() == true`를 반환하는지
  직접 확인하려면 서비스에 spy/로그를 심거나, 실행 후 DB 상태로 간접 검증한다.
- Testcontainers 사용 시 테스트 간 데이터 격리를 위해 `@Transactional` 또는
  `@Sql(executionPhase = AFTER_TEST_METHOD)` 로 롤백 처리한다.

### 6. 면접에서 왜 중요한가

> "네트워크 타임아웃 후 클라이언트가 재시도할 때 입금이 두 번 되지 않는다는 걸
> 어떻게 보장하나요?"

이 테스트가 그 보장의 직접적인 증거다. 단순히 "Idempotency Key를 씁니다"라고
말하는 것과 "이 테스트가 통과합니다"를 보여주는 것은 면접에서 완전히 다른 설명력을 갖는다.

---

## 시나리오 2. 출금 잔액 이중 차감 방지

### 1. 테스트 목적

출금 요청이 동일 멱등 키로 두 번 들어왔을 때 잔액이 **한 번만** 차감됨을 검증한다.
입금보다 출금이 더 중요한 이유는, 잘못되면 잔액이 음수가 되거나 과도하게 줄어드는
**금융 사고**로 이어지기 때문이다.

**작동 경로**: 1단계 — `findReplayableTransaction()` 히트

### 2. 사전 데이터 조건

| 항목 | 값 |
|------|----|
| 지갑 | walletId = 1, balance = 10000 |
| 멱등 키 | `"key-withdraw-001"` (DB에 없는 상태) |

### 3. 테스트 단계

```
Step 1. walletId=1, amount=3000, key="key-withdraw-001" 로 withdraw 호출
        → 성공 응답 수신

Step 2. 동일 파라미터로 withdraw 재호출

Step 3. walletId=1 의 지갑 잔액 조회
```

### 4. 검증 포인트

```
[잔액 정합성 — 핵심]
- 최종 balance == 7000 (10000 - 3000)
- balance != 4000 (이중 차감되지 않았음을 확인)
- balance >= 0 (음수 잔액 없음)

[응답 동일성]
- Step 1 transactionId == Step 2 transactionId

[DB 불변성]
- transactions 테이블 레코드 수 (해당 키) == 1
- ledger_entries 의 DEBIT 레코드 수 (해당 wallet_id + transaction_id) == 1
```

### 5. 구현 시 주의점

- 잔액 검증은 `walletRepository.findById()` 로 직접 읽거나,
  `GET /wallets/{walletId}` API 응답으로 검증한다.
- `balance == 7000` 단언은 `BigDecimal.compareTo()`로 비교해야 한다.
  `assertEquals`를 BigDecimal에 그대로 쓰면 스케일 차이(7000 vs 7000.0000)로
  오탐이 발생할 수 있다.

### 6. 면접에서 왜 중요한가

> "잔액 차감이 두 번 일어나지 않는다는 걸 어떻게 증명하나요?"

멱등성 테스트 중 가장 직관적이고 설명하기 쉬운 케이스다.
"출금 10000원인데 재시도로 20000원이 빠지지 않는다"는 문장 하나로
비즈니스 가치와 기술적 검증을 동시에 설명할 수 있다.

---

## 시나리오 3. 이체 멱등성 — 양측 잔액 이중 반영 방지

### 1. 테스트 목적

이체 요청이 동일 멱등 키로 두 번 들어왔을 때, 송신 지갑의 차감과
수신 지갑의 입금이 각각 **한 번만** 발생함을 검증한다.
이체는 두 지갑이 동시에 변경되므로 중복 처리 시 피해가 두 배다.

**작동 경로**: 1단계 — `findReplayableTransaction()` 히트

### 2. 사전 데이터 조건

| 항목 | 값 |
|------|----|
| 지갑 A (송신) | walletId = 1, balance = 20000 |
| 지갑 B (수신) | walletId = 2, balance = 0 |
| 멱등 키 | `"key-transfer-001"` |

### 3. 테스트 단계

```
Step 1. fromWalletId=1, toWalletId=2, amount=5000, key="key-transfer-001" 로 transfer 호출
        → 성공 응답 수신

Step 2. 동일 파라미터로 transfer 재호출

Step 3. 지갑 1, 지갑 2 잔액 각각 조회
```

### 4. 검증 포인트

```
[양측 잔액 정합성 — 핵심]
- 지갑 1 최종 balance == 15000 (20000 - 5000, 10000 아님)
- 지갑 2 최종 balance == 5000  (0 + 5000, 10000 아님)

[시스템 전체 잔액 불변]
- 지갑 1 balance + 지갑 2 balance == 20000 (이체 전후 합계 동일)

[DB 원장 불변성]
- 해당 idempotency_key 의 transactions 레코드 수 == 1
- 해당 transaction_id 의 ledger_entries 레코드 수 == 2 (DEBIT 1건 + CREDIT 1건)
```

### 5. 구현 시 주의점

- 시스템 전체 잔액 합산 검증(`지갑1 + 지갑2 == 20000`)은 이중 원장 설계의 핵심
  불변식이다. 이 검증이 있어야 "돈이 어디서 생기거나 사라지지 않는다"를 증명할 수 있다.
- 원장 레코드가 정확히 2건인지 확인함으로써 이체의 Double-Entry 보장을 검증한다.

### 6. 면접에서 왜 중요한가

> "이체 실패 시 한쪽만 처리되는 상황을 어떻게 막나요? 그리고 이체 재시도는요?"

두 질문을 하나의 테스트로 커버한다. `@Transactional` 원자성과
멱등성 보장을 동시에 증명하는 케이스로, 면접에서 가장 임팩트 있는 시나리오다.

---

## 시나리오 4. 동시 중복 요청 — DB UNIQUE 최후 방어선

### 1. 테스트 목적

첫 번째 요청이 **아직 커밋되지 않은 상태**에서 두 번째 요청이 동시에 도달할 때,
`findReplayableTransaction()`을 통과하더라도 DB UNIQUE 제약이 중복을 차단하고
두 번째 요청이 첫 번째의 결과를 반환하는지 검증한다.

**작동 경로**: 2단계 — `saveStartedTransaction()` 의 `DataIntegrityViolationException` 캐치

이 시나리오가 **1단계가 막지 못하는 진짜 Race Condition**을 처리하는 경로를 검증한다.

### 2. 사전 데이터 조건

| 항목 | 값 |
|------|----|
| 지갑 | walletId = 1, balance = 0 |
| 멱등 키 | `"key-concurrent-001"` |
| 스레드 수 | 2 (동시 요청) |

### 3. 테스트 단계

```
Step 1. CyclicBarrier(2) 로 두 스레드를 출발 지점에 대기시킨다.

Step 2. 두 스레드 모두 동일 파라미터
        (walletId=1, amount=1000, key="key-concurrent-001") 로 deposit 호출을
        동시에 출발시킨다.

Step 3. 두 스레드의 결과를 수집한다.
        - 예외 발생 여부
        - 반환된 transactionId

Step 4. DB 상태 조회
```

### 4. 검증 포인트

```
[처리 결과]
- 두 스레드 모두 예외 없이 완료됨
- 두 스레드의 응답 transactionId 가 동일함 (같은 거래를 반환)

[잔액 정합성]
- 최종 balance == 1000 (두 번 입금된 2000 아님)

[DB 불변성]
- idempotency_key = "key-concurrent-001" 의 transactions 레코드 수 == 1
- 해당 ledger_entries 레코드 수 == 1
```

### 5. 구현 시 주의점

- `CyclicBarrier` 를 쓰는 이유: `CountDownLatch` 만으로는 실제 동시 실행이
  보장되지 않는다. `CyclicBarrier.await()` 를 호출 직전에 넣어야 두 스레드가
  같은 시점에 서비스 메서드로 진입한다.
- 멀티스레드 테스트에서 `@Transactional` 롤백은 동작하지 않는다.
  테스트 후 데이터 정리를 `@AfterEach` 에서 명시적으로 수행해야 한다.
- H2 인메모리 DB는 PostgreSQL의 SELECT FOR UPDATE 락 동작을 완전히 재현하지 못한다.
  이 테스트는 반드시 **Testcontainers + 실제 PostgreSQL** 로 실행해야 한다.
- 간헐적 실패(Flaky Test) 방지를 위해 충분한 타임아웃(최소 10초)을 설정한다.

### 6. 면접에서 왜 중요한가

> "Redis 없이 DB만으로 동시 중복 요청을 막을 수 있나요? 어떻게 증명하나요?"

이 테스트가 그 증거다. "DB UNIQUE 제약을 최후 방어선으로 설계했다"는 말을
실행 가능한 코드로 보여줄 수 있다. 면접관이 "직접 테스트해봤나요?"라고
물을 때 가장 명확하게 답할 수 있는 시나리오다.

---

## 시나리오 5. 다른 거래 타입에 동일 키 사용 — 타입 충돌 감지

### 1. 테스트 목적

멱등 키는 거래 타입(DEPOSIT/WITHDRAW/TRANSFER)과 함께 의미를 가진다.
입금에 사용한 키를 출금에 재사용하면 **충돌(409)**로 처리해야 한다.
이 동작이 `saveStartedTransaction()` 의 타입 검증에서 올바르게 작동하는지 확인한다.

**작동 경로**: 2단계 — `saveStartedTransaction()` 에서 타입 불일치 → `IDEMPOTENCY_KEY_CONFLICT`

### 2. 사전 데이터 조건

| 항목 | 값 |
|------|----|
| 지갑 | walletId = 1, balance = 10000 |
| 멱등 키 | `"key-type-conflict-001"` |

### 3. 테스트 단계

```
Step 1. walletId=1, amount=1000, key="key-type-conflict-001" 로 deposit 호출
        → 성공

Step 2. 동일 key="key-type-conflict-001" 로 withdraw 호출 (타입이 다름)
        → 결과 기록
```

### 4. 검증 포인트

```
[Step 2 응답]
- HTTP Status == 409 Conflict
- 에러 코드 == "IDEMPOTENCY_KEY_CONFLICT"

[DB 불변성]
- key="key-type-conflict-001" 의 transactions 레코드는 여전히 1건 (DEPOSIT 타입)
- 지갑 balance == 11000 (입금 1건만 반영, 출금 미반영)
```

### 5. 구현 시 주의점

- 이 시나리오는 "멱등 키가 요청 타입과 결합되어 의미를 가진다"는 설계를 검증한다.
  이 동작이 의도된 것인지, 아니면 단순히 키만으로 처리해야 하는지는
  도메인 정책에 따라 다르다. 현재 구현의 동작 방식을 문서화하는 것이 목적이다.
- `saveStartedTransaction()` 에서 `existing.get().getType() == type` 조건이
  핵심이다. 이 조건이 없으면 다른 타입의 기존 거래를 반환하는 버그가 발생한다.

### 6. 면접에서 왜 중요한가

> "멱등 키는 어떤 범위까지 유효한가요? 거래 종류가 달라도 같은 키를 쓰면 어떻게 되나요?"

이 시나리오는 멱등성 설계의 **경계 조건**을 이해하고 있다는 것을 보여준다.
단순히 "키가 같으면 같은 결과를 반환한다"라는 이해를 넘어,
"키 + 타입의 조합으로 거래를 식별한다"는 더 정확한 설계 의도를 설명할 수 있다.

---

## 시나리오 6. 실패한 요청의 멱등 키 재사용 가능성

### 1. 테스트 목적

첫 번째 요청이 도메인 검증 실패(잔액 부족 등)로 롤백되었을 때,
**동일 멱등 키로 다시 요청하면 새 거래로 처리**되는지 검증한다.

실패로 롤백된 거래는 DB에 흔적이 없으므로 키가 "소비된" 것으로 보면 안 된다.
클라이언트는 문제를 수정한 뒤(예: 잔액 충전 후) 같은 키로 재시도할 수 있어야 한다.

### 2. 사전 데이터 조건

| 항목 | 값 |
|------|----|
| 지갑 | walletId = 1, balance = 1000 |
| 멱등 키 | `"key-retry-after-fail-001"` |

### 3. 테스트 단계

```
Step 1. walletId=1, amount=5000, key="key-retry-after-fail-001" 로 withdraw 호출
        (잔액 1000 < 요청 5000 → INSUFFICIENT_BALANCE 예외 예상)
        → HTTP 400, 에러 코드 INSUFFICIENT_BALANCE 응답 수신

Step 2. 지갑에 10000원 입금 (별도 키로, 잔액 충전)
        → 지갑 balance = 11000

Step 3. 동일 key="key-retry-after-fail-001" 로 amount=5000 withdraw 재호출
        → 결과 기록
```

### 4. 검증 포인트

```
[Step 1 롤백 확인]
- transactions 테이블에 key="key-retry-after-fail-001" 레코드 없음
  (PENDING 도 커밋되지 않았음을 확인)

[Step 3 결과]
- HTTP Status == 200
- 새 거래가 정상 생성됨 (Step 1과 다른 transactionId)
- 지갑 balance == 6000 (11000 - 5000)
```

### 5. 구현 시 주의점

- 이 시나리오의 핵심은 `@Transactional` 에 의해 실패한 `saveStartedTransaction()`의
  결과까지 롤백된다는 것이다. PENDING 레코드가 DB에 잔류하지 않아야 한다.
- Step 1 이후 `transactionRepository.findByIdempotencyKey("key-retry-after-fail-001")`
  결과가 `empty`임을 명시적으로 단언한다.

### 6. 면접에서 왜 중요한가

> "실패한 요청의 멱등 키를 재사용하면 어떻게 되나요?"

많은 지원자가 "키는 한 번만 사용 가능하다"고 잘못 이해한다.
**실패한 요청은 흔적이 없으므로 키를 재사용할 수 있다**는 것이
올바른 멱등성 설계다. 이 차이를 설명하는 시나리오는 멱등성에 대한 깊은 이해를 보여준다.

---

## 시나리오 7. 빈 멱등 키 — 입력 검증 계층 동작 확인

### 1. 테스트 목적

멱등 키가 빈 문자열이거나 공백만 있는 경우 **서비스 진입 전에 차단**되는지 확인한다.
이는 멱등성 충돌(409)이 아닌 잘못된 입력(400)으로 처리되어야 한다.

### 2. 사전 데이터 조건

| 항목 | 값 |
|------|----|
| 지갑 | walletId = 1, balance = 10000 |

### 3. 테스트 단계

```
Step 1. Idempotency-Key 헤더 없이 deposit 요청
        → 결과 기록

Step 2. Idempotency-Key = "" (빈 문자열) 로 deposit 요청
        → 결과 기록

Step 3. Idempotency-Key = "   " (공백만) 로 deposit 요청
        → 결과 기록
```

### 4. 검증 포인트

```
[Step 1 — 헤더 누락]
- HTTP Status == 400
- 에러 코드 == "MISSING_HEADER"
- (GlobalExceptionHandler.handleMissingRequestHeaderException 경로)

[Step 2 — 빈 문자열]
- HTTP Status == 400
- 에러 코드 == "VALIDATION_ERROR" 또는 "INVALID_REQUEST"
- (@NotBlank 또는 validateIdempotencyKey 중 어느 쪽이 먼저 잡는지 확인)

[Step 3 — 공백만]
- HTTP Status == 400
- 에러 코드 != "IDEMPOTENCY_KEY_CONFLICT" (409 아님을 명시적으로 확인)

[공통]
- DB에 어떠한 레코드도 생성되지 않음
- 지갑 balance 변화 없음
```

### 5. 구현 시 주의점

- Step 2와 Step 3은 두 개의 다른 방어선에서 잡힐 수 있다.
  컨트롤러의 `@NotBlank`(Controller Validation)와 서비스의 `validateIdempotencyKey()`
  중 **어느 쪽이 먼저 동작하는지** 확인하는 것도 이 테스트의 목적이다.
  현재 구현에서는 `@NotBlank` 가 컨트롤러에 있으므로 서비스 진입 전에 차단된다.

### 6. 면접에서 왜 중요한가

> "입력 검증은 어느 레이어에서 하나요? 왜 그렇게 나눴나요?"

이 테스트는 컨트롤러 검증(HTTP 인터페이스 규약)과 서비스 검증(도메인 규칙)의
책임 분리를 검증하는 케이스다.
"컨트롤러는 HTTP 계약을 검증하고, 서비스는 도메인 규칙을 검증한다"는
레이어 설계 원칙을 실제로 확인하는 테스트로 설명할 수 있다.

---

## 시나리오 8. 응답 필드 완전 일관성 — 모든 필드 비교

### 1. 테스트 목적

재시도 응답이 원본 응답과 **모든 필드에서** 동일한지 검증한다.
`transactionId`만 같고 `completedAt` 이나 `amount` 가 다르면 진정한 멱등성이 아니다.

### 2. 사전 데이터 조건

| 항목 | 값 |
|------|----|
| 지갑 | walletId = 1, balance = 0 |
| 멱등 키 | `"key-full-compare-001"` |

### 3. 테스트 단계

```
Step 1. deposit 호출 → 첫 번째 응답 전체 저장
        {
          transactionId: X,
          type: "DEPOSIT",
          status: "COMPLETED",
          amount: 1000,
          completedAt: T1
        }

Step 2. 동일 파라미터로 deposit 재호출 → 두 번째 응답 전체 저장

Step 3. 두 응답의 모든 필드를 비교
```

### 4. 검증 포인트

```
[필드별 완전 일치]
- firstResponse.transactionId == secondResponse.transactionId
- firstResponse.type          == secondResponse.type
- firstResponse.status        == secondResponse.status
- firstResponse.amount        == secondResponse.amount         (BigDecimal.compareTo)
- firstResponse.completedAt   == secondResponse.completedAt    (Instant 동등 비교)

[Instant 동등 비교 주의]
- completedAt 은 밀리초 단위까지 동일해야 한다.
  재시도 시 새로운 Instant.now() 를 호출하면 이 검증이 실패한다.
  현재 구현에서 재시도 시 DB에서 읽어온 기존 completedAt 을 그대로 반환하는지
  이 테스트가 보장한다.
```

### 5. 구현 시 주의점

- `completedAt` 비교는 가장 예민한 필드다. 만약 서비스 코드가 재시도 시
  `Instant.now()` 를 새로 설정한다면 이 테스트가 실패한다.
  현재 구현은 DB에서 읽어온 기존 엔티티를 그대로 반환하므로 `completedAt`이
  동일해야 한다. 이 동작을 이 테스트가 명시적으로 고정(pin)한다.
- `BigDecimal` 비교는 반드시 `compareTo() == 0` 을 사용한다.
  `equals()` 는 `1000` 과 `1000.0000` 을 다르게 처리한다.

### 6. 면접에서 왜 중요한가

> "재시도 응답이 원본 응답과 동일하다는 걸 어떻게 보장하나요?
> completedAt 같은 시각 필드도요?"

이 테스트가 멱등성의 "완전한 재현성"을 코드로 증명한다.
`completedAt` 까지 동일하다는 것은 단순히 "같은 데이터를 반환"하는 수준이 아니라
"거래가 처음 처리된 시각 자체를 보존해서 반환"한다는 것을 의미한다.
이 개념까지 설명하면 금융 시스템 설계에 대한 깊은 이해를 보여줄 수 있다.

---

## 테스트 실행 환경 구성 가이드

### PostgreSQL Testcontainers 설정 포인트

```
1. @Testcontainers 어노테이션과 PostgreSQLContainer 정의
2. spring.datasource.url 을 컨테이너 JDBC URL 로 동적 오버라이드
3. Flyway 또는 JPA ddl-auto 로 스키마 자동 생성
4. idempotency_key UNIQUE 제약이 실제 PostgreSQL 에 존재하는지 확인
   (H2 에서는 DataIntegrityViolationException 동작이 다를 수 있음)
```

### 테스트 데이터 격리 전략

| 방식 | 적용 상황 | 주의점 |
|------|-----------|--------|
| `@Transactional` 롤백 | 단일 스레드 테스트 | 멀티스레드 시나리오(시나리오 4)에서는 동작 안 함 |
| `@Sql` AFTER_TEST_METHOD | 멀티스레드 테스트 | DELETE 순서에 FK 제약 주의 |
| `@DirtiesContext` | 최후 수단 | 컨텍스트 재시작으로 느림 |

### 시나리오 우선순위

| 우선순위 | 시나리오 | 이유 |
|----------|----------|------|
| 1 | 시나리오 4 (동시 중복) | 진짜 Race Condition 검증, 면접 핵심 증거 |
| 2 | 시나리오 2 (출금 이중 차감) | 금융 사고 방지 직접 증명 |
| 3 | 시나리오 6 (실패 후 재사용) | 멱등성 깊이 이해 시연 |
| 4 | 시나리오 1 (순차 중복) | 가장 기본 케이스, 구현 진입점 |
| 5 | 시나리오 8 (전 필드 비교) | 완전성 증명 |
| 6 | 시나리오 3, 5, 7 | 경계 조건 및 타입 충돌 |

---

*문서 최종 수정: 2026-03-06*
*작성자: Test Agent (Claude Code)*
