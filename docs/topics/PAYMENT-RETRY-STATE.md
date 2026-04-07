# 결제 재시도 상태 전략 설계

> 최종 수정: 2026-04-07

---

## 문제 정의

현재 재시도 관련 코드에 세 가지 구조적 불명확함이 있다.

### 1. PaymentEvent 상태 — 재시도 중을 구분할 수 없음

`RETRYING` 상태가 없어서 "첫 시도 전"과 "재시도 대기 중"이 모두 `IN_PROGRESS`로 표현된다.
`PaymentEvent.retryCount` 필드는 존재하지만 실제로 사용되지 않는다.

```
현재: READY → IN_PROGRESS(재시도 0회) ··· IN_PROGRESS(재시도 4회) → DONE / FAILED
           (외부에서 재시도 진행 여부를 알 수 없음)
```

### 2. 재시도 정책 — 하드코딩, Backoff 없음

`RETRYABLE_LIMIT = 5`가 `PaymentEvent`와 `PaymentOutbox` 양쪽에 중복 하드코딩되어 있다.
폴링 간격(5초)도 고정이라 일시적 장애 시 게이트웨이에 부하가 집중될 수 있다.
`PaymentOutbox`에 `nextRetryAt` 개념이 없어 FIXED 이외의 Backoff 전략을 지원할 수 없다.

### 3. 실패 분류 — 예외 타입으로 인코딩되어 흐름이 불명확

`PaymentConfirmResult`(domain dto)에 `RETRYABLE_FAILURE | NON_RETRYABLE_FAILURE` 분류가 이미 있음에도
`PaymentCommandUseCase`가 이를 `PaymentTossRetryableException` / `PaymentTossNonRetryableException`으로 변환하여 던지고,
`OutboxProcessingService`가 catch로 분기하는 구조다.
도메인 결과값이 있음에도 예외 타입이 분류 기준 역할을 하고 있어 흐름을 파악하기 어렵다.

---

## 상태 머신 다이어그램

### PaymentEvent 상태 (신규 설계)

```mermaid
stateDiagram-v2
    [*] --> READY : checkout 완료

    READY --> IN_PROGRESS : execute()\nconfirm 요청 수신
    READY --> EXPIRED : expire()\n만료 스케줄러(30분)

    IN_PROGRESS --> DONE : done()\nToss 성공
    IN_PROGRESS --> FAILED : fail()\nnon-retryable 오류\n또는 재고 부족
    IN_PROGRESS --> RETRYING : toRetrying()\nretryable 오류 (첫 실패)

    RETRYING --> DONE : done()\n재시도 성공
    RETRYING --> FAILED : fail()\nnon-retryable 오류\n또는 재시도 소진
    RETRYING --> RETRYING : toRetrying()\n재시도 또 실패 (n회차)

    DONE --> [*]
    FAILED --> [*]
    EXPIRED --> [*]
```

> **guard 업데이트 필요:**
> - `done()`: 현재 `IN_PROGRESS || DONE` → `IN_PROGRESS || RETRYING || DONE` 으로 확장
> - `fail()`: 현재 `READY || IN_PROGRESS` → `READY || IN_PROGRESS || RETRYING` 으로 확장
> - `toRetrying()`: 신규 메서드, `IN_PROGRESS || RETRYING` 허용

### PaymentOutbox 상태

```mermaid
stateDiagram-v2
    [*] --> PENDING : createPending()\nconfirm TX 내 생성

    PENDING --> IN_FLIGHT : claimToInFlight()\natomic UPDATE\n(nextRetryAt 조건 포함)

    IN_FLIGHT --> DONE : toDone()\nToss 성공 완료 TX
    IN_FLIGHT --> FAILED : toFailed()\n보상 TX (non-retryable\n또는 재시도 소진)
    IN_FLIGHT --> PENDING : incrementRetryCount(RetryPolicy)\nretryable 실패 또는 timeout 복구\n→ nextRetryAt 설정

    DONE --> [*]
    FAILED --> [*]
```

---

## 전체 처리 흐름

```mermaid
flowchart TD
    A[POST /confirm] --> B["[TX]\nREADY→IN_PROGRESS\n재고 감소\nOutbox PENDING 생성"]
    B --> C[202 Accepted]
    B --> D[AFTER_COMMIT\nchannel.offer]

    D --> E{채널 오퍼 성공?}
    E -- "true\n정상 경로" --> F[ImmediateWorker\nchannel.take]
    E -- "false\n큐 가득 참" --> G[OutboxWorker\n폴링 2초 주기 처리]

    F --> H[process orderId]
    G --> H

    H --> I["claimToInFlight\nWHERE status=PENDING\nAND nextRetryAt IS NULL\nOR nextRetryAt ≤ NOW\natomic UPDATE → IN_FLIGHT"]
    I -- "클레임 실패\n이미 처리 중" --> Z[return]
    I -- "클레임 성공" --> J[PaymentEvent 조회]
    J -- "조회 실패" --> K["incrementRetryOrFail\n→ PENDING 복원\nnextRetryAt 설정"]
    J --> L["Toss API 호출\n트랜잭션 밖"]

    L -- SUCCESS --> M["[TX] 성공 완료\nOutbox IN_FLIGHT→DONE\nPaymentEvent →DONE"]
    L -- NON_RETRYABLE --> N["[TX] 보상\nOutbox IN_FLIGHT→FAILED\n재고 복원\nPaymentEvent →FAILED"]
    L -- RETRYABLE --> O{isExhausted?}

    O -- NO --> P["[TX]\nOutbox IN_FLIGHT→PENDING\nnextRetryAt = now + backoff\nPaymentEvent →RETRYING\nretryCount++"]
    O -- YES --> Q["[TX] 보상\nOutbox IN_FLIGHT→FAILED\n재고 복원\nPaymentEvent →FAILED"]

    P --> R["다음 OutboxWorker\n폴링 주기 대기\nnextRetryAt 도달 후 재처리"]
```

---

## 재시도 한계치 도달 시 처리 흐름

두 가지 경로에서 소진에 도달할 수 있다.

### 경로 1: 일반 retryable 실패 소진

```mermaid
flowchart TD
    A["Toss API → RETRYABLE_FAILURE"] --> B["incrementRetryOrFail\nretryCount 현재 값 확인"]
    B --> C{"isExhausted?\nretryCount >= maxAttempts"}
    C -- "NO (retryCount < maxAttempts)" --> D["Outbox IN_FLIGHT→PENDING\nnextRetryAt = now + backoff(retryCount)\nPaymentEvent →RETRYING\nretryCount++"]
    C -- "YES (한계 도달)" --> E["executePaymentFailureCompensationWithOutbox\n단일 TX"]
    E --> F["Outbox toFailed() → FAILED"]
    E --> G["increaseStockForOrders() → 재고 복원"]
    E --> H["markPaymentAsFail() → PaymentEvent FAILED\nstatusReason 저장"]
    D --> I["OutboxWorker 폴링 대기\nnextRetryAt 도달 후 재시도"]
```

### 경로 2: IN_FLIGHT timeout 복구 후 소진

```mermaid
flowchart TD
    A["OutboxWorker 실행\nStep 0: recoverTimedOutInFlightRecords"] --> B["IN_FLIGHT 레코드 중\ninFlightAt 기준 5분 초과 조회"]
    B --> C["outbox.incrementRetryCount(RetryPolicy)\nretryCount++\nstatus → PENDING\nnextRetryAt 설정"]
    C --> D["다음 폴링 주기"]
    D --> E["findPendingBatch\nnextRetryAt 조건 통과"]
    E --> F["claimToInFlight → IN_FLIGHT"]
    F --> G["Toss API 호출"]
    G -- "성공" --> H["Outbox→DONE\nPaymentEvent→DONE"]
    G -- "RETRYABLE + isExhausted" --> I["보상 TX\nOutbox→FAILED\n재고 복원\nPaymentEvent→FAILED"]
    G -- "NON_RETRYABLE" --> I
```

> **timeout 복구의 특성:**
> - `recoverTimedOutInFlightRecords`는 `isExhausted` 체크 없이 increment한다.
> - retryCount가 maxAttempts인 상태에서 timeout되면 retryCount가 maxAttempts+1이 되어 PENDING으로 복원된다.
> - 다음 `process()`에서 API 호출이 한 번 더 일어나고, 그 결과에 따라 소진 처리된다.
> - 즉 timeout 경로는 "한 번 더 시도 허용" 의미를 가진다. 이는 의도된 동작이다.

---

## 결정 사항

| 항목 | 결정 | 이유 |
|------|------|------|
| 재시도 중 PaymentEvent 상태 | `RETRYING` 상태 추가 + `retryCount` 실제 활용 | 외부에서 재시도 진행 여부 파악 가능 |
| 재시도 정책 관리 | `RetryPolicy` 도메인 객체 분리 + `application.yml` 설정 주입 | 정책 변경 시 코드 수정 없이 설정으로 제어 |
| Backoff 전략 | `BackoffType { FIXED, EXPONENTIAL }` + `PaymentOutbox.nextRetryAt` 추가 | FIXED/EXPONENTIAL 모두 지원, 폴링 쿼리로 시점 제어 |
| 실패 분류 기준 | 예외 변환 제거 — `PaymentConfirmResult` 직접 반환, caller가 `isRetryable()` 분기 | 도메인 결과값이 분류 기준이 되어 흐름 명확화 |
| 재시도 경로 | 재시도는 OutboxWorker 폴링 전용. ImmediateWorker는 첫 시도만 담당 | 재시도 시 채널에 재push하면 nextRetryAt 우회 위험 |
| claimToInFlight 조건 | `nextRetryAt IS NULL OR nextRetryAt <= NOW()` 추가 | findPendingBatch 필터만으로는 불완전, 직접 클레임 방어 필요 |
| 폴링 인덱스 | `(status, next_retry_at, created_at)` 복합 인덱스 신규 추가 | nextRetryAt 범위 조건 추가 시 성능 유지 |

---

## RetryPolicy 설계

### 도메인 객체

```java
// payment/domain/RetryPolicy.java
public record RetryPolicy(
    int maxAttempts,
    BackoffType backoffType,
    long baseDelayMs,
    long maxDelayMs
) {
    public boolean isExhausted(int retryCount) {
        return retryCount >= maxAttempts;
    }

    public Duration nextDelay(int retryCount) {
        return switch (backoffType) {
            case FIXED       -> Duration.ofMillis(baseDelayMs);
            case EXPONENTIAL -> Duration.ofMillis(
                Math.min(baseDelayMs * (1L << retryCount), maxDelayMs)
            );
        };
    }
}

// payment/domain/enums/BackoffType.java
public enum BackoffType { FIXED, EXPONENTIAL }
```

### 설정 주입

```yaml
# application.yml
payment:
  retry:
    max-attempts: 5
    backoff-type: FIXED         # FIXED | EXPONENTIAL
    base-delay-ms: 5000
    max-delay-ms: 60000
```

`RetryPolicyProperties` (`@ConfigurationProperties("payment.retry")`)를 `PaymentOutboxUseCase`에 주입하여 `RetryPolicy` 인스턴스를 생성한다.

---

## PaymentOutbox.nextRetryAt 설계

### 필드 추가

```java
// PaymentOutbox
private LocalDateTime nextRetryAt;  // null = 즉시 처리 가능

public void incrementRetryCount(RetryPolicy policy, LocalDateTime now) {
    this.retryCount++;
    this.status = PaymentOutboxStatus.PENDING;
    this.nextRetryAt = now.plus(policy.nextDelay(this.retryCount));
}
```

### 폴링 쿼리 변경

```sql
-- 기존
WHERE status = 'PENDING' ORDER BY created_at LIMIT :batchSize

-- 변경
WHERE status = 'PENDING'
  AND (next_retry_at IS NULL OR next_retry_at <= NOW())
ORDER BY created_at
LIMIT :batchSize
```

### claimToInFlight 쿼리 변경

```sql
-- 기존
UPDATE ... WHERE order_id = :orderId AND status = 'PENDING'

-- 변경
UPDATE ...
WHERE order_id = :orderId
  AND status = 'PENDING'
  AND (next_retry_at IS NULL OR next_retry_at <= NOW())
```

### 인덱스 변경

```sql
-- 기존
INDEX idx_payment_outbox_status_created (status, created_at)

-- 변경
INDEX idx_payment_outbox_status_retry_created (status, next_retry_at, created_at)
```

---

## 실패 분류 기준 — 예외 변환 제거

### 변경 전

```
PaymentGatewayPort.confirm() → PaymentConfirmResult(RETRYABLE_FAILURE)
  → PaymentCommandUseCase: throw PaymentTossRetryableException
  → OutboxProcessingService: catch PaymentTossRetryableException → 재시도
```

### 변경 후

```
PaymentGatewayPort.confirm() → PaymentConfirmResult(RETRYABLE_FAILURE)
  → PaymentCommandUseCase: return PaymentConfirmResult (예외 변환 없음)
  → OutboxProcessingService: result.isRetryable() → 재시도
```

**제거 대상:**
- `PaymentTossRetryableException`, `PaymentTossNonRetryableException`
- `PaymentCommandUseCase.confirmPaymentWithGateway()`의 예외 변환 switch

---

## 영향 범위

### 변경

| 파일 | 변경 내용 |
|------|----------|
| `payment/domain/enums/PaymentEventStatus` | `RETRYING` 추가 |
| `payment/domain/PaymentEvent` | `toRetrying()` 추가; `done()` / `fail()` guard에 `RETRYING` 포함; `retryCount` 활용; `RETRYABLE_LIMIT` 제거 (RetryPolicy로 이관) |
| `payment/domain/PaymentOutbox` | `nextRetryAt` 필드 추가; `incrementRetryCount(RetryPolicy, LocalDateTime)` 시그니처 변경; `RETRYABLE_LIMIT` 제거 |
| `payment/application/usecase/PaymentCommandUseCase` | `markPaymentAsRetrying()` 추가; `confirmPaymentWithGateway()` 예외 변환 제거 → `PaymentConfirmResult` 반환 |
| `payment/application/usecase/PaymentOutboxUseCase` | `RetryPolicy` 주입; `incrementRetryOrFail()` → `RetryPolicy.isExhausted()` 사용 + `PaymentEvent RETRYING` 전환 |
| `payment/application/usecase/PaymentTransactionCoordinator` | `executePaymentRetryWithOutbox()` 신규 트랜잭션 메서드 추가 (Outbox PENDING 복원 + PaymentEvent RETRYING) |
| `payment/infrastructure/entity/PaymentOutboxEntity` | `nextRetryAt` 컬럼 추가; 인덱스 변경 |
| `payment/infrastructure/repository/JpaPaymentOutboxRepository` | `findPendingBatch` / `claimToInFlight` 쿼리에 `nextRetryAt` 조건 추가 |
| `payment/scheduler/OutboxProcessingService` | 예외 catch 제거 → `PaymentConfirmResult.isRetryable()` 분기 |

### 신규

| 파일 | 내용 |
|------|------|
| `payment/domain/RetryPolicy` | 재시도 정책 도메인 record |
| `payment/domain/enums/BackoffType` | `FIXED`, `EXPONENTIAL` |
| `payment/infrastructure/config/RetryPolicyProperties` | `@ConfigurationProperties("payment.retry")` |
| DB migration | `payment_outbox.next_retry_at` 컬럼 추가; 인덱스 재생성 |

### 제거

| 파일 | 이유 |
|------|------|
| `payment/exception/PaymentTossRetryableException` | 예외 변환 제거로 불필요 |
| `payment/exception/PaymentTossNonRetryableException` | 동일 |

### 무관

- Checkout 흐름 (`READY → IN_PROGRESS`, 재고 감소)
- `executePaymentSuccessCompletionWithOutbox` 성공 경로
- `PaymentScheduler` 만료 처리
- `TossPaymentGatewayStrategy` 내부 오류 분류 로직
- Client API 계약

---

## 제외 범위

- **Dead Letter Queue / 수동 재처리**: 자동 재시도 전략에만 집중
- **재시도 알림 / 모니터링 대시보드**: 기존 메트릭 AOP 그대로 활용
- **클라이언트 응답에 RETRYING 노출 여부**: `PaymentStatusService` 응답 스펙 변경은 별도 논의
- **`recoverTimedOutInFlightRecords`의 즉시 보상**: timeout 경로는 "한 번 더 시도 허용" 의미를 유지하여 단순함 보존