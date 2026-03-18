# PaymentConfirmService 구현체 비즈니스 로직 분석

> 최종 수정: 2026-03-18

---

## 1. Sync 플로우 (`PaymentConfirmServiceImpl`)

> `spring.payment.async-strategy=sync` (기본값, `matchIfMissing=true`)

```
[Controller] POST /confirm
      │
      ▼
① getPaymentEventByOrderId(orderId)
      │  PaymentEvent 조회 (상태: READY 예상)
      ▼
② executeStockDecreaseWithJobCreation(orderId, paymentOrderList)
      │  단일 트랜잭션 (rollbackFor=PaymentOrderedProductStockException)
      │  ┌─ decreaseStockForOrders()     — 재고 감소
      │  └─ createProcessingJob(orderId) — PaymentProcess: PROCESSING 생성
      │
      │  [실패] PaymentOrderedProductStockException (재고 부족)
      │    └─ handleStockFailure(paymentEvent, message)
      │         └─ markPaymentAsFail()   → PaymentEvent: FAILED
      │         └─ throw PaymentTossConfirmException (4xx)
      ▼
③ executePayment(paymentEvent, paymentKey)
      │  PaymentEvent: READY → IN_PROGRESS, paymentKey DB 기록
      │
      │  [실패] PaymentStatusException (상태 전환 불가)
      │    └─ handleNonRetryableFailure(paymentEvent, message)
      │         └─ executePaymentFailureCompensation()
      │              ├─ existsByOrderId() → true → failJob()  — PaymentProcess: FAILED
      │              ├─ increaseStockForOrders()              — 재고 복원
      │              └─ markPaymentAsFail()                   — PaymentEvent: FAILED
      │         └─ rethrow PaymentStatusException
      ▼
④ validateCompletionStatus(paymentEvent, command)
      │  금액(amount) 일치 검증, paymentKey 일치 검증
      │
      │  [실패] PaymentValidException / PaymentStatusException
      │    └─ handleNonRetryableFailure()  →  보상 트랜잭션 + FAILED
      ▼
⑤ confirmPaymentWithGateway(command)
      │  Toss API: POST /v1/payments/confirm 호출
      │
      │  [실패] PaymentTossRetryableException (Toss 일시 오류)
      │    └─ handleRetryableFailure(paymentEvent, message)
      │         └─ markPaymentAsUnknown() → PaymentEvent: UNKNOWN
      │         └─ throw PaymentTossConfirmException (4xx)
      │
      │  [실패] PaymentTossNonRetryableException (Toss 비재시도 오류)
      │    └─ handleNonRetryableFailure(paymentEvent, message)
      │         └─ executePaymentFailureCompensation()
      │              ├─ existsByOrderId() → true → failJob()
      │              ├─ increaseStockForOrders()
      │              └─ markPaymentAsFail()
      │         └─ throw PaymentTossConfirmException (4xx)
      │
      │  [실패] Unknown Exception
      │    └─ handleUnknownFailure(paymentEvent, message)
      │         └─ executePaymentFailureCompensation()
      │              ├─ existsByOrderId() → true → failJob()
      │              ├─ increaseStockForOrders()
      │              └─ markPaymentAsFail()
      │         └─ rethrow
      ▼
⑥ executePaymentSuccessCompletion(orderId, paymentEvent, approvedAt)
      │  단일 트랜잭션
      │  ├─ existsByOrderId() → true
      │  │   └─ completeJob(orderId)    — PaymentProcess: COMPLETED
      │  └─ markPaymentAsDone()         — PaymentEvent: DONE
      ▼
[Controller] 200 OK  (ResponseType.SYNC_200)
```

---

## 2. Outbox 플로우 (`OutboxAsyncConfirmService` + `OutboxWorker`)

> `spring.payment.async-strategy=outbox`

### 2-1. confirm() — HTTP 요청 처리

```
[Controller] POST /confirm
      │
      ▼
① getPaymentEventByOrderId(orderId)
      │  PaymentEvent 조회 (상태: READY 예상)
      ▼
② executePaymentAndStockDecreaseWithOutbox(paymentEvent, paymentKey, orderId, paymentOrderList)
      │  단일 트랜잭션 (rollbackFor=PaymentOrderedProductStockException)
      │  ┌─ executePayment()             — PaymentEvent: READY → IN_PROGRESS, paymentKey 기록
      │  ├─ decreaseStockForOrders()     — 재고 감소
      │  └─ createPendingRecord(orderId) — PaymentOutbox: PENDING 생성
      │
      │  [실패] PaymentOrderedProductStockException (재고 부족)
      │    → 트랜잭션 롤백 (PaymentEvent READY로 복원, Outbox 롤백)
      │    └─ handleStockFailure(paymentEvent, message)
      │         └─ markPaymentAsFail()   → PaymentEvent: FAILED
      │         └─ rethrow PaymentOrderedProductStockException (4xx)
      ▼
[Controller] 202 Accepted  (ResponseType.ASYNC_202)
      (이후 처리는 OutboxWorker가 비동기로 담당)
```

### 2-2. OutboxWorker.processRecord() — 백그라운드 처리

```
[@Scheduled fixedDelay — 기본 1000ms]
      │
      ▼
Step 0: recoverTimedOutInFlightRecords(inFlightTimeoutMinutes)
      │  IN_FLIGHT 상태 레코드 중 inFlightAt 기준 N분(기본 5분) 초과 시
      │  → PaymentOutbox: IN_FLIGHT → PENDING (워커 비정상 종료 복구)
      ▼
Step 1: findPendingBatch(batchSize)
      │  PaymentOutbox: PENDING 배치 조회 (기본 10건)
      │  parallel 모드 시 가상 스레드(Java 21)로 병렬 처리
      │
      │  [배치 없음] → return (다음 사이클 대기)
      ▼
Step 2: claimToInFlight(outbox)
      │  PaymentOutbox: PENDING → IN_FLIGHT, inFlightAt 기록
      │  REQUIRES_NEW 트랜잭션으로 즉시 커밋 — 다중 워커 인스턴스 중복 처리 방지
      │
      │  [클레임 실패 — 다른 워커가 먼저 처리] → return
      ▼
Step 3: getPaymentEventByOrderId(orderId)
      │  paymentKey는 confirm() 단계에서 executePayment()로 이미 기록됨
      │
      │  [조회 실패] → incrementRetryOrFail(orderId, outbox)
      ▼
Step 4: validateCompletionStatus(paymentEvent, command)
      │  금액(amount) 일치 검증, paymentKey 일치 검증
      │
      │  [실패] PaymentValidException / PaymentStatusException (검증 불일치)
      │    └─ executePaymentFailureCompensation()
      │         ├─ existsByOrderId() → false → failJob() 스킵
      │         ├─ increaseStockForOrders()   — 재고 복원
      │         └─ markPaymentAsFail()        — PaymentEvent: FAILED
      │    └─ markFailed(orderId)             — PaymentOutbox: FAILED
      │    (재시도 없음 — 데이터 정합성 오류)
      ▼
Step 5: confirmPaymentWithGateway(command)
      │  Toss API: POST /v1/payments/confirm 호출 (트랜잭션 밖)
      │
      │  [실패] PaymentTossNonRetryableException (Toss 비재시도 오류)
      │    └─ executePaymentFailureCompensation()
      │         ├─ existsByOrderId() → false → failJob() 스킵
      │         ├─ increaseStockForOrders()   — 재고 복원
      │         └─ markPaymentAsFail()        — PaymentEvent: FAILED
      │    └─ markFailed(orderId)             — PaymentOutbox: FAILED
      │
      │  [실패] PaymentTossRetryableException (Toss 일시 오류)
      │    └─ incrementRetryOrFail(orderId, outbox)
      │         ├─ retryCount < 5 → retryCount++ → PaymentOutbox: PENDING (다음 사이클 재처리)
      │         └─ retryCount >= 5 → PaymentOutbox: FAILED (재시도 한도 초과)
      ▼
Step 6: executePaymentSuccessCompletion(orderId, paymentEvent, approvedAt)
      │  단일 트랜잭션
      │  ├─ existsByOrderId() → false → completeJob() 스킵 (PaymentProcess 미존재)
      │  └─ markPaymentAsDone()  — PaymentEvent: DONE
      ▼
Step 7: markDone(orderId)
      │  PaymentOutbox: IN_FLIGHT → DONE
      ▼
[종료]
```

---

## 3. Kafka 플로우 (`KafkaAsyncConfirmService` + `KafkaConfirmListener`)

> `spring.payment.async-strategy=kafka`

### 3-1. confirm() — HTTP 요청 처리

```
[Controller] POST /confirm
      │
      ▼
① getPaymentEventByOrderId(orderId)
      │  PaymentEvent 조회 (상태: READY 예상)
      ▼
② executePaymentAndStockDecrease(paymentEvent, paymentKey, paymentOrderList)
      │  단일 트랜잭션 (rollbackFor=PaymentOrderedProductStockException)
      │  ┌─ executePayment()         — PaymentEvent: READY → IN_PROGRESS, paymentKey 기록
      │  └─ decreaseStockForOrders() — 재고 감소
      │  (PaymentOutbox 없음, PaymentProcess 없음)
      │
      │  [실패] PaymentOrderedProductStockException (재고 부족)
      │    → 트랜잭션 롤백 (PaymentEvent READY로 복원)
      │    └─ handleStockFailure(paymentEvent, message)
      │         └─ markPaymentAsFail()   → PaymentEvent: FAILED
      │         └─ rethrow PaymentOrderedProductStockException (4xx)
      ▼
③ confirmPublisher.publish(orderId)
      │  Kafka topic 'payment-confirm'에 orderId 발행
      │  재고 감소 트랜잭션 커밋 이후 호출
      │  (커밋 전 발행 시 컨슈머가 IN_PROGRESS 미조회 가능 — 타이밍 레이스 방지)
      │
      │  [실패] Exception (Kafka 발행 실패)
      │    └─ executePaymentFailureCompensation(orderId, inProgressEvent, paymentOrderList, message)
      │         ├─ existsByOrderId() → false → failJob() 스킵
      │         ├─ increaseStockForOrders()   — 재고 복원
      │         └─ markPaymentAsFail()        — PaymentEvent: FAILED
      │    └─ rethrow Exception (5xx)
      ▼
[Controller] 202 Accepted  (ResponseType.ASYNC_202)
      (이후 처리는 KafkaConfirmListener가 비동기로 담당)
```

### 3-2. KafkaConfirmListener.consume() — Kafka 컨슈머

```
[Kafka topic: 'payment-confirm' / groupId: payment-confirm-group]
      │
      @RetryableTopic 설정:
        - attempts: 6회 (최초 1회 + 재시도 5회)
        - backoff: 1초 시작, 2배 증가, 최대 30초
        - include: PaymentTossRetryableException 만 자동 재시도
        - DLT topic: 'payment-confirm-dlq'
        - autoCreateTopics: true
      │
      ▼
① getPaymentEventByOrderId(orderId)
      │  paymentKey는 confirm() 단계에서 executePayment()로 이미 기록됨
      ▼
② validateCompletionStatus(paymentEvent, command)
      │  금액(amount) 일치 검증, paymentKey 일치 검증
      │
      │  [실패] PaymentValidException / PaymentStatusException (검증 불일치)
      │    → @RetryableTopic include 목록에 없는 예외 → 재시도 없이 즉시 DLT 전송
      │    → @DltHandler: executePaymentFailureCompensation()
      │         ├─ existsByOrderId() → false → failJob() 스킵
      │         ├─ increaseStockForOrders()   — 재고 복원
      │         └─ markPaymentAsFail()        — PaymentEvent: FAILED
      ▼
③ confirmPaymentWithGateway(command)
      │  Toss API: POST /v1/payments/confirm 호출
      │
      │  [실패] PaymentTossNonRetryableException (Toss 비재시도 오류)
      │    └─ executePaymentFailureCompensation(orderId, paymentEvent, paymentOrderList, message)
      │         ├─ existsByOrderId() → false → failJob() 스킵
      │         ├─ increaseStockForOrders()   — 재고 복원
      │         └─ markPaymentAsFail()        — PaymentEvent: FAILED
      │    (재시도 없이 종료)
      │
      │  [실패] PaymentTossRetryableException (Toss 일시 오류)
      │    └─ rethrow → @RetryableTopic이 캐치 → 재시도 토픽으로 재발행
      │         시도 1: 1초 후 / 시도 2: 2초 후 / 시도 3: 4초 후
      │         시도 4: 8초 후 / 시도 5: 16초 후 / 시도 6 실패 → DLT 전송
      │
      │  [DLT 도달 — 재시도 6회 모두 실패]
      │    @DltHandler:
      │    └─ executePaymentFailureCompensation("kafka-dlt-exhausted")
      │         ├─ existsByOrderId() → false → failJob() 스킵
      │         ├─ increaseStockForOrders()   — 재고 복원
      │         └─ markPaymentAsFail()        — PaymentEvent: FAILED
      ▼
④ executePaymentSuccessCompletion(orderId, paymentEvent, approvedAt)
      │  단일 트랜잭션
      │  ├─ existsByOrderId() → false → completeJob() 스킵 (PaymentProcess 미존재)
      │  └─ markPaymentAsDone()  — PaymentEvent: DONE
      ▼
[종료]
```

---

## 4. 공유 메커니즘

### 4-1. executePaymentFailureCompensation (공통 보상 트랜잭션)

> `PaymentTransactionCoordinator.executePaymentFailureCompensation(orderId, paymentEvent, paymentOrderList, failureReason)`

```
단일 @Transactional

① existsByOrderId(orderId)?
      ├─ true  (Sync 전략 — PaymentProcess 존재)
      │    └─ failJob(orderId, reason)  → PaymentProcess: FAILED
      └─ false (Outbox / Kafka 전략 — PaymentProcess 미존재)
           └─ failJob() 스킵

② increaseStockForOrders(paymentOrderList)  — 재고 복원

③ markPaymentAsFail(paymentEvent, reason)   — PaymentEvent: FAILED
```

**호출 지점:**
- Sync: `handleNonRetryableFailure()`, `handleUnknownFailure()` (PaymentFailureUseCase)
- Outbox/Kafka confirm(): Kafka 발행 실패 시 (KafkaAsyncConfirmService)
- OutboxWorker: 검증 실패, 비재시도 오류 시
- KafkaConfirmListener: 비재시도 오류, DLT 도달 시
- recoverRetryablePayment: 비재시도 오류, 미지 예외 시

### 4-2. handleStockFailure (재고 부족 전용)

> `PaymentFailureUseCase.handleStockFailure(paymentEvent, failureMessage)`

```
markPaymentAsFail(paymentEvent, failureMessage)  → PaymentEvent: FAILED
(재고 복원 없음 — 재고 감소 자체가 트랜잭션 롤백으로 복원됨)
(increaseStockForOrders() 미호출)
```

### 4-3. executePaymentSuccessCompletion (성공 완료 — 전략 공통)

> `PaymentTransactionCoordinator.executePaymentSuccessCompletion(orderId, paymentEvent, approvedAt)`

```
단일 @Transactional

① existsByOrderId(orderId)?
      ├─ true  (Sync 전략) → completeJob(orderId)  — PaymentProcess: COMPLETED
      └─ false (Outbox / Kafka 전략) → completeJob() 스킵

② markPaymentAsDone(paymentEvent, approvedAt)  → PaymentEvent: DONE
```

---

## 5. 복구 스케줄러 (`PaymentScheduler`)

### 5-1. recoverStuckPayments()

> 활성화: `scheduler.payment-recovery.enabled=true`
> 주기: `scheduler.payment-recovery.interval-ms` (기본 1분, fixedDelay)

**목적**: PaymentProcess: PROCESSING인데 PaymentEvent가 미완료인 건을 Toss 상태 조회 API로 확인 후 처리.

```
① findAllProcessingJobs()  — PaymentProcess.status = PROCESSING 전수 조회
      │
      ▼ (각 건 순회)
② getStatusByOrderId(orderId)  — Toss API: GET /v1/payments/{orderId} (상태 조회)
      │
      ├─ status == DONE
      │    └─ executePaymentSuccessCompletion()
      │         ├─ existsByOrderId() → true (Sync) → completeJob()  — PaymentProcess: COMPLETED
      │         └─ markPaymentAsDone()                               — PaymentEvent: DONE
      │
      └─ status != DONE
           └─ executePaymentFailureCompensation()
                ├─ existsByOrderId() → true (Sync) → failJob()  — PaymentProcess: FAILED
                ├─ increaseStockForOrders()                      — 재고 복원
                └─ markPaymentAsFail()                           — PaymentEvent: FAILED
```

### 5-2. recoverRetryablePayment()

> 활성화: `scheduler.payment-status-sync.enabled=true`
> 주기: `scheduler.payment-status-sync.fixed-rate` (기본 5분, fixedRate)

**목적**: UNKNOWN 상태 또는 IN_PROGRESS로 5분 이상 응답 없는 결제에 Toss confirm API 재호출.

**재시도 조건 (isRetryable):**
- `status == UNKNOWN` 또는 `(status == IN_PROGRESS AND executedAt < now - 5분)`
- AND `retryCount < 5` (PaymentEvent.RETRYABLE_LIMIT)

```
① getRetryablePaymentEvents()  — 조건 만족하는 PaymentEvent 조회
      │
      ▼ (각 건 순회)
② isRetryable(now) 재검증 (DB 조회 후 상태 변경 가능성 대비)
      │
      ├─ false  → 건너뜀
      │
      └─ true
           ▼
           ③ increaseRetryCount()  — PaymentEvent.retryCount++ (상태 변경 없음)
           ▼
           ④ confirmPaymentWithGateway(command)  — Toss confirm API 재호출
                │
                ├─ 성공
                │    └─ markPaymentAsDone()  — PaymentEvent: DONE
                │       (completeJob() 미호출 — PaymentProcess: PROCESSING 유지
                │        → recoverStuckPayments가 후속 처리)
                │
                ├─ PaymentTossRetryableException
                │    └─ markPaymentAsUnknown()  — PaymentEvent: UNKNOWN
                │       (다음 주기에 재시도 대상)
                │
                └─ PaymentTossNonRetryableException / Unknown / 조건 불충족
                     └─ executePaymentFailureCompensation()
                          ├─ existsByOrderId() → true(Sync) → failJob()
                          │               → false(Outbox/Kafka) → 스킵
                          ├─ increaseStockForOrders()  — 재고 복원
                          └─ markPaymentAsFail()       — PaymentEvent: FAILED
```

---

## 6. 전략 비교 요약

| 항목 | Sync | Outbox | Kafka |
|------|------|--------|-------|
| `spring.payment.async-strategy` | `sync` (기본값) | `outbox` | `kafka` |
| HTTP 응답 | 200 OK | 202 Accepted | 202 Accepted |
| 재고 감소 시점 | confirm() — Toss 호출 전 | confirm() — Outbox 생성과 같은 TX | confirm() — Kafka 발행 전 |
| PaymentProcess 생성 | O (PROCESSING) | X | X |
| PaymentOutbox 생성 | X | O (PENDING) | X |
| Toss API 호출 시점 | confirm() 내부 (동기) | OutboxWorker 스케줄러 (비동기) | KafkaConfirmListener (비동기) |
| Toss API 재시도 전략 | 없음 (UNKNOWN 처리) | OutboxWorker retryCount (최대 5회) | RetryableTopic (최대 6회, exponential backoff) |
| PaymentEvent 최종 상태 | DONE / FAILED / UNKNOWN | DONE / FAILED | DONE / FAILED |
| 재고 실패 핸들링 | handleStockFailure() → FAILED | handleStockFailure() → FAILED | handleStockFailure() → FAILED |
| Kafka 발행 실패 보상 | 해당 없음 | 해당 없음 | executePaymentFailureCompensation() |
| DLT 처리 | 해당 없음 | 해당 없음 | @DltHandler → 보상 트랜잭션 |
