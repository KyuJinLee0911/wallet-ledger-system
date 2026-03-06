# 동시성 제어(Concurrency Control) 테스트 시나리오 설계서

> 대상 시스템: Wallet Ledger System
> 관련 구현: `WalletLedgerServiceImpl` — `withdraw`, `deposit`, `transfer`
> 락 전략: `PESSIMISTIC_WRITE` (SELECT ... FOR UPDATE)
> 테스트 환경 전제: JUnit 5 + Spring Boot Test + Testcontainers (PostgreSQL)

---

## 동시성 방어 구조 요약

이 시스템의 잔액 정합성은 **3단계 방어 구조**로 보장된다.

```
동시 출금 요청 N개 진입
  ↓
[1단계] findByIdForUpdate()
  → SELECT ... FOR UPDATE
  → 하나의 스레드만 진입, 나머지는 락 대기 큐 진입
  ↓ (락 획득 순서대로 직렬 처리)
[2단계] wallet.withdraw() — 도메인 검증
  → balance.compareTo(amount) < 0 이면 INSUFFICIENT_BALANCE 예외
  → 잔액이 충분할 때만 차감
  ↓
[3단계] DB CHECK 제약 (선택적, Flyway 마이그레이션으로 추가 시)
  → balance >= 0 위반 시 DB 레벨 최종 차단
```

각 시나리오는 어느 방어선이 동작하는지 명시한다.

### 동시성 테스트의 핵심 도구

| 도구 | 역할 |
|------|------|
| `CyclicBarrier(N)` | N개 스레드를 출발 지점에 대기시켜 진짜 동시 실행 보장 |
| `CountDownLatch(N)` | 모든 스레드 완료까지 메인 스레드 대기 |
| `ExecutorService` | 스레드 풀 관리 |
| `AtomicInteger` | 성공/실패 카운터 (스레드 안전) |
| `Collections.synchronizedList` | 예외 수집 (스레드 안전) |

> **중요**: `CyclicBarrier` 없이 `ExecutorService.submit()`만 쓰면 스레드가 순차적으로
> 시작되어 진짜 Race Condition이 재현되지 않는다.
> 모든 스레드는 `barrier.await()` 를 서비스 호출 직전에 두어야 한다.

---

## 시나리오 1. 동시 출금 — 잔액 초과 요청 (핵심 Race Condition)

### 1. 테스트 목적

잔액보다 더 많은 총액의 출금 요청이 동시에 들어왔을 때,
**비관적 락이 직렬화를 강제**해 잔액이 절대 음수가 되지 않음을 검증한다.

이것이 이 시스템에서 가장 중요한 동시성 테스트다.
비관적 락이 없다면 모든 스레드가 동시에 잔액을 확인하고 동시에 차감해
잔액이 음수가 되는 **Lost Update** 문제가 발생한다.

**작동 방어선**: 1단계(비관적 락) + 2단계(도메인 검증)

### 2. 사전 데이터 조건

| 항목 | 값 |
|------|----|
| 지갑 | walletId = 1, balance = 10,000 |
| 스레드 수 | 20개 |
| 요청당 출금액 | 1,000원 |
| 총 요청 금액 | 20,000원 (잔액의 2배) |
| 멱등 키 | 스레드마다 고유 UUID (각자 다른 키) |

### 3. 동시 실행 방식

```
CyclicBarrier(20) 로 20개 스레드 동시 출발
각 스레드: barrier.await() → withdraw(walletId=1, amount=1000, key=UUID.random())
ExecutorService(20 스레드) 로 병렬 실행
CountDownLatch(20) 로 전체 완료 대기
```

### 4. 테스트 단계

```
Step 1. 지갑 balance = 10,000 초기화

Step 2. 20개 스레드 준비
        각 스레드는 고유 멱등 키를 미리 할당받음

Step 3. CyclicBarrier(20) → 동시 출발

Step 4. 각 스레드 결과 수집
        - 성공 (WalletTransaction 반환): successCount++
        - INSUFFICIENT_BALANCE 예외: failCount++
        - 그 외 예외: unexpectedErrors에 추가

Step 5. CountDownLatch.await() (타임아웃: 30초)

Step 6. DB에서 최종 상태 조회
        - wallets 테이블 balance
        - transactions 테이블 COMPLETED 건수
        - ledger_entries 테이블 DEBIT 건수
```

### 5. 검증 포인트

```
[잔액 정합성 — 최우선]
- 최종 balance >= 0              (음수 절대 불가)
- 최종 balance == 0              (10,000원 잔액, 1,000원씩 = 정확히 10건 성공 가능)

[성공/실패 건수 정합성]
- successCount == 10             (잔액 범위 내 최대 성공 수)
- failCount == 10                (잔액 초과분은 모두 실패)
- successCount + failCount == 20 (예외 없이 모두 처리됨)
- unexpectedErrors.isEmpty()     (INSUFFICIENT_BALANCE 외 다른 예외 없음)

[원장-잔액 일관성]
- ledger_entries DEBIT 건수 == successCount
- transactions COMPLETED 건수 == successCount
- balance == 10,000 - (successCount × 1,000)

[비즈니스 불변식]
- 어떠한 순간에도 balance < 0 이 아님
  (중간 상태 확인은 실용적으로 불가하므로 최종 상태로 대체 검증)
```

### 6. 구현 시 주의점

- **H2 사용 금지**: H2는 `SELECT FOR UPDATE`를 일부만 지원한다.
  비관적 락의 직렬화 효과가 실제 PostgreSQL과 다르게 동작할 수 있다.
  반드시 **Testcontainers + PostgreSQL 15+** 를 사용한다.

- **멀티스레드에서 `@Transactional` 롤백 불가**: `@SpringBootTest` 의
  `@Transactional` 은 단일 스레드에서만 롤백된다.
  테스트 후 `@AfterEach` 에서 `DELETE FROM ledger_entries`, `DELETE FROM transactions`,
  `UPDATE wallets SET balance = 0` 순서로 명시적 정리가 필요하다.

- **스레드 수와 스레드 풀 크기 일치**: `Executors.newFixedThreadPool(20)` 으로
  스레드 수와 풀 크기를 동일하게 설정해야 모든 요청이 즉시 처리된다.
  풀이 작으면 요청이 순차화되어 Race Condition이 재현되지 않는다.

- **`BigDecimal` 비교**: `balance == 0` 검증 시 `compareTo(BigDecimal.ZERO) == 0` 사용.
  `equals(BigDecimal.ZERO)` 는 스케일 차이로 오탐 가능.

- **PostgreSQL lock_timeout 설정**: 락 대기 시간이 무한히 길어지는 것을 막기 위해
  `SET lock_timeout = '5s'` 설정 권장. 타임아웃 발생 시 테스트가 명확히 실패한다.

### 7. 면접에서 왜 중요한가

> "동시에 같은 지갑에서 출금 요청이 들어오면 잔액이 음수가 될 수 있지 않나요?
> 어떻게 막았나요? 실제로 테스트해봤나요?"

이 테스트가 그 질문에 대한 **실행 가능한 증거**다.
"비관적 락을 씁니다"라는 말 한 마디보다 "이 테스트가 통과합니다"를 보여주는 것이
면접에서 완전히 다른 신뢰도를 만든다.

비관적 락이 없을 경우 20개 스레드 모두 잔액 10,000원을 동시에 읽고
동시에 차감하면 `balance = 10,000 - 20,000 = -10,000` 이 될 수 있다는
**Lost Update** 문제를 먼저 설명한 뒤, 이 테스트로 해결을 증명하는 흐름이 효과적이다.

---

## 시나리오 2. 동시 출금 — 잔액 정확 소진 (Edge Case)

### 1. 테스트 목적

요청 건수와 잔액이 **정확히 일치**하는 상황에서 모든 요청이 성공하고
최종 잔액이 정확히 0이 되는지 검증한다.
이 케이스는 "한 건이라도 잘못되면 잔액이 남거나, 음수가 된다"는 민감한 경계다.

### 2. 사전 데이터 조건

| 항목 | 값 |
|------|----|
| 지갑 | walletId = 1, balance = 10,000 |
| 스레드 수 | 10개 |
| 요청당 출금액 | 1,000원 |
| 총 요청 금액 | 10,000원 (잔액과 동일) |

### 3. 동시 실행 방식

```
CyclicBarrier(10) → 10개 스레드 동시 출발
각 스레드: withdraw(walletId=1, amount=1000, key=UUID.random())
```

### 4. 테스트 단계

```
Step 1. balance = 10,000 초기화
Step 2. 10개 스레드 동시 출발
Step 3. 결과 수집
Step 4. DB 최종 상태 조회
```

### 5. 검증 포인트

```
[잔액]
- 최종 balance == 0 (정확히 소진)
- balance != 음수 (초과 차감 없음)

[건수]
- successCount == 10 (모두 성공)
- failCount == 0 (잔액 부족 없음)
- unexpectedErrors.isEmpty()

[원장]
- DEBIT 레코드 수 == 10
- 각 ledger_entry 의 balance_after 는 9000, 8000, ..., 0 으로
  반드시 순서대로일 필요는 없지만 전체 집합은 이 값들로 구성되어야 함
```

### 6. 구현 시 주의점

- 이 시나리오는 시나리오 1보다 **결과 예측이 확정적**이다.
  10개 요청, 10,000원 잔액 → 반드시 모두 성공이어야 한다.
  실패가 발생하면 락 구현에 버그가 있거나 타임아웃 설정 문제다.
- `balance_after` 필드의 집합 검증은 선택적 심화 검증이다.
  실제 순서는 락 획득 순서(비결정적)에 따라 달라진다.

### 7. 면접에서 왜 중요한가

> "잔액이 딱 맞는 상황에서 동시 요청이 오면 어떻게 되나요?"

경계값 테스트는 면접관에게 "구현을 실제로 생각해봤다"는 인상을 준다.
시나리오 1의 "일부 실패"와 이 시나리오의 "모두 성공"을 대조해서 설명하면
비관적 락의 직렬화 효과를 더 명확히 전달할 수 있다.

---

## 시나리오 3. 동시 입금 + 출금 혼합 — 잔액 수렴 검증

### 1. 테스트 목적

입금과 출금 요청이 **동시에 섞여서** 들어올 때 최종 잔액이
성공한 입금 합계 - 성공한 출금 합계와 정확히 일치하는지 검증한다.

실무에서는 입금과 출금이 동시에 발생한다. 이 혼합 상황에서도
잔액 정합성이 유지되는지 확인하는 시나리오다.

### 2. 사전 데이터 조건

| 항목 | 값 |
|------|----|
| 지갑 | walletId = 1, balance = 5,000 |
| 입금 스레드 수 | 10개, 각 1,000원 |
| 출금 스레드 수 | 10개, 각 2,000원 |
| 총 스레드 수 | 20개 |

> 출금 총액(20,000)이 초기 잔액(5,000)보다 크므로 일부 출금은 반드시 실패한다.

### 3. 동시 실행 방식

```
CyclicBarrier(20) → 입금 10개 + 출금 10개 동시 출발
입금 스레드: deposit(walletId=1, amount=1000, key=UUID.random())
출금 스레드: withdraw(walletId=1, amount=2000, key=UUID.random())
```

### 4. 테스트 단계

```
Step 1. balance = 5,000 초기화
Step 2. 20개 스레드 동시 출발 (입금 10 + 출금 10)
Step 3. 결과별 수집
        - depositSuccessCount (입금 성공)
        - withdrawSuccessCount (출금 성공)
        - withdrawFailCount (잔액 부족으로 실패)
Step 4. DB 최종 balance 조회
```

### 5. 검증 포인트

```
[최종 잔액 수렴 검증 — 핵심]
- 최종 balance ==
    5,000
    + (depositSuccessCount × 1,000)
    - (withdrawSuccessCount × 2,000)

[음수 방지]
- 최종 balance >= 0

[예외 분류]
- 모든 예외는 WalletBusinessException(INSUFFICIENT_BALANCE) 이어야 함
- 그 외 예외 없음

[원장 건수 일치]
- CREDIT 레코드 수 == depositSuccessCount
- DEBIT 레코드 수 == withdrawSuccessCount
```

### 6. 구현 시 주의점

- 최종 잔액이 결정론적(deterministic)이지 않다.
  락 획득 순서에 따라 입금이 먼저 쌓인 뒤 출금이 성공할 수도 있고,
  반대로 초기 잔액이 소진된 뒤 입금이 들어올 수도 있다.
  따라서 "balance == X" 로 고정 검증하지 않고,
  `balance == 5000 + 입금합계 - 출금합계` 공식으로 검증한다.

### 7. 면접에서 왜 중요한가

> "실제 서비스에서는 입금과 출금이 동시에 발생하는데, 그 상황도 처리 가능한가요?"

단일 방향(출금만) 테스트보다 현실적인 시나리오다.
"잔액이 특정 값으로 수렴한다"는 수렴 검증 패턴은
비결정적(non-deterministic) 동시성 환경에서 정합성을 검증하는
올바른 방법임을 설명할 수 있는 포인트다.

---

## 시나리오 4. 양방향 동시 이체 — 데드락 부재 검증

### 1. 테스트 목적

A → B 이체와 B → A 이체가 **동시에** 실행될 때 데드락이 발생하지 않고
두 요청 모두 타임아웃 없이 완료되는지 검증한다.

이것은 이 시스템에서 **설계 의도가 가장 명확하게 드러나는 테스트**다.
`Math.min/max` 로 락 순서를 고정하지 않으면 교차 요청에서 데드락이 발생한다.

**작동 방어선**: `Math.min(fromId, toId)` → `Math.max(fromId, toId)` 순 락 획득

### 2. 사전 데이터 조건

| 항목 | 값 |
|------|----|
| 지갑 A | walletId = 1, balance = 10,000 |
| 지갑 B | walletId = 2, balance = 10,000 |

### 3. 동시 실행 방식

```
CyclicBarrier(2) → 두 스레드 동시 출발

스레드 1: transfer(from=1, to=2, amount=3,000, key="key-1to2")
스레드 2: transfer(from=2, to=1, amount=5,000, key="key-2to1")
```

> 이 케이스가 데드락 위험의 핵심이다.
> 스레드 1은 wallet#1 락 → wallet#2 락 순서를 원한다.
> 스레드 2는 wallet#2 락 → wallet#1 락 순서를 원한다.
> 락 순서 고정 없이는 교착 상태가 발생한다.
>
> 현재 구현에서는:
> 스레드 1: min(1,2)=1 → max(1,2)=2 순서로 락
> 스레드 2: min(2,1)=1 → max(2,1)=2 순서로 락 (동일 순서)
> → 항상 wallet#1 을 먼저 잠근다 → 데드락 불가

### 4. 테스트 단계

```
Step 1. 지갑 1 balance = 10,000, 지갑 2 balance = 10,000 초기화

Step 2. CyclicBarrier(2) → 두 스레드 동시 출발

Step 3. 두 스레드 모두 완료 대기 (타임아웃: 10초)

Step 4. 두 스레드 결과 확인
        - 예외 발생 여부
        - 타임아웃 여부

Step 5. 최종 잔액 검증
```

### 5. 검증 포인트

```
[데드락 부재 — 핵심]
- CountDownLatch.await(10, SECONDS) 이 타임아웃 없이 완료됨
- 두 스레드 모두 정상 응답 수신 (예외 없음)

[잔액 정합성]
- 지갑 1 최종 balance == 10,000 - 3,000 + 5,000 == 12,000
- 지갑 2 최종 balance == 10,000 + 3,000 - 5,000 == 8,000

[시스템 전체 불변식]
- 지갑 1 balance + 지갑 2 balance == 20,000 (이체 전후 합계 동일)

[원장]
- 각 이체별 DEBIT 1건 + CREDIT 1건 = 총 4건의 ledger_entries
```

### 6. 구현 시 주의점

- 타임아웃은 데드락 감지의 핵심 수단이다.
  `CountDownLatch.await(10, TimeUnit.SECONDS)` 에서 `false` 를 반환하면
  데드락 또는 과도한 지연이 발생한 것이다.
  이 경우 테스트를 명시적으로 실패 처리해야 한다.

- PostgreSQL은 기본적으로 **deadlock_timeout(1초)** 이후 데드락을 감지하고
  한 쪽 트랜잭션을 롤백한다. 락 순서 고정이 올바르게 구현되어 있다면
  데드락이 발생하지 않으므로 이 타임아웃이 동작하지 않아야 한다.
  만약 `ERROR: deadlock detected` 로그가 발생하면 락 순서 구현에 버그가 있는 것이다.

- 잔액 검증이 정확히 맞아야 한다. 수치가 다르다면 이체 로직 자체의 버그다.

### 7. 면접에서 왜 중요한지

> "이체에서 데드락이 어떻게 발생하나요? 어떻게 해결했나요?"

이 질문은 금융 시스템 면접에서 자주 나온다.
현재 구현의 `Math.min/max` 락 순서 고정 전략을 설명한 뒤,
"이 테스트가 데드락 없이 완료됨을 10초 타임아웃으로 보장합니다"라고 말하면
이론 + 증거를 모두 갖춘 답변이 된다.

---

## 시나리오 5. 다중 지갑 동시 이체 — 시스템 전체 잔액 불변식

### 1. 테스트 목적

여러 지갑 사이에서 다양한 방향의 이체가 **동시에** 발생할 때,
시스템 전체의 **잔액 합계가 변하지 않는다**는 불변식을 검증한다.
이체는 돈을 생성하거나 소멸시키지 않고 이동만 시키므로 합계는 항상 동일해야 한다.

### 2. 사전 데이터 조건

| 항목 | 값 |
|------|----|
| 지갑 수 | 4개 (walletId = 1, 2, 3, 4) |
| 초기 잔액 | 각 10,000원 (합계 40,000원) |
| 이체 스레드 수 | 8개 |

이체 방향 (예시):
```
스레드 1: wallet1 → wallet2, 1,000원
스레드 2: wallet2 → wallet3, 2,000원
스레드 3: wallet3 → wallet4, 3,000원
스레드 4: wallet4 → wallet1, 1,500원
스레드 5: wallet1 → wallet3, 500원
스레드 6: wallet2 → wallet4, 800원
스레드 7: wallet3 → wallet1, 1,200원
스레드 8: wallet4 → wallet2, 700원
```

### 3. 동시 실행 방식

```
CyclicBarrier(8) → 8개 스레드 동시 출발
각 스레드: 서로 다른 방향의 transfer 호출
```

### 4. 테스트 단계

```
Step 1. 4개 지갑 각 10,000원 초기화
Step 2. 8개 스레드 동시 출발
Step 3. 전체 완료 대기
Step 4. 4개 지갑 잔액 합계 계산
```

### 5. 검증 포인트

```
[시스템 전체 불변식 — 핵심]
- wallet1.balance + wallet2.balance + wallet3.balance + wallet4.balance == 40,000
  (이체 성공/실패 무관하게 합계는 항상 40,000)

[개별 잔액 음수 방지]
- 모든 지갑의 balance >= 0

[원장 정합성]
- 성공한 이체 수 == COMPLETED transactions 수
- 성공한 이체마다 DEBIT 1건 + CREDIT 1건 이므로
  DEBIT 수 == CREDIT 수 == 성공한 이체 수
```

### 6. 구현 시 주의점

- 이 시나리오에서 일부 이체는 잔액 부족으로 실패할 수 있다.
  핵심 검증은 "성공한 이체와 실패한 이체를 합산해도 전체 잔액이 변하지 않는다"이다.
- 합계 검증은 `walletRepository.findAll()` 로 4개 지갑을 조회한 뒤
  `stream().map(Wallet::getBalance).reduce(BigDecimal.ZERO, BigDecimal::add)` 로 계산한다.

### 7. 면접에서 왜 중요한가

> "이체 과정에서 돈이 사라지거나 두 배로 늘어나지 않는다는 걸 어떻게 증명하나요?"

**Double-Entry Ledger 의 핵심 불변식**이 "합계는 변하지 않는다"이다.
이 테스트는 그 불변식을 시스템 수준에서 검증한다.
단순한 잔액 정합성을 넘어서 금융 시스템의 근본 원칙을 이해하고
테스트로 증명한다는 것을 보여주는 강력한 포인트다.

---

## 시나리오 6. 성공 건수 — 원장 레코드 수 일치 검증

### 1. 테스트 목적

동시 처리 후 **성공한 거래 수**와 **DB에 실제로 기록된 원장 수**가
정확히 일치하는지 검증한다. 비관적 락으로 동시성은 제어하지만,
예외 처리나 트랜잭션 롤백 과정에서 원장만 기록되거나 거래만 기록되는
**부분 커밋(Partial Commit)** 이 없음을 확인한다.

### 2. 사전 데이터 조건

| 항목 | 값 |
|------|----|
| 지갑 | walletId = 1, balance = 5,000 |
| 스레드 수 | 15개 |
| 요청당 출금액 | 1,000원 |
| 예상 성공 | 5건 (잔액 내) |
| 예상 실패 | 10건 (잔액 초과) |

### 3. 동시 실행 방식

```
CyclicBarrier(15) → 15개 스레드 동시 출발
각 스레드: withdraw(walletId=1, amount=1000, key=UUID.random())
```

### 4. 테스트 단계

```
Step 1. balance = 5,000 초기화
Step 2. 15개 스레드 동시 출발
Step 3. successCount 집계
Step 4. DB 레코드 직접 카운트
```

### 5. 검증 포인트

```
[거래-원장 1:1 대응]
- transactions WHERE status = 'COMPLETED' 건수 == successCount
- ledger_entries WHERE type = 'DEBIT' 건수 == successCount

[부분 커밋 없음]
- transactions WHERE status = 'PENDING' 건수 == 0
  (PENDING 상태로 남은 거래가 없어야 함)
  (실패한 요청은 @Transactional 롤백으로 PENDING도 남지 않아야 함)

[잔액 역산 일치]
- 5,000 - (successCount × 1,000) == 최종 balance
```

### 6. 구현 시 주의점

- `transactions WHERE status = 'PENDING' == 0` 검증이 핵심이다.
  실패한 요청에서 `saveStartedTransaction()` 의 PENDING 삽입이
  `@Transactional` 롤백으로 함께 사라졌는지 확인한다.
  PENDING 레코드가 남아 있다면 트랜잭션 경계 설계에 버그가 있는 것이다.

### 7. 면접에서 왜 중요한가

> "`@Transactional` 이 실제로 원자적으로 동작하는지 어떻게 확인했나요?"

이 테스트가 그 답이다. "PENDING 이 하나도 남지 않는다"는 검증이
`@Transactional` 의 전체-커밋/전체-롤백 동작을 실제로 확인한 증거가 된다.

---

## 시나리오 7. 비관적 락 제거 가상 시나리오 — Lost Update 재현 (설명용)

> **주의**: 이 시나리오는 **실제로 구현하거나 실행하지 않는다**.
> 면접에서 "비관적 락이 왜 필요한가"를 설명하기 위한 **대조 설명용** 시나리오다.

### 1. 목적

락이 없을 경우 어떤 문제가 발생하는지 이론적으로 설명한다.

### 2. 락 없을 때 발생하는 문제 흐름

```
초기 balance = 10,000원

스레드 A: SELECT balance → 10,000원 확인
스레드 B: SELECT balance → 10,000원 확인  ← 동시에 읽음

스레드 A: 10,000 - 8,000 = 2,000원 → UPDATE balance = 2,000
스레드 B: 10,000 - 8,000 = 2,000원 → UPDATE balance = 2,000  ← A의 변경을 덮어씀

최종 balance = 2,000원  (올바른 값은 -6,000원이 되어야 하지만
                          도메인 검증이 통과했으므로 실제 차감이 이루어짐)
```

실제로는 스레드 A가 `2,000 < 8,000` 을 검사하고 예외를 던져야 하지만,
두 스레드 모두 `10,000 >= 8,000` 조건을 통과하면 두 번 차감이 발생한다.

### 3. 면접에서 설명 방법

```
1. "비관적 락 없이는 이런 Lost Update 가 발생합니다" (위 흐름 설명)
2. "현재 구현은 findByIdForUpdate() 로 SELECT FOR UPDATE 를 걸어
   한 번에 하나의 스레드만 잔액을 읽고 수정할 수 있습니다"
3. "시나리오 1 테스트가 이를 실제로 검증합니다"
```

이 설명 흐름이 면접관에게 **문제 인식 → 설계 의도 → 검증 방법** 의 논리를 완성한다.

---

## 테스트 환경 구성 요약

### 필수 환경

```
- Testcontainers PostgreSQL 15+
  (H2 사용 시 SELECT FOR UPDATE 동작이 달라 테스트 신뢰도 없음)

- spring.datasource.hikari.maximum-pool-size >= 스레드 수 + 2
  (커넥션 풀이 스레드 수보다 작으면 모든 스레드가 동시에 실행되지 않음)

- PostgreSQL lock_timeout = '5s'
  (무한 락 대기 방지, 이 이상 대기하면 테스트 실패로 명확히 처리)
```

### 테스트 데이터 격리

```
- @AfterEach 에서 명시적 DELETE/UPDATE
  (멀티스레드 테스트에서 @Transactional 롤백 불동작)

DELETE 순서:
  1. DELETE FROM ledger_entries   (FK: wallet_id, transaction_id)
  2. DELETE FROM transactions     (FK: 없음)
  3. UPDATE wallets SET balance = X  (초기화)
```

### 시나리오 우선순위

| 우선순위 | 시나리오 | 이유 |
|----------|----------|------|
| 1 | 시나리오 1 (동시 출금 초과) | 핵심 Race Condition, 면접 필수 증거 |
| 2 | 시나리오 4 (양방향 이체) | 데드락 방지 설계 증명 |
| 3 | 시나리오 5 (전체 잔액 불변) | Double-Entry 불변식 검증 |
| 4 | 시나리오 6 (원장 건수 일치) | 부분 커밋 없음 증명 |
| 5 | 시나리오 2 (정확 소진) | 경계값 케이스 |
| 6 | 시나리오 3 (혼합 요청) | 현실적인 복합 시나리오 |

---

## 면접 설명 흐름 가이드

### 1분 설명 구조

```
[문제 제시]
"출금 API에 동시 요청이 들어오면 잔액 정합성이 깨질 수 있습니다.
 두 스레드가 동시에 잔액을 읽으면 둘 다 '잔액 충분'으로 판단해
 이중으로 차감하는 Lost Update가 발생합니다."

[해결 방법]
"비관적 락(SELECT FOR UPDATE)으로 지갑 행을 잠가
 한 번에 하나의 스레드만 처리하도록 직렬화했습니다.
 이체는 두 지갑에 락을 걸어야 하므로, 데드락 방지를 위해
 항상 walletId가 작은 것부터 잠그는 순서를 고정했습니다."

[검증 방법]
"CyclicBarrier로 100개 스레드를 동시에 출발시켜
 잔액이 음수가 되지 않고 성공/실패 건수가 비즈니스 규칙에 맞는지
 Testcontainers + 실제 PostgreSQL로 검증했습니다."
```

---

*문서 최종 수정: 2026-03-06*
*작성자: Test Agent (Claude Code)*
