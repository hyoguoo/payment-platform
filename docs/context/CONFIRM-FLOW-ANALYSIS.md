# PaymentConfirmService 구현체 비즈니스 로직 분석

> 최종 수정: 2026-04-04

---

## 1. Outbox 플로우 (`OutboxAsyncConfirmService` + `PaymentConfirmChannel` + `OutboxImmediateWorker` + `OutboxWorker`)

> **Outbox 단일 전략** — `OutboxAsyncConfirmService`가 유일한 `PaymentConfirmService` 구현체
>
> **채널 기반 비동기 처리 + 폴백(OutboxWorker) 이중 구조**
> - 정상 경로: confirm() 커밋 후 `OutboxImmediateEventHandler`가 `channel.offer(orderId)` → `OutboxImmediateWorker` VT/PT 워커가 `channel.take()` → `OutboxProcessingService.process()` 처리
> - 폴백 경로: 큐 오버플로우 또는 서버 크래시 시 `OutboxWorker(fixedDelay 2s)`가 PENDING 레코드 배치 처리 → `OutboxProcessingService.process()` 위임

### 1-1. confirm() — HTTP 요청 처리

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
[Controller] 202 Accepted
      (이후 처리는 OutboxImmediateEventHandler가 비동기로 담당)
```

### 1-2. OutboxImmediateEventHandler.handle() — 채널 적재 (비블로킹)

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

### 1-3. OutboxImmediateWorker.workerLoop() — 채널 소비 (VT/PT 워커)

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
      [아래 1-4 참조]
      │
      └─ loop 반복 (InterruptedException → 루프 종료)
```

### 1-4. OutboxProcessingService.process() — 공유 처리 로직

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
      │  결과는 PaymentConfirmResultStatus enum (예외가 아닌 값 반환)으로 분기
      │
      │  [NON_RETRYABLE_FAILURE]
      │    └─ executePaymentFailureCompensationWithOutbox(paymentEvent, orderList, reason, outbox)
      │         ├─ outbox.toFailed() + save()  — PaymentOutbox: FAILED
      │         ├─ increaseStockForOrders()    — 재고 복원
      │         └─ markPaymentAsFail()         — PaymentEvent: FAILED
      │
      │  [RETRYABLE_FAILURE]
      │    └─ policy = retryPolicyProperties.toRetryPolicy()
      │         ├─ retryCount < maxAttempts
      │         │    └─ executePaymentRetryWithOutbox(paymentEvent, outbox, policy, now)
      │         │         ├─ outbox.incrementRetryCount(policy, now)
      │         │         │    — retryCount++, status=PENDING, nextRetryAt=now+backoff
      │         │         ├─ paymentOutboxUseCase.save(outbox)
      │         │         └─ markPaymentAsRetrying()  — PaymentEvent: RETRYING
      │         └─ retryCount >= maxAttempts → FAILED 확정 (executePaymentFailureCompensationWithOutbox)
      ▼
④ executePaymentSuccessCompletionWithOutbox(paymentEvent, approvedAt, outbox)
      │  단일 트랜잭션
      │  ├─ outbox.toDone() + save()  — PaymentOutbox: IN_FLIGHT → DONE
      │  └─ markPaymentAsDone()       — PaymentEvent: DONE
      ▼
[종료]
```

### 1-5. OutboxWorker.process() — 폴백 스케줄러

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
      [처리 로직은 1-4와 동일]
      ▼
[종료]
```

---

## 2. 공유 메커니즘

### 2-1. executePaymentSuccessCompletionWithOutbox (Outbox 성공 완료)

> `PaymentTransactionCoordinator.executePaymentSuccessCompletionWithOutbox(paymentEvent, approvedAt, outbox)`

```
단일 @Transactional

① outbox.toDone()
② paymentOutboxUseCase.save(outbox)  — PaymentOutbox: IN_FLIGHT → DONE
③ markPaymentAsDone(paymentEvent, approvedAt)  → PaymentEvent: DONE
```

**호출 지점:** OutboxProcessingService (Toss confirm 성공 후)

### 2-2. executePaymentFailureCompensationWithOutbox (Outbox 실패 보상)

> `PaymentTransactionCoordinator.executePaymentFailureCompensationWithOutbox(paymentEvent, paymentOrderList, failureReason, outbox)`

```
단일 @Transactional

① outbox.toFailed()
② paymentOutboxUseCase.save(outbox)          — PaymentOutbox: IN_FLIGHT → FAILED
③ increaseStockForOrders(paymentOrderList)   — 재고 복원
④ markPaymentAsFail(paymentEvent, reason)    — PaymentEvent: FAILED
```

**호출 지점:** OutboxProcessingService (비재시도 오류 발생 시)

### 2-3. executePaymentRetryWithOutbox (재시도 전환)

> `PaymentTransactionCoordinator.executePaymentRetryWithOutbox(paymentEvent, outbox, policy, now)`

```
단일 @Transactional

① outbox.incrementRetryCount(policy, now)
   — retryCount++, status=PENDING, nextRetryAt=now+policy.nextDelay(retryCount)
② paymentOutboxUseCase.save(outbox)   — PaymentOutbox: IN_FLIGHT → PENDING (nextRetryAt 설정)
③ markPaymentAsRetrying(paymentEvent) — PaymentEvent: IN_PROGRESS 또는 RETRYING → RETRYING
```

**호출 지점:** OutboxProcessingService (RETRYABLE_FAILURE, 재시도 한도 미도달 시)

**백오프 전략 (`BackoffType`):**
- `FIXED`: 매회 `baseDelayMs`만큼 대기
- `EXPONENTIAL`: `min(baseDelayMs * 2^retryCount, maxDelayMs)` — 지수 증가, 상한 있음

### 2-4. handleStockFailure (재고 부족 전용)

> `PaymentFailureUseCase.handleStockFailure(paymentEvent, failureMessage)`

```
markPaymentAsFail(paymentEvent, failureMessage)  → PaymentEvent: FAILED
(재고 복원 없음 — 재고 감소 자체가 트랜잭션 롤백으로 복원됨)
```
