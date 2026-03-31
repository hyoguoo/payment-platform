# PaymentConfirmService 구현체 비즈니스 로직 분석

> 최종 수정: 2026-03-31

---

## 1. Sync 플로우 (`PaymentConfirmServiceImpl`)

> `spring.payment.async-strategy=sync` (`matchIfMissing=false`, 기본값: `outbox`)

```
[Controller] POST /confirm
      │
      ▼
① getPaymentEventByOrderId(orderId)
      │  PaymentEvent 조회 (상태: READY 예상)
      ▼
② validateLocalPaymentRequest(paymentEvent, command)
      │  buyerId / amount / orderId / paymentKey 로컬 검증
      │
      │  [실패] PaymentValidException (불일치)
      │    → 즉시 throw (보상 없음 — TX 진입 전)
      ▼
③ executeStockDecreaseWithJobCreation(orderId, paymentOrderList)
      │  단일 트랜잭션 (rollbackFor=PaymentOrderedProductStockException)
      │  ┌─ decreaseStockForOrders()     — 재고 감소
      │  └─ createProcessingJob(orderId) — PaymentProcess: PROCESSING 생성
      │
      │  [실패] PaymentOrderedProductStockException (재고 부족)
      │    └─ handleStockFailure(paymentEvent, message)
      │         └─ markPaymentAsFail()   → PaymentEvent: FAILED
      │         └─ throw PaymentTossConfirmException (4xx)
      ▼
④ executePayment(paymentEvent, paymentKey)
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

## 2. Outbox 플로우 (`OutboxAsyncConfirmService` + `PaymentConfirmChannel` + `OutboxImmediateWorker` + `OutboxWorker`)

> `spring.payment.async-strategy=outbox` (기본값)
>
> **채널 기반 비동기 처리 + 폴백(OutboxWorker) 이중 구조**
> - 정상 경로: confirm() 커밋 후 `OutboxImmediateEventHandler`가 `channel.offer(orderId)` → `OutboxImmediateWorker` VT/PT 워커가 `channel.take()` → `OutboxProcessingService.process()` 처리
> - 폴백 경로: 큐 오버플로우 또는 서버 크래시 시 `OutboxWorker(fixedDelay 2s)`가 PENDING 레코드 배치 처리 → `OutboxProcessingService.process()` 위임

### 2-1. confirm() — HTTP 요청 처리

```
[Controller] POST /confirm
      │
      ▼
① getPaymentEventByOrderId(orderId)
      │  PaymentEvent 조회 (상태: READY 예상)
      ▼
② LVAL: command.amount == paymentEvent.totalAmount 검증
      │  [실패] PaymentValidException → 즉시 throw (TX 진입 전, 금액 위변조 감지)
      ▼
      @Transactional(rollbackFor=PaymentOrderedProductStockException)
      │
③ executePaymentAndStockDecreaseWithOutbox(paymentEvent, paymentKey, orderId, paymentOrderList)
      │  단일 트랜잭션 내 (rollbackFor=PaymentOrderedProductStockException)
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
④ confirmPublisher.publish(orderId)  [OutboxImmediatePublisher]
      │  Spring ApplicationEventPublisher.publishEvent(PaymentConfirmEvent)
      │  트랜잭션 커밋 이후 @TransactionalEventListener(AFTER_COMMIT)가 발동
      │  (TX 내에서 이벤트 큐잉 → 커밋 후 비동기 처리)
      ▼
[Controller] 202 Accepted  (ResponseType.ASYNC_202)
      (이후 처리는 OutboxImmediateEventHandler가 비동기로 담당)
```

### 2-2. OutboxImmediateEventHandler.handle() — 채널 적재 (비블로킹)

```
[@TransactionalEventListener(AFTER_COMMIT)]
TX 커밋 직후 HTTP 요청 스레드에서 실행 (블로킹 없음)
      │
      ▼
channel.offer(orderId)
      ├─ true:  LinkedBlockingQueue에 적재 완료
      │         → OutboxImmediateWorker 워커가 비동기 처리
      │
      └─ false: 큐 가득 참 (capacity 초과)
                → warn 로그 기록
                → OutboxWorker(polling)가 폴백 처리
[종료 — HTTP 스레드 즉시 반환]
```

### 2-3. OutboxImmediateWorker.workerLoop() — 채널 소비 (VT/PT 워커)

```
[SmartLifecycle — 앱 시작 시 workerCount개(기본 200) 워커 스레드 생성]
각 워커 스레드가 독립적으로 루프 실행

workerLoop():
      │
      ▼
channel.take()  [blocking wait — 큐에 항목이 올 때까지 대기]
      │
      ▼
OutboxProcessingService.process(orderId)
      [아래 2-4 참조]
      │
      └─ loop 반복 (InterruptedException → 루프 종료)
```

### 2-4. OutboxProcessingService.process() — 공유 처리 로직

```
[OutboxImmediateWorker 및 OutboxWorker 양쪽에서 호출]
      │
      ▼
① claimToInFlight(orderId)
      │  atomic UPDATE WHERE status='PENDING' → IN_FLIGHT
      │  REQUIRES_NEW 트랜잭션으로 즉시 커밋 — 중복 처리 방지
      │
      │  [클레임 실패 — Optional.empty()] → return
      ▼
② loadPaymentEvent(orderId)
      │  paymentKey는 confirm()의 executePayment()로 이미 기록됨
      │
      │  [조회 실패] → incrementRetryOrFail(orderId, outbox) → return
      ▼
③ confirmPaymentWithGateway(command)
      │  Toss API: POST /v1/payments/confirm 호출 (트랜잭션 밖)
      │
      │  [실패] PaymentTossNonRetryableException (Toss 비재시도 오류)
      │    └─ executePaymentFailureCompensationWithOutbox(paymentEvent, orderList, reason, outbox)
      │         ├─ outbox.toFailed() + save()  — PaymentOutbox: FAILED
      │         ├─ increaseStockForOrders()    — 재고 복원
      │         └─ markPaymentAsFail()         — PaymentEvent: FAILED
      │
      │  [실패] PaymentTossRetryableException (Toss 일시 오류)
      │    └─ incrementRetryOrFail(orderId, outbox)
      │         ├─ retryCount < 5 → retryCount++ → PaymentOutbox: PENDING (다음 처리 대상)
      │         └─ retryCount >= 5 → FAILED 확정 (executePaymentFailureCompensationWithOutbox)
      ▼
④ executePaymentSuccessCompletionWithOutbox(paymentEvent, approvedAt, outbox)
      │  단일 트랜잭션
      │  ├─ outbox.toDone() + save()  — PaymentOutbox: IN_FLIGHT → DONE
      │  └─ markPaymentAsDone()       — PaymentEvent: DONE
      ▼
[종료]
```

### 2-5. OutboxWorker.process() — 폴백 스케줄러

```
[@Scheduled fixedDelay — 기본 2000ms]
주 역할: 큐 오버플로우 / 서버 재시작으로 누락된 PENDING 레코드 재처리
      │
      ▼
Step 0: recoverTimedOutInFlightRecords(inFlightTimeoutMinutes)
      │  IN_FLIGHT 상태 레코드 중 inFlightAt 기준 N분(기본 5분) 초과 시
      │  → PaymentOutbox: IN_FLIGHT → PENDING (워커 비정상 종료 복구)
      ▼
Step 1: findPendingBatch(batchSize)
      │  PaymentOutbox: PENDING 상태 배치 조회 (기본 50건)
      │  정상 환경에서는 채널 처리로 PENDING이 없어 바로 return
      │  parallel 모드 시 가상 스레드(Java 21)로 병렬 처리
      │
      │  [배치 없음] → return (다음 사이클 대기)
      ▼
per record:
      OutboxProcessingService.process(outbox.getOrderId())
      [처리 로직은 2-4와 동일]
      ▼
[종료]
```

---

## 3. 공유 메커니즘

### 3-1. executePaymentSuccessCompletionWithOutbox (Outbox 성공 완료)

> `PaymentTransactionCoordinator.executePaymentSuccessCompletionWithOutbox(paymentEvent, approvedAt, outbox)`

```
단일 @Transactional

① outbox.toDone()
② paymentOutboxUseCase.save(outbox)  — PaymentOutbox: IN_FLIGHT → DONE
③ markPaymentAsDone(paymentEvent, approvedAt)  → PaymentEvent: DONE
```

**호출 지점:** OutboxProcessingService (Toss confirm 성공 후)

### 3-2. executePaymentFailureCompensationWithOutbox (Outbox 실패 보상)

> `PaymentTransactionCoordinator.executePaymentFailureCompensationWithOutbox(paymentEvent, paymentOrderList, failureReason, outbox)`

```
단일 @Transactional

① outbox.toFailed()
② paymentOutboxUseCase.save(outbox)          — PaymentOutbox: IN_FLIGHT → FAILED
③ increaseStockForOrders(paymentOrderList)   — 재고 복원
④ markPaymentAsFail(paymentEvent, reason)    — PaymentEvent: FAILED
```

**호출 지점:** OutboxProcessingService (비재시도 오류 발생 시)

### 3-3. executePaymentFailureCompensation (Sync 보상 트랜잭션)

> `PaymentTransactionCoordinator.executePaymentFailureCompensation(orderId, paymentEvent, paymentOrderList, failureReason)`

```
단일 @Transactional

① existsByOrderId(orderId)?
      ├─ true  (Sync 전략 — PaymentProcess 존재)
      │    └─ failJob(orderId, reason)  → PaymentProcess: FAILED
      └─ false → failJob() 스킵

② increaseStockForOrders(paymentOrderList)  — 재고 복원

③ markPaymentAsFail(paymentEvent, reason)   — PaymentEvent: FAILED
```

**호출 지점:**
- Sync: `handleNonRetryableFailure()`, `handleUnknownFailure()` (PaymentFailureUseCase)

### 3-4. executePaymentSuccessCompletion (Sync 성공 완료)

> `PaymentTransactionCoordinator.executePaymentSuccessCompletion(orderId, paymentEvent, approvedAt)`

```
단일 @Transactional

① existsByOrderId(orderId)?
      ├─ true  (Sync 전략) → completeJob(orderId)  — PaymentProcess: COMPLETED
      └─ false → completeJob() 스킵

② markPaymentAsDone(paymentEvent, approvedAt)  → PaymentEvent: DONE
```

**호출 지점:** Sync (`PaymentConfirmServiceImpl.processPayment()`)

### 3-5. handleStockFailure (재고 부족 전용)

> `PaymentFailureUseCase.handleStockFailure(paymentEvent, failureMessage)`

```
markPaymentAsFail(paymentEvent, failureMessage)  → PaymentEvent: FAILED
(재고 복원 없음 — 재고 감소 자체가 트랜잭션 롤백으로 복원됨)
```

---

## 4. 전략 비교 요약

| 항목 | Sync | Outbox |
|------|------|--------|
| `spring.payment.async-strategy` | `sync` | `outbox` (기본값) |
| HTTP 응답 | 200 OK | 202 Accepted |
| 재고 감소 시점 | confirm() — Toss 호출 전 | confirm() — Outbox 생성과 같은 TX |
| PaymentProcess 생성 | O (PROCESSING) | X |
| PaymentOutbox 생성 | X | O (PENDING) |
| Toss API 호출 시점 | confirm() 내부 (동기) | OutboxProcessingService (OutboxImmediateWorker VT/PT 워커, AFTER_COMMIT 채널 적재) |
| 폴백 처리 | 없음 | OutboxWorker (fixedDelay 2s, 배치 50) + OutboxProcessingService 위임 |
| Toss API 재시도 전략 | 없음 (UNKNOWN 처리) | incrementRetryOrFail (최대 5회) |
| PaymentEvent 최종 상태 | DONE / FAILED / UNKNOWN | DONE / FAILED |
| 재고 실패 핸들링 | handleStockFailure() → FAILED | handleStockFailure() → FAILED |
| 즉시 처리 메커니즘 | 해당 없음 | PaymentConfirmChannel + OutboxImmediateWorker (SmartLifecycle, VT/PT 선택) |
