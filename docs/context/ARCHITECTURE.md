# Architecture

**Analysis Date:** 2026-04-04

## Pattern Overview

**Overall:** Hexagonal Architecture (Ports and Adapters) with vertical-slice bounded contexts

**Key Characteristics:**
- Four bounded contexts: `payment`, `paymentgateway`, `product`, `user` — each has its own `domain → application → infrastructure → presentation` layer stack
- Ports are Java interfaces defined in `application/port/` (outbound) and `presentation/port/` (inbound); all infrastructure implements those interfaces
- Outbox 단일 전략: `OutboxAsyncConfirmService`가 유일한 `PaymentConfirmService` 구현체 (전략 선택 불필요)
- Cross-context calls never share a repository; they go through `presentation/port` interfaces of the target context, consumed by `infrastructure/internal/` adapters in the calling context

---

## Layers (payment context — the primary context)

**Domain (`payment/domain`):**
- Purpose: Pure business logic, zero Spring dependencies
- Location: `src/main/java/com/hyoguoo/paymentplatform/payment/domain/`
- Contains: `PaymentEvent`, `PaymentOrder`, `PaymentOutbox`, `PaymentHistory` aggregates; `RecoveryDecision` record (복구 사이클 결정 값 객체, Type enum + RecoveryReason); `RetryPolicy` record (maxAttempts, backoffType, baseDelayMs, maxDelayMs); status enums in `domain/enums/` including `BackoffType` (FIXED/EXPONENTIAL), `PaymentEventStatus` (with `isTerminal()` SSOT 판별자), `RecoveryReason`; cross-context DTOs in `domain/dto/` and `domain/dto/vo/`; Spring `ApplicationEvent` subtypes in `domain/event/`
- Depends on: nothing outside `domain`
- Used by: `application` use-case services

**Application (`payment/application`):**
- Purpose: Orchestrates domain objects; owns all port interfaces; contains the confirm service bean
- Location: `src/main/java/com/hyoguoo/paymentplatform/payment/application/`
- Inbound ports (interfaces in `presentation/port/`, implemented here):
  - `PaymentConfirmService` — implemented by `OutboxAsyncConfirmService` (only implementation)
  - `PaymentStatusService` — implemented by `PaymentStatusServiceImpl` (always active)
  - `PaymentCheckoutService` — implemented by `PaymentCheckoutServiceImpl`
  - `AdminPaymentService` — implemented by `AdminPaymentServiceImpl`
- Outbound ports (interfaces in `application/port/`, implemented in `infrastructure`):
  - `PaymentEventRepository`, `PaymentOrderRepository`, `PaymentOutboxRepository`, `PaymentHistoryRepository`
  - `PaymentGatewayPort` — confirm/cancel/status calls to PG (Toss/NicePay)
  - `ProductPort`, `UserPort` — cross-context calls
  - `application/port/out/PaymentConfirmPublisherPort` — Outbox 즉시 처리 이벤트 발행 추상화
  - `IdempotencyStore` — 멱등성 키 저장소 (port at `application/port/IdempotencyStore.java`)
- Fine-grained use-case services (internal, not exposed as ports):
  - `PaymentCommandUseCase` — all status-changing operations; owns `@PublishDomainEvent` and `@PaymentStatusChange` annotations; `getPaymentStatusByOrderId()` — 복구 사이클용 PG 상태 조회 위임 (scheduler → application 레이어 경유); `markPaymentAsQuarantined()` — 격리 상태 전이 + `PaymentQuarantineMetrics` 카운터 증가
  - `PaymentLoadUseCase` — read operations
  - `PaymentOutboxUseCase` — outbox lifecycle
  - `PaymentFailureUseCase` — failure routing logic
  - `PaymentTransactionCoordinator` — all `@Transactional` boundary definitions; owns `claimToInFlight` delegation pattern
  - `OrderedProductUseCase`, `OrderedUserUseCase`, `PaymentCreateUseCase`
  - `PaymentHistoryUseCase` — payment history 저장/조회
  - `AdminPaymentLoadUseCase` — admin 쿼리 전용 use-case
- Config (application layer):
  - `RetryPolicyProperties` — `@ConfigurationProperties(prefix = "payment.retry")`; `toRetryPolicy()` converts to `RetryPolicy` domain record; defaults: maxAttempts=5, backoffType=FIXED, baseDelayMs=5000, maxDelayMs=60000
- Utilities (application layer, not ports/use-cases):
  - `IdempotencyKeyHasher` — 멱등성 키 해시 유틸
- Depends on: `domain`, `core/common`
- Used by: `presentation`, `infrastructure/scheduler`, `infrastructure/listener`

**Infrastructure (`payment/infrastructure`):**
- Purpose: JPA entities, Spring Data, gateway adapters, Kafka publisher
- Location: `src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/`
- Contains:
  - JPA entities: `entity/PaymentEventEntity`, `PaymentOrderEntity`, `PaymentOutboxEntity`, `PaymentHistoryEntity`
  - Spring Data interfaces: `repository/JpaPaymentEventRepository`, `JpaPaymentOrderRepository`, `JpaPaymentOutboxRepository`, `JpaPaymentHistoryRepository`
  - Port implementations: `repository/PaymentEventRepositoryImpl`, `PaymentOutboxRepositoryImpl`, etc.
  - Gateway strategy (pg-service 모듈 쪽 구현): `pg/infrastructure/gateway/toss/TossPaymentGatewayStrategy`, `pg/infrastructure/gateway/nicepay/NicepayPaymentGatewayStrategy`, `pg/infrastructure/gateway/fake/FakePgGatewayStrategy` (smoke 프로파일 한정)
  - `PaymentGatewayType` enum is in `payment/domain/enums/` (TOSS, NICEPAY); `PaymentEvent.gatewayType` stores per-event PG 선택 (DB 컬럼 바인딩 + Kafka wire contract 이중 역할)
  - Cross-context HTTP adapters: `payment/infrastructure/adapter/http/ProductHttpAdapter` implements `ProductPort`, `UserHttpAdapter` implements `UserPort`. Conditional on `product.adapter.type=http` / `user.adapter.type=http` (MSA 기본 프로파일)
  - Outbox 즉시 처리 발행자: `messaging/publisher/OutboxImmediatePublisher` implements `PaymentConfirmPublisherPort` (Spring `ApplicationEventPublisher` 기반)
  - Idempotency: `idempotency/IdempotencyStoreImpl` implements `IdempotencyStore`, `IdempotencyProperties`
  - Mapper: `PaymentInfrastructureMapper`
- Depends on: `application/port`, `paymentgateway/presentation/port`, `product/presentation/port`, `user/presentation/port`

**Presentation (`payment/presentation`):**
- Purpose: HTTP controllers and inbound port interface definitions
- Location: `src/main/java/com/hyoguoo/paymentplatform/payment/presentation/`
- Contains: `PaymentController`, `PaymentAdminController`, `NicepayReturnController`, `PaymentPresentationMapper`; request/response DTOs in `presentation/dto/`
- Inbound port interfaces: `presentation/port/PaymentConfirmService`, `PaymentCheckoutService`, `PaymentStatusService`, `AdminPaymentService`
- `PaymentController.confirm()` always returns `ResponseEntity.accepted(202)`
- Depends on: application layer via port interfaces only

**Scheduler (`payment/infrastructure/scheduler`):**
- Purpose: Background scheduled jobs and lifecycle-managed async workers
- Location: `src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/scheduler/`
- Contains:
  - `OutboxWorker` — `@Scheduled(fixedDelayString)` 폴링 안전망; PENDING outbox 레코드를 배치 조회 → `OutboxRelayService.relay(orderId)` 위임. 병렬 모드(`scheduler.outbox-worker.parallel-enabled=true`) 시 `ContextExecutorService.wrap` 기반 VT 풀을 사용해 MDC 승계.
  - `PaymentScheduler` — 만료 스케줄러; `@Scheduled(fixedRateString)` 기본 5분마다 READY 상태 오래된 결제를 만료 처리
- Port interfaces: `application/port/in/PaymentExpirationService` (K12: scheduler/port/ → application/port/in/ 이동)
- Depends on: `application/service/OutboxRelayService`, `application/usecase/PaymentOutboxUseCase`
- **pg-service scheduler 구조 (대칭 설계, `pg/infrastructure/scheduler/`):**
  - `PgOutboxImmediateWorker` — `SmartLifecycle` 구현체; 앱 시작 시 N개(기본 1개, `pg.outbox.channel.worker-count`) VT 워커 스레드를 기동. `PgOutboxChannel.take()`로 outboxId를 수신 → `ContextExecutorService.wrap` 기반 `relayExecutor`에 relay 제출. `stop()`: running=false + 스레드 interrupt + awaitTermination(10s). `getPhase()=Integer.MAX_VALUE-100` (채널보다 나중에 stop).
  - `PgOutboxPollingWorker` — `@Scheduled(fixedDelayString=2000ms)` 안전망; `available_at <= NOW AND processedAt IS NULL` 조건 polling → `PgOutboxRelayService.relay(id)` 위임.

**Listener (`payment/infrastructure/listener`):**
- Purpose: TX commit 이후 Kafka 발행을 담당하는 AFTER_COMMIT 리스너 + Spring 이벤트 리스너
- Location: `src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/listener/`
- Contains:
  - `OutboxImmediateEventHandler` — `@TransactionalEventListener(AFTER_COMMIT)` + `@Async("outboxRelayExecutor")`; confirm TX 커밋 직후 VT 풀에서 `OutboxRelayService.relay(orderId)` 호출해 Kafka 발행. HTTP 워커 스레드를 즉시 해방. 실패/누락은 `OutboxWorker`(polling) 안전망이 회복.
  - `StockOutboxImmediateEventHandler` — `@TransactionalEventListener(AFTER_COMMIT)` + `@Async("outboxRelayExecutor")`; stock_outbox DB 커밋 직후 `StockOutboxRelayService.relay(outboxId)` 트리거. T-J1 Transactional Outbox 패턴.
  - `PaymentHistoryEventListener` — `@TransactionalEventListener(BEFORE_COMMIT)` 기반 결제 이력 저장 리스너. `PaymentHistoryService`(application/port/in) 경유.
  - `StockCacheWarmupApplicationEventListener` — `@EventListener(ApplicationReadyEvent.class)` 기반 재고 캐시 warmup 트리거. Spring ApplicationReadyEvent 구독 — Kafka consumer 아님.
- Port interfaces: `application/port/in/PaymentHistoryService` (K11: listener/port/ → application/port/in/ 이동)
- Depends on: `application/service/OutboxRelayService`, `application/service/StockOutboxRelayService`, `application/port/in/PaymentHistoryService`

---

## Confirm Flow (Outbox 단일 전략)

### payment-service 발행 경로 (HTTP → Kafka)

```
PaymentController.confirm()
  → OutboxAsyncConfirmService.confirm()
      1. Redis DECR (stockCachePort.decrement) — TX 외부
         REJECTED → 재고 부족 예외 즉시 반환
         CACHE_DOWN → 캐시 장애 → coordinator.executeConfirmTxWithStockCompensation 경유
         SUCCESS → coordinator.executeConfirmTxWithStockCompensation(@Transactional)
      2. coordinator.executeConfirmTxWithStockCompensation()  [@Transactional]
         - PaymentEvent READY→IN_PROGRESS 전이 + PaymentOutbox(PENDING) INSERT (원자 TX)
         - ApplicationEventPublisher.publishEvent(PaymentConfirmEvent) — TX 내 이벤트 예약
         - TX 실패 시: stockCachePort.increment(productId, qty) 보상(ADR-D3)
  ← ResponseEntity.accepted(202)

OutboxImmediateEventHandler.handle(PaymentConfirmEvent)
  [@TransactionalEventListener(AFTER_COMMIT), @Async("outboxRelayExecutor")]
  — TX 커밋 직후 VT 풀(outboxRelayExecutor)에서 비동기 실행
  → OutboxRelayService.relay(orderId)
       Step 1: paymentOutboxRepository.claimToInFlight(orderId, now)  [atomic PENDING→IN_FLIGHT]
               claimed=false → 다른 워커 처리 중 → 즉시 return (멱등성 보장)
       Step 2: paymentEvent 조회 → PaymentConfirmCommandMessage 구성
       Step 3: messagePublisherPort.send(payment.commands.confirm, orderId, message)
               실패 시 예외 전파 → TX rollback → outbox PENDING 유지
       Step 4: outbox.toDone() + save

OutboxWorker.process()  [@Scheduled fixedDelay=5000ms — 안전망 폴링]
  — OuboxImmediateEventHandler 누락·크래시 시 PENDING row 재픽업
  Step 0: recoverTimedOutInFlightRecords()  [IN_FLIGHT timeout → PENDING 복구]
  Step 1: findPendingBatch(batchSize=10)
  per record: OutboxRelayService.relay(outbox.getOrderId())  [동일 relay 경로]
```

### pg-service 발행 경로 (Kafka 소비 → pg-service 내부)

```
PaymentConfirmConsumer  [@KafkaListener payment.commands.confirm, groupId=pg-service]
  → PgConfirmService.handle(PgConfirmCommand)
       1. EventDedupeStore.markWithLease(eventUUID, shortTtl)  [dedupe 1단]
       2. pgInboxRepository.transitNoneToInProgress(orderId, amount)  [CAS 원자 선점]
          IN_PROGRESS/terminal → 분기 처리 (no-op 또는 재발행)
       3. PgVendorCallService.call()  [Toss/NicePay 벤더 API 호출]
          → PgFinalConfirmationGate.performFinalCheck()  [FCG: getStatus 1회만]
       4. PgOutbox INSERT (PENDING) + PgOutboxReadyEvent 발행

PgOutboxReadyEvent  [@TransactionalEventListener(AFTER_COMMIT)]
  → PgOutboxChannel.offer(outboxId)  [LinkedBlockingQueue<Long>]

PgOutboxImmediateWorker  [SmartLifecycle — VT 워커]
  PgOutboxChannel.take() → relayExecutor.submit → PgOutboxRelayService.relay(id)
  → PgEventPublisherPort.publish(payment.events.confirmed, orderId, payload, headers)
     payload: ConfirmedEventPayload (APPROVED/FAILED/QUARANTINED + amount + approvedAt)

PgOutboxPollingWorker  [@Scheduled fixedDelay=2000ms — 안전망]
  findPendingBatch(batchSize, now) → PgOutboxRelayService.relay(id)  [동일 relay 경로]
```

### payment-service 수신 경로 (pg-service → Kafka → stock 처리)

```
ConfirmedEventConsumer  [@KafkaListener payment.events.confirmed, groupId=payment-service]
  → PaymentConfirmResultUseCase.handle(ConfirmedEventMessage)  [@Transactional(timeout=5)]
       T-C3 two-phase lease:
         진입: eventDedupeStore.markWithLease(eventUuid, leaseTtl=5m)
         성공 후: extendLease(eventUuid, longTtl=P8D)
         실패 후: remove(eventUuid) → remove=false이면 DLQ 전송
       분기:
         APPROVED →
           paymentEvent.done(approvedAt, now)  [amount 불일치 시 AMOUNT_MISMATCH QUARANTINED]
           + applicationEventPublisher.publishEvent(StockCommitRequestedEvent) per order  [T-D2]
         FAILED →
           paymentEvent.fail(now)
           + failureCompensationService.compensate(orderId, productId, qty) per order
             → applicationEventPublisher.publishEvent(StockRestoreRequestedEvent)  [T-D2]
         QUARANTINED →
           QuarantineCompensationHandler.handle(orderId)

StockOutboxImmediateEventHandler  [@TransactionalEventListener(AFTER_COMMIT), @Async("outboxRelayExecutor")]
  — T-J1: stock 발행 Transactional Outbox 패턴 (payment.commands.confirm 경로와 대칭)
  StockOutboxReadyEvent 수신 → StockOutboxRelayService.relay(outboxId)
    → StockOutboxKafkaPublisher.send(topic, key, payload)
  발행 실패 시: 안전망 폴링은 현재 미구현 — Phase 4 stock_outbox 폴링 워커 예정 (TODOS.md)
```

### payment-service vs pg-service 구조 대칭성

| | payment-service | pg-service |
|---|---|---|
| 즉시 처리 | `OutboxImmediateEventHandler` (`@Async` + `@TransactionalEventListener`) | `PgOutboxImmediateWorker` (`SmartLifecycle` + `LinkedBlockingQueue` + VT) |
| 폴링 안전망 | `OutboxWorker` (`@Scheduled` 5000ms) | `PgOutboxPollingWorker` (`@Scheduled` 2000ms) |
| Kafka 발행 | `OutboxRelayService` → `MessagePublisherPort` | `PgOutboxRelayService` → `PgEventPublisherPort` |
| MDC 전파 | `MdcTaskDecorator` (outboxRelayExecutor) | `ContextExecutorService.wrap` (relayExecutor) |
| 종료 제어 | Spring `@Async` 스레드풀 lifecycle 위임 | `SmartLifecycle.stop()` 직접 제어 (awaitTermination 10s) |

**트레이드오프**: pg-service SmartLifecycle은 종료 순서·drain 제어가 세밀하나 구현 복잡도가 높다. payment-service의 Spring-native `@TransactionalEventListener + @Async` 조합은 TX 동기화가 자동 보장되어 단순하나 스레드풀 lifecycle은 Spring container에 위임한다.

---

## Key Design Decisions

**PaymentTransactionCoordinator — shared transactional boundary:**
- A plain `@Service` (no interface) shared by `OutboxAsyncConfirmService` and `OutboxWorker`
- Every method annotated with `@Transactional`; individual use-case services are not `@Transactional` themselves unless they have single-operation needs
- Key methods: `executeConfirmTxWithStockCompensation`(confirm TX + ADR-D3 Redis 보상), `executePaymentSuccessCompletionWithOutbox`, `executePaymentFailureCompensationWithOutbox` (D12 가드: TX 내 outbox/event 재조회 후 조건 충족 시에만 재고 복구), `executePaymentRetryWithOutbox`, `executePaymentQuarantineWithOutbox` (격리 전이: outbox FAILED + event QUARANTINED)
- File: `src/main/java/com/hyoguoo/paymentplatform/payment/application/usecase/PaymentTransactionCoordinator.java`

**PaymentConfirmPublisherPort — Outbox 즉시 처리 이벤트 추상화:**
- Interface in `application/port/out/PaymentConfirmPublisherPort` with single method `publish(String orderId)`
- `OutboxImmediatePublisher` (infrastructure 계층)가 `ApplicationEventPublisher.publishEvent(PaymentConfirmEvent)`로 구현
- Application 계층은 Spring 이벤트 발행 세부 구현을 알지 못함

**OutboxRelayService — Kafka relay 단일 진입점:**
- `application/service/OutboxRelayService` — `OutboxImmediateEventHandler`(AFTER_COMMIT+@Async 경로)와 `OutboxWorker`(@Scheduled 경로) 양쪽에서 공유하는 단일 relay 서비스
- `relay(orderId)`: `claimToInFlight`(원자 선점) → paymentEvent 조회 → `messagePublisherPort.send(payment.commands.confirm)` → `outbox.toDone()`
- `@Transactional`: 양 경로 모두 TX-less 진입이므로 relay 메서드에서 TX 시작
- `payment-service` 에는 `OutboxImmediateWorker` / `PaymentConfirmChannel` 이 존재하지 않는다 — 이는 구버전 문서의 잔재이며 실제 코드에 부재.

**Cross-Context Communication pattern (MSA 기준):**
- `payment-service` → `pg-service`: Kafka `payment.commands.confirm` 토픽 발행, pg-service 가 consume. 응답은 `payment.events.confirmed` 경로로 비동기 귀환 (ADR-02, ADR-21)
- `payment-service` → `product-service`: 상품·재고 조회는 HTTP(`ProductHttpAdapter` → `GET /api/v1/products/{id}`), 재고 확정·복원은 Kafka (`payment.events.stock-committed`, `stock.events.restore`) 이벤트
- `payment-service` → `user-service`: HTTP 조회 전용 (`UserHttpAdapter` → `GET /api/v1/users/{id}`)
- No cross-context JPA joins (ADR-23: DB per service)
- 모든 HTTP 호출은 Gateway(Eureka `lb://`) 경유. `@CircuitBreaker` 는 Phase 4에서 설치 예정 (ADR-22 예약)

**AOP-Driven Observability:**
- `@PublishDomainEvent` on `PaymentCommandUseCase` (payment-service) → `DomainEventLoggingAspect` publishes Spring `ApplicationEvent`
- `@PaymentStatusChange` on `PaymentCommandUseCase` (payment-service) → `PaymentStatusMetricsAspect` records Micrometer counters
- `@TossApiMetric` (pg-service) → `TossApiMetricsAspect` records vendor API latency
- Aspect classes: `payment-service/.../core/common/aspect/` (payment-service 내부), `pg-service/.../infrastructure/aspect/` (pg-service 벤더 호출 메트릭)

**Benchmark profile:**
- `@Profile("benchmark")` in `mock/BenchmarkConfig.java` replaces the real `HttpOperator` with `FakeTossHttpOperator` (configurable delay)
- Files: `src/main/java/com/hyoguoo/paymentplatform/mock/BenchmarkConfig.java`, `FakeTossHttpOperator.java`

---

## Error Handling

**Strategy — Outbox Relay 복구:** `OutboxRelayService.relay(orderId)` → `claimToInFlight`(원자 선점) → Kafka 발행. 실패 시 TX rollback → outbox PENDING 유지 → `OutboxWorker` 재시도. pg-service 측에서는 `PgVendorCallService` → `PgFinalConfirmationGate(FCG)` → `PgOutbox` 경유로 최종 결과를 `payment.events.confirmed` 로 발행한다.
- **FCG(Final Confirmation Gate)**: pg-service에서 retry 소진 시 `getStatusByOrderId` 1회 재조회 → 판별 가능하면 APPROVED/FAILED, 불가하면 QUARANTINED
- **T-D2 AFTER_COMMIT 분리**: payment-service `PaymentConfirmResultUseCase`(APPROVED/FAILED 처리) → stock ApplicationEvent 발행 → `StockEventPublishingListener`(AFTER_COMMIT) → Kafka 발행. DB TX 블로킹 방지.

**PG 예외 분류:**
- `PaymentGatewayRetryableException` — PG 일시 오류, 재시도 가능 → `RETRY_LATER` 또는 `QUARANTINE`
- `PaymentGatewayNonRetryableException` — PG에 결제 기록 없음 → `ATTEMPT_CONFIRM` (confirm 시도)
- `PaymentGatewayConfirmException` — confirm 실패 (벤더 무관 공통 예외)

**Confirm 결과 분류** (`PaymentConfirmResultStatus`, ATTEMPT_CONFIRM 경로에서 사용):
- `SUCCESS` — `executePaymentSuccessCompletionWithOutbox` (DONE)
- `RETRYABLE_FAILURE` — 미소진: `executePaymentRetryWithOutbox`, 소진: FCG
- `NON_RETRYABLE_FAILURE` — `executePaymentFailureCompensationWithOutbox` (D12 가드 + FAILED)

**기타 예외:**
- `PaymentOrderedProductStockException` — stock exhausted; payment marked FAILED, no compensation (stock was never decremented)
- `PaymentStatusException` / `PaymentValidException` — domain guard violations; handler returns 4xx

**Retry backoff:** Configured via `RetryPolicyProperties` (`payment.retry.*`); `RetryPolicy.nextDelay(retryCount)` computes FIXED or EXPONENTIAL delay; `PaymentOutbox.nextRetryAt` stores scheduled time; `OutboxWorker` queries `WHERE status='PENDING' AND next_retry_at <= now`.

**Compensation flow:** `executePaymentFailureCompensationWithOutbox`는 D12 가드(TX 내 outbox/event 재조회) 후 조건 충족 시에만 재고 복구, `PaymentEvent` FAILED 전이. **Quarantine flow:** `executePaymentQuarantineWithOutbox`는 outbox FAILED + `PaymentEvent` QUARANTINED 전이 + `PaymentQuarantineMetrics` 카운터 증가.

**Quarantine Recovery (운영자 복구 경로) — 현재 자동 경로 없음:**
- `payment_event.status = QUARANTINED` 는 FCG INDETERMINATE 판정 후 PG 실제 상태를 자동으로 결정할 수 없는 경우에만 발생한다.
- T3.5-07(2026-04-24)에서 QUARANTINED 재고 자동 복구 경로를 철거 — 자동 복구 시 PG 실제 승인 건의 이중 복구 위험이 있으므로 운영자 개입이 유일한 복구 경로다.
- `QuarantineCompensationHandler.handle(orderId)`: QUARANTINED 전이만 수행하며 재고·결제 상태를 최종 확정하지 않는다.
- **홀딩 자산 복구 절차 (현재 수동, 자동화 별도 토픽 `QUARANTINED-ADMIN-RECOVERY` 예약):**
  1. 운영자가 PG 관리 콘솔에서 `orderId` 기준 실제 결제 상태 확인
  2. APPROVED 확인 시: `payment_event.status` → DONE 수동 전이 + 재고 차감 유지
  3. FAILED/취소 확인 시: `payment_event.status` → FAILED 수동 전이 + 재고 복원 수동 발행
  4. 처리 완료 후 Grafana `payment_outbox` 패널에서 QUARANTINED 잔여 건 0 확인
- **모니터링**: `PaymentQuarantineMetrics` 카운터 + Grafana alerting(임계 TBD — `QUARANTINED-ADMIN-RECOVERY` 토픽에서 SLA 정의 예정)
- **관련 파일**: `QuarantineCompensationHandler`, `PgDlqService`, `PaymentEvent.quarantine()`

**Exception handlers:**
- `src/main/java/com/hyoguoo/paymentplatform/payment/core/common/exception/GlobalExceptionHandler.java`

---

## Cross-Cutting Concerns

**Logging:** `LogFmt` (`payment/core/common/log/LogFmt.java`) produces structured `key=value` lines; `MaskingPatternLayout` masks sensitive values in logback
**Validation:** Domain object guard clauses (`PaymentEvent.execute()`, `done(approvedAt null 가드)`, `fail(종결 no-op)`, `expire()`, `toRetrying()`, `quarantine()`)
**Transaction boundary:** All multi-step DB operations go through `PaymentTransactionCoordinator`
**Metrics:** `PaymentStateMetrics`, `PaymentHealthMetrics`, `PaymentTransitionMetrics`, `TossApiMetrics`, `PaymentQuarantineMetrics` (격리 카운터) in `payment/core/common/metrics/`

---

## Kafka Consumer Group 정책 (T3.5-09)

**원칙**: **기능(도메인 플로우)별 독립 groupId**. 같은 서비스 안이라도 논리적으로 분리된 경로는 독립 그룹으로 둔다.

**근거**:
- 한 consumer group 안의 consumer 는 offset commit·rebalance 를 공유한다. commit 경로에서 DB lock 지연이 발생하면 restore(보상) 경로까지 lag 이 생긴다.
- 재고 commit 과 재고 복원은 서로 독립된 도메인 플로우다 — commit 이 느려도 보상은 제 속도로 흘러야 한다.
- Kafka consumer group 자체는 매우 가볍다(coordinator 노드의 mapping 하나). 분리 비용이 사실상 0.

**적용 현황**:

| 서비스 | Consumer | Topic | groupId |
|---|---|---|---|
| payment-service | `ConfirmedEventConsumer` | `payment.events.confirmed` | `payment-service` |
| pg-service | `PaymentConfirmConsumer` | `payment.commands.confirm` | `pg-service` |
| pg-service | `PaymentConfirmDlqConsumer` | `payment.commands.confirm.dlq` | `pg-service-dlq` |
| product-service | `StockCommitConsumer` | `payment.events.stock-committed` | `product-service-stock-commit` |
| product-service | `StockRestoreConsumer` | `stock.events.restore` | `product-service-stock-restore` |

**신규 consumer 추가 시**: 새 도메인 플로우라면 새 groupId 를 발급한다. 같은 플로우의 파티션 병렬 소비 확장은 동일 groupId 하에 consumer 인스턴스 증설로 처리한다.

---

---

## Package Layout (K11 기준 — 2026-04-24 확정)

각 서비스의 표준 패키지 배치. pg-service가 reference 구현체이며 payment-service도 동일 기준을 따른다.

```
<svc>/                                   ← com.hyoguoo.paymentplatform.<svc>.*
  presentation/                          ← REST Controller + presentation/port/(inbound interfaces)
  application/
    usecase/                             ← Use Case 클래스 (fine-grained, @Transactional)
    service/                             ← Application Service
    port/in/                             ← Inbound port 인터페이스 (presentation/listener 경유 호출)
    port/out/                            ← Outbound port 인터페이스 (infrastructure 구현)
    dto/                                 ← Application 내부 DTO
    dto/event/                           ← Kafka wire format DTO (외부 직렬화 포맷)
    event/                               ← Spring ApplicationEvent record (JVM 내부 이벤트)
    aspect/annotation/                   ← AOP annotation only
    messaging/                           ← Kafka 토픽 상수 (PaymentTopics 등)
    util/                                ← 유틸 클래스
    config/                              ← Application 레이어 설정 (RetryPolicyProperties 등)
  domain/
    enums/                               ← Domain enum (PaymentEventStatus 등)
    event/                               ← DomainEvent (PaymentHistoryEvent 등)
    dto/, dto/vo/                        ← 도메인 내부 DTO
  infrastructure/
    adapter/http/                        ← HTTP 아웃바운드 어댑터 (ProductHttpAdapter 등)
    adapter/http/dto/                    ← HTTP 어댑터 전용 DTO (request/response record)
    messaging/publisher/                 ← Kafka 발행 어댑터 (KafkaMessagePublisher 등)
    messaging/consumer/                  ← Kafka @KafkaListener 어댑터 (ConfirmedEventConsumer 등)
    listener/                            ← Spring @TransactionalEventListener / @EventListener
                                            (OutboxImmediateEventHandler, PaymentHistoryEventListener,
                                             StockCacheWarmupApplicationEventListener 등)
    repository/                          ← JPA Repository 구현체
    entity/                              ← JPA @Entity
    cache/                               ← Redis 어댑터
    config/                              ← Infrastructure 설정 (KafkaProducerConfig 등)
    aspect/                              ← AOP 구현체 (DomainEventLoggingAspect 등)
    dedupe/                              ← 중복 처리 어댑터 (EventDedupeStoreRedisAdapter 등)
    metrics/                             ← Micrometer 메트릭 어댑터
    idempotency/                         ← 멱등성 어댑터
  core/                                  ← Cross-cutting (payment-service: payment/core/ 패키지)
    common/log/                          ← LogFmt + LogDomain + EventType (ADR-19 복제(b))
    common/exception/                    ← GlobalExceptionHandler + ErrorCode
    common/infrastructure/               ← BaseEntity + SystemLocalDateTimeProvider 등
    common/metrics/                      ← PaymentStateMetrics 등 공통 메트릭
    common/service/port/                 ← LocalDateTimeProvider + UUIDProvider
    config/                              ← AsyncConfig, JpaConfig, WebConfig 등
    config/concurrent/                   ← ContextAwareVirtualThreadExecutors
    response/                            ← BasicResponse, ErrorResponse, ResponseAdvice
  infrastructure/scheduler/              ← @Scheduled 폴링 워커 + SmartLifecycle 워커 (K12: 시간 기반 입력 어댑터 — infrastructure 정석)
```

### 패키지 배치 결정 원칙

| 위치 | 배치 기준 |
|---|---|
| `application/event/` | Spring `ApplicationEventPublisher`로 발행하는 JVM 내부 이벤트 record |
| `application/dto/event/` | Kafka 토픽을 통해 서비스 경계를 넘는 wire format DTO |
| `infrastructure/listener/` | `@TransactionalEventListener` / `@EventListener` 구현체 (Kafka consumer 아님) |
| `infrastructure/messaging/consumer/` | `@KafkaListener` 기반 Kafka 소비 어댑터 |
| `infrastructure/messaging/publisher/` | `KafkaTemplate` 기반 Kafka 발행 어댑터 (Spring ApplicationEventPublisher 포함) |
| `infrastructure/scheduler/` | `@Scheduled` / `SmartLifecycle` 워커 — 시간 기반 입력 어댑터 (K12: scheduler/ top-level 폐지) |
| `application/port/in/` | listener/scheduler가 호출하는 inbound service 인터페이스 (K11: listener/port/ 폐지, K12: scheduler/port/ 폐지) |
| `payment/core/` | payment-service 전용 cross-cutting (pg.core / 타 서비스는 각자 `<svc>/core/`) |

*Package analysis: 2026-04-24 (K11, K12)*

---

*Architecture analysis: 2026-04-14 (updated 2026-04-24 for T3.5-09, T-F4, K11, K12)*
