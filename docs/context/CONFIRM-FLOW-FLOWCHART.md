# Confirm Flow Flowchart

> 기준: 실제 코드 (`PaymentConfirmServiceImpl`, `OutboxAsyncConfirmService`,
> `OutboxImmediateEventHandler`, `OutboxImmediateWorker`, `OutboxProcessingService`,
> `OutboxWorker`, `PaymentTransactionCoordinator`)
> 최종 수정: 2026-03-31

---

## 상태(Status) 사전

### PaymentEvent 상태 (`PaymentEventStatus`)

| 상태 | 의미 | 전환 시점 |
|------|------|-----------|
| `READY` | 결제 초기 생성 상태. 아직 처리 시작 전 | 결제 주문 생성 시 |
| `IN_PROGRESS` | Toss API confirm 요청을 위해 진입. paymentKey 기록 완료 | `executePayment()` 호출 시 |
| `DONE` | Toss API confirm 성공, 결제 완료 | `markPaymentAsDone()` 호출 시 |
| `FAILED` | 재고 부족 / Toss 비재시도 오류 / 보상 완료 후 최종 실패 | `markPaymentAsFail()` 호출 시 |
| `UNKNOWN` | Toss API에서 재시도 가능 오류 발생 — 결과 불확실 (Sync 전략 전용) | `markPaymentAsUnknown()` 호출 시 |

> **핵심**: `IN_PROGRESS` 상태에서 실패하면 반드시 `FAILED`로 전환해야 한다.
> 그렇지 않으면 PaymentEvent가 `IN_PROGRESS`에 고착되어 재시도도, 정상 조회도 불가능해진다.

---

### PaymentProcess 상태 (`PaymentProcessStatus`)

| 상태 | 의미 | 전환 시점 |
|------|------|-----------|
| `PROCESSING` | 결제 처리 진행 중 (재고 감소 완료, Toss 대기) | `createProcessingJob()` 호출 시 |
| `COMPLETED` | Toss confirm 성공 | `completeJob()` 호출 시 |
| `FAILED` | 보상 트랜잭션 완료 후 최종 실패 | `failJob()` 호출 시 |

> **주의**: `PaymentProcess`는 **Sync 전략에서만 생성**된다.
> Outbox는 `executePaymentSuccessCompletion()`에서 `existsByOrderId()` 가드로 `completeJob()` 호출을 건너뛴다.

---

### PaymentOutbox 상태 (`PaymentOutboxStatus`) — Outbox 전략 전용

| 상태 | 의미 | 전환 시점 |
|------|------|-----------|
| `PENDING` | 처리 대기 중. OutboxWorker가 배치로 조회할 대상 | `createPendingRecord()` 또는 재시도 후 `incrementRetryCount()` 시 |
| `IN_FLIGHT` | 핸들러/워커가 처리를 시작함. 타임아웃 복구 대상 | `claimToInFlight()` 호출 시 (REQUIRES_NEW 트랜잭션, 즉시 커밋) |
| `DONE` | Toss confirm 성공, 처리 완료 | `executePaymentSuccessCompletionWithOutbox()` 내 `outbox.toDone()` 시 |
| `FAILED` | 재시도 한도 초과 또는 비재시도 오류. 더 이상 처리 안 함 | `executePaymentFailureCompensationWithOutbox()` 내 `outbox.toFailed()` 또는 `incrementRetryOrFail()` 한도 초과 시 |

> **IN_FLIGHT 타임아웃**: `inFlightTimeoutMinutes`(기본 5분) 초과 시 `PENDING`으로 되돌려
> 워커 재시도 기회를 확보한다. 워커/핸들러 비정상 종료 시 데드락 방지 목적.
>
> **재시도 한도**: `RETRYABLE_LIMIT = 5`. `retryCount >= 5`이면 `PENDING`으로 돌리지 않고 `FAILED` 확정.

---

## 1. Sync (`PaymentConfirmServiceImpl`)

> `spring.payment.async-strategy=sync` (`matchIfMissing=false`, 기본값: `outbox`)

```mermaid
flowchart TD
    A([Controller\nPOST /confirm]) --> B

    B["① getPaymentEventByOrderId(orderId)\n⎿ PaymentEvent 조회 (상태: READY 예상)"]
    B --> LVAL

    LVAL["② validateLocalPaymentRequest()\n⎿ buyerId / amount / orderId / paymentKey 로컬 검증"]
    LVAL -->|"PaymentValidException\n불일치 → 즉시 throw (TX 진입 전)"| Z_FAIL([4xx throw])
    LVAL --> C

    C["③ executeStockDecreaseWithJobCreation()\n⎿ decreaseStockForOrders()  — 재고 감소\n⎿ createProcessingJob()     — PaymentProcess: PROCESSING 생성\n※ 단일 트랜잭션, rollbackFor=StockException"]
    C -->|"PaymentOrderedProductStockException\n(재고 부족)"| C_FAIL
    C_FAIL["handleStockFailure()\n⎿ markPaymentAsFail()  → PaymentEvent: FAILED"]
    C_FAIL --> Z_FAIL

    C --> D
    D["④ executePayment(paymentEvent, paymentKey)\n⎿ PaymentEvent: READY → IN_PROGRESS\n⎿ paymentKey DB 기록"]
    D -->|PaymentStatusException\n상태 전환 불가| D_FAIL
    D_FAIL["handleNonRetryableFailure()\n⎿ executePaymentFailureCompensation()\n  ├─ existsByOrderId() → true → failJob()  — PaymentProcess: FAILED\n  ├─ increaseStockForOrders()             — 재고 복원\n  └─ markPaymentAsFail()                 — PaymentEvent: FAILED"]
    D_FAIL --> Z_FAIL

    D --> F

    F["⑤ confirmPaymentWithGateway(command)\n⎿ Toss API: POST /v1/payments/confirm 호출"]
    F -->|PaymentTossRetryableException\nToss 일시 오류| F_R
    F_R["handleRetryableFailure()\n⎿ markPaymentAsUnknown()  → PaymentEvent: UNKNOWN\n  (결과 불확실 — 수동 확인 필요)"]
    F_R --> Z_FAIL

    F -->|"PaymentTossNonRetryableException\nUnknown Exception"| F_NR
    F_NR["handleNonRetryableFailure() / handleUnknownFailure()\n⎿ executePaymentFailureCompensation()\n  ├─ existsByOrderId() → true → failJob()  — PaymentProcess: FAILED\n  ├─ increaseStockForOrders()              — 재고 복원\n  └─ markPaymentAsFail()                  — PaymentEvent: FAILED"]
    F_NR --> Z_FAIL

    F --> G
    G["⑥ executePaymentSuccessCompletion()\n⎿ existsByOrderId() → true\n⎿ completeJob()       — PaymentProcess: COMPLETED\n⎿ markPaymentAsDone() — PaymentEvent: DONE"]
    G --> H([200 OK])
```

---

## 2. Outbox (`OutboxAsyncConfirmService` + `PaymentConfirmChannel` + `OutboxImmediateWorker` + `OutboxWorker`)

> `spring.payment.async-strategy=outbox` (기본값)
>
> **채널 기반 비동기 처리 + 폴백 이중 구조**:
> - 정상 경로: confirm() 커밋 후 `OutboxImmediateEventHandler`가 `channel.offer()` → `OutboxImmediateWorker` VT/PT 워커가 `channel.take()` → `OutboxProcessingService.process()`
> - 폴백 경로: 큐 오버플로우 시 `OutboxWorker` (fixedDelay 2s) 가 PENDING 레코드를 배치로 재처리 → `OutboxProcessingService.process()` 위임

### 2-1. confirm() — HTTP 요청 처리 (동기 구간)

```mermaid
flowchart TD
    A([Controller\nPOST /confirm]) --> B

    B["① getPaymentEventByOrderId(orderId)\n⎿ PaymentEvent 조회 (상태: READY 예상)"]
    B --> LVAL

    LVAL["② LVAL: command.amount == paymentEvent.totalAmount 검증\n※ TX 진입 전 로컬 금액 위변조 감지"]
    LVAL -->|PaymentValidException| Z_FAIL([4xx throw])
    LVAL --> C

    C["③ executePaymentAndStockDecreaseWithOutbox()\n⎿ executePayment()             — PaymentEvent: READY → IN_PROGRESS, paymentKey 기록\n⎿ decreaseStockForOrders()     — 재고 감소\n⎿ createPendingRecord(orderId) — PaymentOutbox: PENDING 생성\n※ 단일 트랜잭션(@Transactional), rollbackFor=StockException\n※ PaymentProcess 생성 없음"]
    C -->|PaymentOrderedProductStockException\n재고 부족| C_FAIL
    C_FAIL["트랜잭션 롤백 (PaymentEvent READY 복원, Outbox 롤백)\nhandleStockFailure()\n⎿ markPaymentAsFail()  → PaymentEvent: FAILED\n⎿ (PaymentOutbox는 롤백되어 생성 안 됨)"]
    C_FAIL --> Z_FAIL

    C --> P
    P["④ confirmPublisher.publish(orderId)\n⎿ OutboxImmediatePublisher\n⎿ ApplicationEventPublisher.publishEvent(PaymentConfirmEvent)\n⎿ 트랜잭션 커밋 후 AFTER_COMMIT 이벤트 큐잉"]
    P --> H([202 Accepted\n이후 처리는 OutboxImmediateEventHandler가 담당])
```

### 2-2. OutboxImmediateEventHandler.handle() — 채널 적재 (비블로킹)

```mermaid
flowchart TD
    EV(["@TransactionalEventListener(AFTER_COMMIT)\nTX 커밋 직후 HTTP 요청 스레드에서 실행"]) --> OFF

    OFF{"channel.offer(orderId)\n⎿ LinkedBlockingQueue non-blocking"}
    OFF -->|"true (적재 성공)"| OK([return\n→ OutboxImmediateWorker가 비동기 처리])
    OFF -->|"false (큐 가득 참)"| WARN["warn 로그 기록\n— PaymentConfirmChannel 오버플로우\n— OutboxWorker(polling)가 폴백 처리"]
    WARN --> END([return])
```

### 2-3. OutboxImmediateWorker.workerLoop() — 채널 소비 (VT/PT 워커)

```mermaid
flowchart TD
    SL(["SmartLifecycle.start()\n앱 시작 시 workerCount개(기본 200)\nVT 또는 PT 워커 스레드 생성"]) --> LOOP

    LOOP["workerLoop() — 각 워커 독립 실행"]
    LOOP --> TAKE

    TAKE["channel.take()\n⎿ 큐에 항목이 올 때까지 blocking wait"]
    TAKE --> PROC

    PROC["OutboxProcessingService.process(orderId)\n⎿ claimToInFlight → Toss API → success/retry/failure\n⎿ (아래 2-4 다이어그램 참조)"]
    PROC --> LOOP

    TAKE -->|InterruptedException| STOP([루프 종료\n→ SmartLifecycle.stop()])
```

### 2-4. OutboxProcessingService.process() — 공유 처리 로직

```mermaid
flowchart TD
    ENTRY(["OutboxImmediateWorker 또는 OutboxWorker에서 호출"]) --> C

    C["① claimToInFlight(orderId)\n⎿ atomic UPDATE WHERE status='PENDING' → IN_FLIGHT\n⎿ REQUIRES_NEW 트랜잭션 (즉시 커밋)\n  — 중복 처리 방지"]
    C -->|"Optional.empty() (클레임 실패)"| SKIP([return])
    C --> G

    G["② loadPaymentEvent(orderId)\n⎿ paymentKey는 confirm() executePayment()로 이미 기록됨"]
    G -->|조회 실패| G_FAIL
    G_FAIL["incrementRetryOrFail(orderId, outbox)\n→ retryCount 증가 또는 FAILED 확정"]
    G_FAIL --> Z_END([종료])
    G --> F

    F["③ confirmPaymentWithGateway()\n⎿ Toss API: POST /v1/payments/confirm 호출 (TX 밖)"]
    F -->|PaymentTossNonRetryableException\nToss 비재시도 오류| F_NR
    F_NR["executePaymentFailureCompensationWithOutbox()\n⎿ outbox.toFailed() + save()  — PaymentOutbox: FAILED\n⎿ increaseStockForOrders()    — 재고 복원\n⎿ markPaymentAsFail()         — PaymentEvent: FAILED\n※ 단일 트랜잭션"]
    F_NR --> Z_END

    F -->|PaymentTossRetryableException\nToss 일시 오류| F_R
    F_R{"incrementRetryOrFail()\n⎿ retryCount < 5?"}
    F_R -->|"Yes → PENDING 복귀"| RETRY(["다음 처리 사이클에서 재처리"])
    F_R -->|"No (한도 초과) → FAILED 확정"| Z_END

    F --> E
    E["④ executePaymentSuccessCompletionWithOutbox()\n⎿ outbox.toDone() + save()  — PaymentOutbox: IN_FLIGHT → DONE\n⎿ markPaymentAsDone()       — PaymentEvent: DONE\n※ 단일 트랜잭션"]
    E --> Z_OK([종료])
```

### 2-5. OutboxWorker.process() — 폴백 스케줄러

```mermaid
flowchart TD
    S(["@Scheduled fixedDelay\n기본 2000ms 간격\n주 역할: 큐 오버플로우 / 재시작 누락 레코드 재처리"]) --> R

    R["Step 0: recoverTimedOutInFlightRecords()\n⎿ IN_FLIGHT 상태인 레코드 중\n  inFlightAt 기준 N분(기본 5분) 초과 시\n  → PaymentOutbox: IN_FLIGHT → PENDING\n  (워커 비정상 종료 복구용)"]
    R --> P

    P["Step 1: findPendingBatch(batchSize)\n⎿ PaymentOutbox: PENDING 상태 배치 조회 (기본 50건)\n⎿ 정상 환경에서는 채널 처리로 PENDING 없음 → 바로 return\n⎿ parallel 모드 시 VT 병렬 처리"]
    P -->|배치 없음| SKIP([return])
    P --> PROC

    PROC["per record:\nOutboxProcessingService.process(outbox.getOrderId())\n⎿ (처리 로직은 2-4 다이어그램과 동일)"]
    PROC --> Z_END([종료])
```

---

## 3. 전략 비교

### 3-1. HTTP 응답 / 처리 흐름

```mermaid
flowchart LR
    subgraph Sync["Sync (동기)"]
        direction TB
        S1["로컬 검증\n(validateLocalPaymentRequest)"] --> S2["재고 감소\n+ PaymentProcess: PROCESSING"]
        S2 --> S3["Toss API 호출"]
        S3 --> S4["PaymentProcess: COMPLETED\nPaymentEvent: DONE"]
        S4 --> S5([200 OK])
    end

    subgraph Outbox["Outbox (비동기 — 채널 + 폴백)"]
        direction TB
        O1["LVAL 금액 검증"] --> O2["재고 감소\n+ PaymentOutbox: PENDING\n(executePayment 포함 단일 TX)"]
        O2 --> O3([202 Accepted])
        O3 -.->|"OutboxImmediateEventHandler\n(AFTER_COMMIT) → channel.offer\n→ OutboxImmediateWorker (VT/PT)"| O4["Toss API 호출\n(OutboxProcessingService)"]
        O3 -.->|"OutboxWorker (폴백)\n(fixedDelay 2s)"| O4
        O4 --> O5["PaymentEvent: DONE\nPaymentOutbox: DONE"]
    end
```

### 3-2. Outbox 보상 패턴 (`executePaymentFailureCompensationWithOutbox`)

```mermaid
flowchart TD
    A["executePaymentFailureCompensationWithOutbox(paymentEvent, orderList, reason, outbox)"] --> B
    B["outbox.toFailed() + paymentOutboxUseCase.save()\n→ PaymentOutbox: FAILED"]
    B --> C["increaseStockForOrders()\n→ 재고 복원"]
    C --> D["markPaymentAsFail()\n→ PaymentEvent: FAILED"]
```

### 3-3. 전략별 상태 엔티티 사용 요약

| 엔티티 | Sync | Outbox |
|------|------|--------|
| `PaymentEvent` | READY → IN_PROGRESS → DONE/FAILED/UNKNOWN | READY → IN_PROGRESS → DONE/FAILED |
| `PaymentProcess` | PROCESSING → COMPLETED/FAILED | 미사용 |
| `PaymentOutbox` | 미사용 | PENDING → IN_FLIGHT → DONE/FAILED |
| HTTP 응답 | 200 OK | 202 Accepted |
| Toss API 재시도 | 없음 (UNKNOWN 처리) | incrementRetryOrFail 최대 5회 |
| 재고 + executePayment TX | 분리 (별도 단계) | 단일 TX (Outbox 포함) |
| 즉시 처리 메커니즘 | 해당 없음 | OutboxImmediateEventHandler (AFTER_COMMIT) |
| 폴백 메커니즘 | 해당 없음 | OutboxWorker (fixedDelay 5s, 배치 50) |
