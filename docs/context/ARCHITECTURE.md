# Architecture

**Analysis Date:** 2026-03-31

## Pattern Overview

**Overall:** Hexagonal Architecture (Ports and Adapters) with vertical-slice bounded contexts

**Key Characteristics:**
- Four bounded contexts: `payment`, `paymentgateway`, `product`, `user` — each has its own `domain → application → infrastructure → presentation` layer stack
- Ports are Java interfaces defined in `application/port/` (outbound) and `presentation/port/` (inbound); all infrastructure implements those interfaces
- Two async confirm strategies are swapped at startup via `@ConditionalOnProperty(name = "spring.payment.async-strategy")`
- Cross-context calls never share a repository; they go through `presentation/port` interfaces of the target context, consumed by `infrastructure/internal/` adapters in the calling context

---

## Layers (payment context — the primary context)

**Domain (`payment/domain`):**
- Purpose: Pure business logic, zero Spring dependencies
- Location: `src/main/java/com/hyoguoo/paymentplatform/payment/domain/`
- Contains: `PaymentEvent`, `PaymentOrder`, `PaymentOutbox`, `PaymentHistory`, `PaymentProcess` aggregates; status enums in `domain/enums/`; cross-context DTOs in `domain/dto/` and `domain/dto/vo/`; Spring `ApplicationEvent` subtypes in `domain/event/`
- Depends on: nothing outside `domain`
- Used by: `application` use-case services

**Application (`payment/application`):**
- Purpose: Orchestrates domain objects; owns all port interfaces; contains async strategy service beans
- Location: `src/main/java/com/hyoguoo/paymentplatform/payment/application/`
- Inbound ports (interfaces in `presentation/port/`, implemented here):
  - `PaymentConfirmService` — implemented by ONE of: `PaymentConfirmServiceImpl`, `OutboxAsyncConfirmService`
  - `PaymentStatusService` — implemented by `PaymentStatusServiceImpl` (always active)
  - `PaymentCheckoutService` — implemented by `PaymentCheckoutServiceImpl`
  - `AdminPaymentService` — implemented by `AdminPaymentServiceImpl`
- Outbound ports (interfaces in `application/port/`, implemented in `infrastructure`):
  - `PaymentEventRepository`, `PaymentOrderRepository`, `PaymentOutboxRepository`, `PaymentProcessRepository`, `PaymentHistoryRepository`
  - `PaymentGatewayPort` — confirm/cancel/status calls to Toss
  - `ProductPort`, `UserPort` — cross-context calls
  - `application/port/out/PaymentConfirmPublisherPort` — Outbox 즉시 처리 이벤트 발행 추상화
- Fine-grained use-case services (internal, not exposed as ports):
  - `PaymentCommandUseCase` — all status-changing operations; owns `@PublishDomainEvent` and `@PaymentStatusChange` annotations
  - `PaymentLoadUseCase` — read operations
  - `PaymentOutboxUseCase` — outbox lifecycle
  - `PaymentProcessUseCase` — process-job lifecycle (sync strategy)
  - `PaymentFailureUseCase` — failure routing logic
  - `PaymentTransactionCoordinator` — all `@Transactional` boundary definitions shared across strategies; also owns `claimToInFlight` delegation pattern
  - `OrderedProductUseCase`, `OrderedUserUseCase`, `PaymentCreateUseCase`
- Depends on: `domain`, `core/common`
- Used by: `presentation`, `scheduler`, `listener`

**Infrastructure (`payment/infrastructure`):**
- Purpose: JPA entities, Spring Data, gateway adapters, Kafka publisher
- Location: `src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/`
- Contains:
  - JPA entities: `entity/PaymentEventEntity`, `PaymentOrderEntity`, `PaymentOutboxEntity`, `PaymentHistoryEntity`, `PaymentProcessEntity`
  - Spring Data interfaces: `repository/JpaPaymentEventRepository`, `JpaPaymentOrderRepository`, `JpaPaymentOutboxRepository`, `JpaPaymentProcessRepository`, `JpaPaymentHistoryRepository`
  - Port implementations: `repository/PaymentEventRepositoryImpl`, `PaymentOutboxRepositoryImpl`, `PaymentProcessRepositoryImpl`, etc.
  - Gateway strategy: `gateway/PaymentGatewayStrategy` (interface), `PaymentGatewayFactory`, `PaymentGatewayProperties`, `PaymentGatewayType`; concrete: `gateway/toss/TossPaymentGatewayStrategy`
  - Cross-context adapters: `internal/InternalPaymentGatewayAdapter` implements `PaymentGatewayPort`, `InternalProductAdapter` implements `ProductPort`, `InternalUserAdapter` implements `UserPort`
  - Outbox 즉시 처리 발행자: `publisher/OutboxImmediatePublisher` implements `PaymentConfirmPublisherPort` (Spring `ApplicationEventPublisher` 기반)
  - Mapper: `PaymentInfrastructureMapper`
- Depends on: `application/port`, `paymentgateway/presentation/port`, `product/presentation/port`, `user/presentation/port`

**Presentation (`payment/presentation`):**
- Purpose: HTTP controllers and inbound port interface definitions
- Location: `src/main/java/com/hyoguoo/paymentplatform/payment/presentation/`
- Contains: `PaymentController`, `PaymentAdminController`, `PaymentPresentationMapper`; request/response DTOs in `presentation/dto/`
- Inbound port interfaces: `presentation/port/PaymentConfirmService`, `PaymentCheckoutService`, `PaymentStatusService`, `AdminPaymentService`
- Depends on: application layer via port interfaces only

**Scheduler (`payment/scheduler`):**
- Purpose: Background scheduled jobs and lifecycle-managed async workers
- Location: `src/main/java/com/hyoguoo/paymentplatform/payment/scheduler/`
- Contains:
  - `OutboxImmediateWorker` — `SmartLifecycle` 구현체; 앱 시작 시 N개(기본 200개)의 VT/PT 워커 스레드를 생성해 `PaymentConfirmChannel`에서 `take()`로 orderId를 꺼내 처리; VT/PT는 `outbox.channel.virtual-threads`로 제어
  - `OutboxProcessingService` — `OutboxImmediateWorker`와 `OutboxWorker` 양쪽이 공유하는 단일 처리 서비스; `claimToInFlight → Toss API → success/retry/failure` 로직을 캡슐화
  - `OutboxWorker` — `@Scheduled(fixedDelayString)` 폴백 전용; `PaymentConfirmChannel` 오버플로우 시 누락된 PENDING 레코드를 배치로 처리; 내부적으로 `OutboxProcessingService.process()` 위임
  - `PaymentScheduler` — 만료 스케줄러; `@Scheduled(fixedRateString)` 기본 5분마다 READY 상태 오래된 결제를 만료 처리
- Port interfaces: `scheduler/port/PaymentExpirationService`
- Depends on: `application` use-case services directly, `core/channel/PaymentConfirmChannel`

**Listener (`payment/listener`):**
- Purpose: Outbox 즉시 처리 핸들러 및 Spring 이벤트 리스너
- Location: `src/main/java/com/hyoguoo/paymentplatform/payment/listener/`
- Contains:
  - `OutboxImmediateEventHandler` — `@TransactionalEventListener(AFTER_COMMIT)`; confirm() 트랜잭션 커밋 직후 `PaymentConfirmChannel.offer(orderId)`를 호출해 큐에 적재; 큐 가득 찬 경우 warn 로그 — OutboxWorker(polling)가 처리 (outbox 전략 전용)
  - `PaymentHistoryEventListener` — handles Spring `ApplicationEvent` subtypes from `domain/event/`
- Port interfaces: `listener/port/PaymentHistoryService`
- Depends on: `core/channel/PaymentConfirmChannel`

---

## Async Strategy Selection

두 가지 전략 구현체가 `src/main/java/com/hyoguoo/paymentplatform/payment/application/`에 위치하며, 동일한 인바운드 포트 `presentation/port/PaymentConfirmService`를 구현한다.

```
spring.payment.async-strategy=sync    → PaymentConfirmServiceImpl   (matchIfMissing=true, default)
spring.payment.async-strategy=outbox  → OutboxAsyncConfirmService
```

`PaymentController` is strategy-unaware. It reads `PaymentConfirmAsyncResult.responseType`:
- `ResponseType.SYNC_200` → `ResponseEntity.ok(200)`
- `ResponseType.ASYNC_202` → `ResponseEntity.accepted(202)`

---

## Data Flow Per Strategy

**Sync strategy (`PaymentConfirmServiceImpl`):**
```
PaymentController.confirm()
  → PaymentConfirmServiceImpl.confirm()
      1. executeStockDecreaseWithJobCreation()  [TX: stock-- + PaymentProcess(PROCESSING) created]
      2. executePayment()                       [TX: READY→IN_PROGRESS saved]
      3. validateCompletionStatus()             [no TX: Toss status check]
      4. confirmPaymentWithGateway()            [no TX: Toss /confirm API call]
      5. executePaymentSuccessCompletion()      [TX: IN_PROGRESS→DONE + PaymentProcess completed]
  ← ResponseEntity.ok(200)
```

**Outbox strategy (`OutboxAsyncConfirmService` + `PaymentConfirmChannel` + `OutboxImmediateWorker` + `OutboxWorker`):**
```
PaymentController.confirm()
  → OutboxAsyncConfirmService.confirm()  [@Transactional]
      1. executePaymentAndStockDecreaseWithOutbox()
         [single TX: READY→IN_PROGRESS + stock-- + PaymentOutbox(PENDING) created atomically]
      2. PaymentConfirmPublisherPort.publish(orderId)
         [OutboxImmediatePublisher: ApplicationEventPublisher.publishEvent(PaymentConfirmEvent)]
         [이벤트는 TX 커밋 후 AFTER_COMMIT 단계에서 발동]
  ← ResponseEntity.accepted(202)

OutboxImmediateEventHandler.handle(event)  [@TransactionalEventListener(AFTER_COMMIT)]
  — TX 커밋 직후 HTTP 요청 스레드에서 실행 (비블로킹 — channel.offer만 호출)
  channel.offer(orderId)
    → true:  LinkedBlockingQueue에 적재 완료 — OutboxImmediateWorker가 비동기 처리
    → false: 큐 가득 참 (warn 로그) — OutboxWorker(polling)가 폴백 처리

OutboxImmediateWorker  [SmartLifecycle — 앱 시작 시 N개 VT/PT 워커 스레드 생성]
  — 정상 경로: channel.take()로 blocking wait → 꺼내는 즉시 처리
  workerLoop() { channel.take() → OutboxProcessingService.process(orderId) }

OutboxProcessingService.process(orderId)  [ImmediateWorker/OutboxWorker 공유]
  Step 1: claimToInFlight(orderId)    [atomic UPDATE PENDING→IN_FLIGHT, REQUIRES_NEW TX]
  Step 2: loadPaymentEvent(orderId)   [실패 시 incrementRetryOrFail → return]
  Step 3: confirmPaymentWithGateway() [no TX, Toss API]
  Step 4A (success):       executePaymentSuccessCompletionWithOutbox()
  Step 4B (non-retryable): executePaymentFailureCompensationWithOutbox()
  Step 4C (retryable):     incrementRetryOrFail()   [retry up to RETRYABLE_LIMIT=5]

OutboxWorker.process()  [@Scheduled every 2s — 폴백 전용]
  — 폴백 경로: 큐 오버플로우 또는 서버 재시작으로 누락된 PENDING 레코드 배치 처리
  Step 0: recoverTimedOutInFlightRecords()  [IN_FLIGHT timeout → reset to PENDING]
  Step 1: findPendingBatch(batchSize)       [기본 50건]
  per record: OutboxProcessingService.process(outbox.getOrderId())
```

---

## Key Design Decisions

**PaymentTransactionCoordinator — shared transactional boundary:**
- A plain `@Service` (no interface) shared by both strategies, `OutboxWorker`, and `OutboxImmediateEventHandler`
- Every method annotated with `@Transactional`; individual use-case services are not `@Transactional` themselves unless they have single-operation needs
- Key methods: `executePaymentAndStockDecreaseWithOutbox`, `executePaymentAndStockDecrease`, `executeStockDecreaseWithJobCreation`, `executePaymentSuccessCompletion`, `executePaymentFailureCompensation`
- File: `src/main/java/com/hyoguoo/paymentplatform/payment/application/usecase/PaymentTransactionCoordinator.java`

**PaymentConfirmChannel — HTTP 스레드와 Worker 스레드 디커플링:**
- `core/channel/PaymentConfirmChannel` — `LinkedBlockingQueue<String>` 래퍼; `offer(orderId)`(non-blocking), `take()`(blocking) 제공
- `OutboxImmediateEventHandler`가 TX 커밋 후 `offer()`로 큐에 적재 → HTTP 스레드 즉시 해방
- `OutboxImmediateWorker`의 VT/PT 워커들이 `take()`로 blocking wait → Toss API 호출
- 큐 용량은 `outbox.channel.capacity`(기본 2000)로 제어; 오버플로우 시 OutboxWorker 폴백

**PaymentConfirmPublisherPort — Outbox 즉시 처리 이벤트 추상화:**
- Interface in `application/port/out/PaymentConfirmPublisherPort` with single method `publish(String orderId)`
- `OutboxImmediatePublisher` (infrastructure 계층)가 `ApplicationEventPublisher.publishEvent(PaymentConfirmEvent)`로 구현
- Application 계층은 Spring 이벤트 발행 세부 구현을 알지 못함

**PaymentOutbox vs PaymentProcess — separate domain concepts:**
- `PaymentOutbox` (`domain/PaymentOutbox.java`) — dedicated outbox record for the outbox strategy; lifecycle: `PENDING → IN_FLIGHT → DONE/FAILED`; retry limit = 5
- `PaymentProcess` (`domain/PaymentProcess.java`) — tracks the in-flight gateway call for the sync strategy; created atomically with stock decrease; completed/failed via `executePaymentSuccessCompletion`/`executePaymentFailureCompensation`
- These are separate DB tables; the two domain concepts serve different strategies and are never mixed

**Cross-Context Communication pattern:**
- `payment` calls `paymentgateway` via `InternalPaymentGatewayAdapter` → `PaymentGatewayInternalReceiver` (a `@RestController` used as an internal Java facade, not an HTTP endpoint from outside)
- `payment` calls `product` via `InternalProductAdapter` → `ProductInternalReceiver`
- `payment` calls `user` via `InternalUserAdapter` → `UserInternalReceiver`
- No cross-context JPA joins

**AOP-Driven Observability:**
- `@PublishDomainEvent` on `PaymentCommandUseCase` → `DomainEventLoggingAspect` publishes Spring `ApplicationEvent`
- `@PaymentStatusChange` on `PaymentCommandUseCase` → `PaymentStatusMetricsAspect` records Micrometer counters
- `@TossApiMetric` → `TossApiMetricsAspect` records API latency
- Aspect classes: `src/main/java/com/hyoguoo/paymentplatform/core/common/aspect/` and `core/common/metrics/aspect/`

**Benchmark profile:**
- `@Profile("benchmark")` in `mock/BenchmarkConfig.java` replaces the real `HttpOperator` with `FakeTossHttpOperator` (configurable delay)
- Files: `src/main/java/com/hyoguoo/paymentplatform/mock/BenchmarkConfig.java`, `FakeTossHttpOperator.java`

---

## Error Handling

**Strategy:** Exception type encodes retryability
- `PaymentTossRetryableException` — OutboxImmediateEventHandler/OutboxWorker calls `incrementRetryOrFail` (최대 5회)
- `PaymentTossNonRetryableException` — immediate `executePaymentFailureCompensation` (stock restored, FAILED)
- `PaymentOrderedProductStockException` — stock exhausted; payment marked FAILED, no compensation (stock was never decremented)
- `PaymentStatusException` / `PaymentValidException` — domain guard violations; handler returns 4xx

**Compensation flow:** `executePaymentFailureCompensation` atomically increments stock (`increaseStockForOrders`), fails `PaymentProcess` if present, and marks `PaymentEvent` as FAILED.

**Exception handlers:**
- `src/main/java/com/hyoguoo/paymentplatform/payment/exception/common/PaymentExceptionHandler.java`
- `src/main/java/com/hyoguoo/paymentplatform/core/common/exception/GlobalExceptionHandler.java`

---

## Cross-Cutting Concerns

**Logging:** `LogFmt` (`core/common/log/LogFmt.java`) produces structured `key=value` lines; `MaskingPatternLayout` masks sensitive values in logback
**Validation:** Domain object guard clauses (`PaymentEvent.execute()`, `done()`, `fail()`, `unknown()`, `expire()`)
**Transaction boundary:** All multi-step DB operations go through `PaymentTransactionCoordinator`
**Metrics:** `PaymentStateMetrics`, `PaymentHealthMetrics`, `PaymentTransitionMetrics`, `TossApiMetrics` in `core/common/metrics/`

---

*Architecture analysis: 2026-03-31*
