# Architecture

**Analysis Date:** 2026-03-18

## Pattern Overview

**Overall:** Hexagonal Architecture (Ports and Adapters) with vertical-slice bounded contexts

**Key Characteristics:**
- Four bounded contexts: `payment`, `paymentgateway`, `product`, `user` — each has its own `domain → application → infrastructure → presentation` layer stack
- Ports are Java interfaces defined in `application/port/` (outbound) and `presentation/port/` (inbound); all infrastructure implements those interfaces
- The three async confirm strategies are swapped at startup via `@ConditionalOnProperty(name = "spring.payment.async-strategy")`
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
- Purpose: Orchestrates domain objects; owns all port interfaces; contains all three async strategy service beans
- Location: `src/main/java/com/hyoguoo/paymentplatform/payment/application/`
- Inbound ports (interfaces in `presentation/port/`, implemented here):
  - `PaymentConfirmService` — implemented by ONE of: `PaymentConfirmServiceImpl`, `OutboxAsyncConfirmService`, `KafkaAsyncConfirmService`
  - `PaymentStatusService` — implemented by `PaymentStatusServiceImpl` (always active)
  - `PaymentCheckoutService` — implemented by `PaymentCheckoutServiceImpl`
  - `AdminPaymentService` — implemented by `AdminPaymentServiceImpl`
- Outbound ports (interfaces in `application/port/`, implemented in `infrastructure`):
  - `PaymentEventRepository`, `PaymentOrderRepository`, `PaymentOutboxRepository`, `PaymentProcessRepository`, `PaymentHistoryRepository`
  - `PaymentGatewayPort` — confirm/cancel/status calls to Toss
  - `ProductPort`, `UserPort` — cross-context calls
  - `application/port/out/PaymentConfirmPublisherPort` — Kafka publish abstraction
- Fine-grained use-case services (internal, not exposed as ports):
  - `PaymentCommandUseCase` — all status-changing operations; owns `@PublishDomainEvent` and `@PaymentStatusChange` annotations
  - `PaymentLoadUseCase` — read operations
  - `PaymentOutboxUseCase` — outbox lifecycle
  - `PaymentProcessUseCase` — process-job lifecycle (sync strategy)
  - `PaymentFailureUseCase` — failure routing logic
  - `PaymentTransactionCoordinator` — all `@Transactional` boundary definitions shared across strategies
  - `OrderedProductUseCase`, `OrderedUserUseCase`, `PaymentRecoveryUseCase`, `PaymentCreateUseCase`
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
  - Kafka publisher: `kafka/KafkaConfirmPublisher` implements `PaymentConfirmPublisherPort`
  - Mapper: `PaymentInfrastructureMapper`
- Depends on: `application/port`, `paymentgateway/presentation/port`, `product/presentation/port`, `user/presentation/port`

**Presentation (`payment/presentation`):**
- Purpose: HTTP controllers and inbound port interface definitions
- Location: `src/main/java/com/hyoguoo/paymentplatform/payment/presentation/`
- Contains: `PaymentController`, `PaymentAdminController`, `PaymentPresentationMapper`; request/response DTOs in `presentation/dto/`
- Inbound port interfaces: `presentation/port/PaymentConfirmService`, `PaymentCheckoutService`, `PaymentStatusService`, `AdminPaymentService`
- Depends on: application layer via port interfaces only

**Scheduler (`payment/scheduler`):**
- Purpose: Background scheduled jobs
- Location: `src/main/java/com/hyoguoo/paymentplatform/payment/scheduler/`
- Contains:
  - `OutboxWorker` — `@Scheduled(fixedDelayString)` every 1s; processes `PaymentOutbox` PENDING → IN_FLIGHT → DONE/FAILED; supports parallel mode via Java 21 virtual threads
  - `PaymentScheduler` — `@ConditionalOnProperty`-guarded recovery + expiration jobs
- Port interfaces: `scheduler/port/PaymentExpirationService`, `PaymentRecoverService`
- Depends on: `application` use-case services directly

**Listener (`payment/listener`):**
- Purpose: Kafka consumer (kafka strategy only) and Spring event listener
- Location: `src/main/java/com/hyoguoo/paymentplatform/payment/listener/`
- Contains:
  - `KafkaConfirmListener` — `@RetryableTopic(attempts=6, backoff=exponential, include=PaymentTossRetryableException)` on `payment-confirm`; `@DltHandler` on `payment-confirm-dlq`
  - `PaymentHistoryEventListener` — handles Spring `ApplicationEvent` subtypes from `domain/event/`
- Port interfaces: `listener/port/PaymentHistoryService`
- Depends on: `application` use-case services directly

---

## Async Strategy Selection

All three strategy implementations live in `src/main/java/com/hyoguoo/paymentplatform/payment/application/` and implement the same inbound port `presentation/port/PaymentConfirmService`.

```
spring.payment.async-strategy=sync    → PaymentConfirmServiceImpl   (matchIfMissing=true, default)
spring.payment.async-strategy=outbox  → OutboxAsyncConfirmService
spring.payment.async-strategy=kafka   → KafkaAsyncConfirmService
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

**Outbox strategy (`OutboxAsyncConfirmService`):**
```
PaymentController.confirm()
  → OutboxAsyncConfirmService.confirm()
      1. executePaymentAndStockDecreaseWithOutbox()
         [single TX: READY→IN_PROGRESS + stock-- + PaymentOutbox(PENDING) created atomically]
  ← ResponseEntity.accepted(202)

OutboxWorker.process()  [@Scheduled every 1s]
  Step 0: recoverTimedOutInFlightRecords()      [IN_FLIGHT timeout → reset to PENDING]
  Step 1: findPendingBatch(batchSize=10)
  Step 2: claimToInFlight()                     [REQUIRES_NEW TX, immediately committed]
  Step 3: validateCompletionStatus() + confirmPaymentWithGateway()  [no TX, Toss API]
  Step 4A (success): executePaymentSuccessCompletion() + markDone()
  Step 4B (non-retryable): executePaymentFailureCompensation() + markFailed()
  Step 4C (retryable): incrementRetryOrFail()   [retry up to RETRYABLE_LIMIT=5]
```

**Kafka strategy (`KafkaAsyncConfirmService`):**
```
PaymentController.confirm()
  → KafkaAsyncConfirmService.confirm()
      1. executePaymentAndStockDecrease()
         [single TX: READY→IN_PROGRESS + stock-- atomically]
      2. PaymentConfirmPublisherPort.publish(orderId)
         [KafkaTemplate.send("payment-confirm", orderId, orderId)]
  ← ResponseEntity.accepted(202)

KafkaConfirmListener.consume(orderId)
  [@RetryableTopic: 6 attempts, 1s→2s→4s→…→30s backoff, PaymentTossRetryableException only]
      1. validateCompletionStatus() + confirmPaymentWithGateway()  [no TX, Toss API]
      2. executePaymentSuccessCompletion()   [TX: IN_PROGRESS→DONE]
      on PaymentTossNonRetryableException: executePaymentFailureCompensation()

KafkaConfirmListener.handleDlt(orderId)
  [@DltHandler on "payment-confirm-dlq"]
      executePaymentFailureCompensation()   [stock++, FAILED]
```

---

## Key Design Decisions

**PaymentTransactionCoordinator — shared transactional boundary:**
- A plain `@Service` (no interface) shared by all three strategies, `OutboxWorker`, and `KafkaConfirmListener`
- Every method annotated with `@Transactional`; individual use-case services are not `@Transactional` themselves unless they have single-operation needs
- Key methods: `executePaymentAndStockDecreaseWithOutbox`, `executePaymentAndStockDecrease`, `executeStockDecreaseWithJobCreation`, `executePaymentSuccessCompletion`, `executePaymentFailureCompensation`
- File: `src/main/java/com/hyoguoo/paymentplatform/payment/application/usecase/PaymentTransactionCoordinator.java`

**PaymentConfirmPublisherPort — Kafka isolation:**
- Interface in `application/port/out/PaymentConfirmPublisherPort` with single method `publish(String orderId)`
- Keeps `KafkaTemplate` and topic name (`payment-confirm`) confined to `infrastructure/kafka/KafkaConfirmPublisher`
- Application layer has no knowledge of Kafka topics or serialisation

**PaymentOutbox vs PaymentProcess — separate domain concepts:**
- `PaymentOutbox` (`domain/PaymentOutbox.java`) — dedicated outbox record for the outbox strategy; lifecycle: `PENDING → IN_FLIGHT → DONE/FAILED`; retry limit = 5
- `PaymentProcess` (`domain/PaymentProcess.java`) — tracks the in-flight gateway call for the sync strategy; created atomically with stock decrease; completed/failed via `executePaymentSuccessCompletion`/`executePaymentFailureCompensation`
- These are separate DB tables; `PaymentRecoverServiceImpl` queries target only `PaymentEvent`, not `PaymentOutbox`

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
- `PaymentTossRetryableException` — triggers `@RetryableTopic` backoff (Kafka); OutboxWorker calls `incrementRetryOrFail`
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

*Architecture analysis: 2026-03-18*
