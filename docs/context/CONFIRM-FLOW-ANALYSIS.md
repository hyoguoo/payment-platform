# Confirm Flow — 비즈니스 로직 분석

> 최종 갱신: 2026-04-27 (post-MSA + PRE-PHASE-4-HARDENING 봉인)
> 짝 문서: [`CONFIRM-FLOW-FLOWCHART.md`](CONFIRM-FLOW-FLOWCHART.md), 전체 end-to-end 는 [`PAYMENT-FLOW.md`](PAYMENT-FLOW.md)

## 책임 분담

본 문서는 **payment-service 측 비동기 confirm 사이클**을 다룬다. PG 벤더 호출(pg-service `PgConfirmService` + 벤더 어댑터)·Kafka 양방향 왕복 전체 흐름은 `PAYMENT-FLOW.md` 가 다룬다.

## 진입점 — `OutboxAsyncConfirmService.confirm()`

```
[Controller] POST /api/v1/payments/confirm
      │
      ▼
① getPaymentEventByOrderId(orderId)
      │  PaymentEvent 조회 (상태: READY 예상)
      ▼
② paymentEvent.validateConfirmRequest(userId, amount, orderId, paymentKey)
      │  사용자 입력 위변조 감지 — TX 진입 전 도메인 가드
      │  [실패] PaymentValidException → 즉시 throw (4xx)
      ▼
③ PaymentTransactionCoordinator.decrementStock(paymentOrderList)
      │  Redis 원자 DECR — TX 외부
      │  결과: SUCCESS / REJECTED(재고 부족) / CACHE_DOWN(Redis 장애)
      │
      ├─ REJECTED → handleStockFailure → event.status=FAILED + 4xx throw
      │
      └─ CACHE_DOWN → markStockCacheDownQuarantine
      │            → event.status=QUARANTINED (보상 펜딩) + 4xx throw
      ▼
④ executeConfirmTxWithStockCompensation
      │  try {
      │     coordinator.executeConfirmTx(paymentEvent, paymentKey, orderId)
      │       — @Transactional, 단일 TX:
      │         · paymentEvent: READY → IN_PROGRESS
      │         · paymentKey 기록
      │         · payment_outbox PENDING 행 INSERT
      │  } catch (RuntimeException txException) {
      │     compensateStock(paymentOrderList)  // Redis INCR 보상
      │     throw txException
      │  }
      ▼
⑤ confirmPublisher.publish(orderId)
      │  Spring ApplicationEventPublisher.publishEvent(PaymentConfirmEvent)
      │  TX 이미 커밋됨 — AFTER_COMMIT 리스너 트리거 큐잉
      ▼
[Controller] 202 Accepted (orderId, amount 즉시 반환)
      이후 비동기 처리는 OutboxImmediateEventHandler / OutboxWorker 가 담당
```

## AFTER_COMMIT — `OutboxImmediateEventHandler`

`@TransactionalEventListener(AFTER_COMMIT, fallbackExecution=true)` + `@Async("outboxRelayExecutor")` (Spring 관리 VT executor).

```
HTTP 스레드: TX 커밋 → publishEvent 큐잉 → 즉시 반환

[VT executor]
      │
      ▼
OutboxRelayService.relay(orderId)
      │
      ├─ Step 1: claimToInFlight(orderId)
      │     atomic UPDATE WHERE status='PENDING' → IN_FLIGHT (REQUIRES_NEW)
      │     [선점 실패] → return (다른 워커가 처리 중)
      │
      ├─ Step 2: outbox + paymentEvent 조회
      │
      ├─ Step 3: KafkaMessagePublisher.send
      │     topic=payment.commands.confirm
      │     payload=PaymentConfirmCommandMessage(orderId, paymentKey, amount, gatewayType, buyerId, eventUuid)
      │     [실패 예외] → IN_FLIGHT 유지 → OutboxWorker 폴백 재발행
      │
      └─ Step 4: outbox.toDone() 저장 → PaymentOutbox: IN_FLIGHT → DONE
```

## 폴백 — `OutboxWorker`

`@Scheduled(fixedDelay)` — 큐 오버플로우 / 워커 크래시 / 발행 실패 회복 전용.

```
Step 0: recoverTimedOutInFlightRecords(inFlightTimeoutMinutes)
      IN_FLIGHT 중 inFlightAt 기준 N분(기본 5분) 초과 → PENDING 복귀

Step 1: findPendingBatch(batchSize)
      PENDING 배치 조회 (기본 50건). 정상 환경에서는 채널 처리로 PENDING 없음 → no-op

Per record:
      OutboxRelayService.relay(orderId)   (위 AFTER_COMMIT 경로와 동일)
```

## 결과 수신 — `ConfirmedEventConsumer` + `PaymentConfirmResultUseCase`

`@KafkaListener(topics="payment.events.confirmed", groupId="payment-service")` → use case 위임.

```
ConfirmedEventConsumer.consume(message)
      │
      ▼
PaymentConfirmResultUseCase.handle(message)
      │
      ├─ Step 1: two-phase lease
      │     EventDedupeStore.markWithLease(eventUuid, leaseTtl=PT5M)
      │       → false: 이미 처리 중 (다른 인스턴스 또는 재처리). no-op + return
      │       → true: 처리 권한 획득
      │
      ├─ Step 2: paymentEvent 조회 (orderId)
      │
      ├─ Step 3: processMessageWithLeaseGuard(paymentEvent, message)
      │     try {
      │        @Transactional(timeout=5)
      │        분기:
      │          · APPROVED → handleApproved
      │          · FAILED   → handleFailed
      │          · QUARANTINED → handleQuarantined
      │
      │        성공 시: extendLease(eventUuid, longTtl=P8D)
      │     } catch (...) {
      │        보상 또는 재throw (운영 컨벤션)
      │        실패 시 remove(eventUuid):
      │          → false 면 dlqPublisher.publishDlq (payment.events.confirmed.dlq)
      │     }
      │
      └─ end
```

### handleApproved (양방향 amount 방어)

```
parseApprovedAt(message.approvedAt)
      [null] → IllegalArgumentException (APPROVED 인데 approvedAt null = 계약 위반)

isAmountMismatch(paymentEvent, message.amount)
      paymentEvent.totalAmount.longValueExact() vs message.amount
      [불일치 또는 message.amount null] → true

if (isAmountMismatch)
      → QuarantineCompensationHandler.handle(orderId, "AMOUNT_MISMATCH")
        markPaymentAsQuarantined(reason)
        early return (done 미호출, 재고 보상 미수행 — 격리 정책)

else
      receivedApprovedAt = OffsetDateTime.parse(message.approvedAt).toLocalDateTime()
      paymentCommandUseCase.markPaymentAsDone(paymentEvent, receivedApprovedAt, localDateTimeProvider.now())
      // AOP @PaymentStatusChange + @PublishDomainEvent 가 payment_history 자동 기록

      각 PaymentOrder 별:
        stock_outbox INSERT(payload=StockCommittedEvent) + StockOutboxReadyEvent publish
      // StockOutboxImmediateEventHandler 가 AFTER_COMMIT 으로 StockOutboxRelayService 호출 → payment.events.stock-committed Kafka publish
```

### handleFailed

```
paymentCommandUseCase.markPaymentAsFail(paymentEvent, reason)
      // AOP audit

각 PaymentOrder 별:
      stockCachePort.increment(productId, qty)
      // redis-stock 선차감 캐시 보상. product RDB 는 차감되지 않았으므로 복원 메시지 발행 X.
```

### handleQuarantined

PG 측 격리(QUARANTINED) 결정 또는 AMOUNT_MISMATCH 감지 시 호출.
```
각 PaymentOrder 별:
      stockCachePort.increment(productId, qty)   // Redis 선차감 보상

QuarantineCompensationHandler.handle(orderId, reason)
      → markPaymentAsQuarantined
      → admin 조사 큐에 등록 (CACHE_DOWN / 판단 불가 격리 트리거)
```

## 복구 사이클 — `PaymentReconciler` (`@Scheduled` 2분)

payment / pg 상태 불일치 스캔. 일정 시간 동안 PROCESSING 에 머문 건을 PG 에 직접 getStatus 조회 → RecoveryDecision 적용.

자세한 RecoveryDecision 분기는 `docs/archive/payment-double-fault-recovery/COMPLETION-BRIEFING.md`.

## 핵심 멱등성 / 가드

| 위치 | 메커니즘 |
|---|---|
| confirm 진입 | LVAL 가드 (`validateConfirmRequest`) — TX 진입 전 |
| confirm TX | `@Transactional` + `payment_outbox PENDING` 단일 커밋 |
| outbox claim | `claimToInFlight` REQUIRES_NEW atomic CAS — 다중 워커 선점 방지 |
| Kafka 멱등 | producer key=orderId, eventUuid 별도 첨부 |
| consumer 멱등 (payment 측) | `EventDedupeStore` **two-phase lease** (Redis SET NX EX) — markWithLease(PT5M) → 처리 → extendLease(P8D). 처리 실패 시 remove → false 면 DLQ |
| consumer 멱등 (pg 측) | `EventDedupeStore.markSeen` **단순 mark** (Redis SET NX EX P8D). 처리 실패 시 try/catch + remove 보상. payment 의 two-phase lease 와 다른 모델 — pg 는 `pg_inbox` 상태 CAS 가 추가 멱등 가드라 단순 mark 만으로 충분 |
| amount 방어 | 양방향 — pg 발행 시 non-null 강제 + payment 수신 시 대조 |
| 상태 전이 | `PaymentEventStatus.isTerminal()` SSOT — 종결 상태 재진입 차단 |
| 재고 보상 | D12 가드 — TX 내 outbox/event 재조회 후 양 조건 충족 시에만 INCR |

## 상태 머신 — `PaymentEventStatus`

| 상태 | 의미 | 진입 | `GET /status` 폴링 응답 |
|---|---|---|---|
| READY | 결제 초기 생성 | checkout 완료 | `PROCESSING` (default 분기) |
| IN_PROGRESS | confirm TX 커밋, paymentKey 기록 | `executePayment()` | `PROCESSING` |
| RETRYING | 복구 사이클 재시도 대기 (outbox PENDING + nextRetryAt) | `markPaymentAsRetrying()` | `PROCESSING` |
| DONE | PG 결제 완료 (approvedAt non-null) | `markPaymentAsDone()` | `DONE` |
| FAILED | 재고 부족 / PG 종결 실패 / non-retryable | `markPaymentAsFail()` | `FAILED` |
| **QUARANTINED** | **판단 불가 격리 (수동 확인 필요)** | `markPaymentAsQuarantined()` | **`PROCESSING`** ⚠️ — 클라이언트 폴링이 영영 종료되지 않음. admin 복구 필요 |
| CANCELED / PARTIAL_CANCELED / EXPIRED | PG 또는 만료 스케줄러 | (별도 경로) | `PROCESSING` (default) |

`isTerminal()` = DONE / FAILED / CANCELED / PARTIAL_CANCELED / EXPIRED / QUARANTINED.

> **운영 영향**: `PaymentStatusServiceImpl.mapEventStatus` 의 default 분기가 QUARANTINED 를 PROCESSING 으로 매핑한다 — `isTerminal()` 의 종결 상태이지만 status 폴링 결과는 종결을 표현하지 않는다. 격리된 결제는 admin 이 수동으로 DONE/FAILED 강제 전이를 해야 클라이언트 폴링이 종료된다.

## PaymentOutbox 상태 머신

| 상태 | 의미 |
|---|---|
| PENDING | 발행 대기. AFTER_COMMIT 리스너 또는 OutboxWorker 가 처리 |
| IN_FLIGHT | 워커가 선점, 발행 진행 중 |
| DONE | Kafka 발행 성공 |
| FAILED | 발행 영구 실패 (DLQ 또는 수동 처리) |

IN_FLIGHT 타임아웃(`inFlightTimeoutMinutes`, 기본 5분) 초과 → PENDING 복귀로 워커 크래시 회복.

## 회복 시나리오 인덱스

| 장애 | 동작 |
|---|---|
| 리스너 스킵 / 워커 크래시 (payment 측) | OutboxWorker 가 PENDING + IN_FLIGHT 타임아웃 회수 |
| Kafka producer 실패 (payment 측) | IN_FLIGHT 유지 → OutboxWorker 폴백 |
| pg-service 측 Toss/NicePay retryable (5xx/timeout) | **pg-service 자체 retry** — `pg_outbox.available_at = now + backoff` 로 `payment.commands.confirm` 에 재발행. attempt < 4 까지 |
| pg-service 측 retry 한도 초과 (attempt ≥ 4) | `payment.commands.confirm.dlq` 로 격리 (`PgVendorCallService.insertDlqOutbox`). pg_inbox 는 IN_PROGRESS 유지 |
| pg-service 측 non-retryable (4xx) | pg_inbox=FAILED → ConfirmedEvent FAILED → payment handleFailed |
| pg-service 측 판단 불가 / 5xx 한도 소진 | pg_inbox=QUARANTINED → ConfirmedEvent QUARANTINED → payment handleQuarantined |
| Redis dedupe 장애 | confirm 단계 CACHE_DOWN → QUARANTINED + 보상 펜딩 |
| amount 위변조 | pg 발행 시 차단 + payment 수신 시 차단 (양방향) |
| 중복 메시지 (payment 측) | two-phase lease (markWithLease/extendLease/remove) + outbox 상태 CAS 2단 |
| 중복 메시지 (pg 측) | markSeen + try/catch remove 보상 + inbox 상태 CAS 2단 |

## 관련 문서

- 전체 end-to-end (브라우저 → 200 → 폴링): `PAYMENT-FLOW.md`
- 다이어그램: `CONFIRM-FLOW-FLOWCHART.md`
- 복구 사이클 상세 (RecoveryDecision, FCG, D12): `docs/archive/payment-double-fault-recovery/COMPLETION-BRIEFING.md`
- AMOUNT_MISMATCH 양방향 방어: `docs/archive/pre-phase-4-hardening/COMPLETION-BRIEFING.md` (D1)
