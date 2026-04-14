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
- Used by: `presentation`, `scheduler`, `listener`

**Infrastructure (`payment/infrastructure`):**
- Purpose: JPA entities, Spring Data, gateway adapters, Kafka publisher
- Location: `src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/`
- Contains:
  - JPA entities: `entity/PaymentEventEntity`, `PaymentOrderEntity`, `PaymentOutboxEntity`, `PaymentHistoryEntity`
  - Spring Data interfaces: `repository/JpaPaymentEventRepository`, `JpaPaymentOrderRepository`, `JpaPaymentOutboxRepository`, `JpaPaymentHistoryRepository`
  - Port implementations: `repository/PaymentEventRepositoryImpl`, `PaymentOutboxRepositoryImpl`, etc.
  - Gateway strategy: `gateway/PaymentGatewayStrategy` (interface), `PaymentGatewayFactory`, `PaymentGatewayProperties`; concrete: `gateway/toss/TossPaymentGatewayStrategy`, `gateway/nicepay/NicepayPaymentGatewayStrategy`
  - `PaymentGatewayType` enum is in `domain/enums/` (TOSS, NICEPAY); `PaymentEvent.gatewayType` stores per-event PG 선택
  - Cross-context adapters: `internal/InternalPaymentGatewayAdapter` implements `PaymentGatewayPort`, `InternalProductAdapter` implements `ProductPort`, `InternalUserAdapter` implements `UserPort`
  - Outbox 즉시 처리 발행자: `publisher/OutboxImmediatePublisher` implements `PaymentConfirmPublisherPort` (Spring `ApplicationEventPublisher` 기반)
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

**Scheduler (`payment/scheduler`):**
- Purpose: Background scheduled jobs and lifecycle-managed async workers
- Location: `src/main/java/com/hyoguoo/paymentplatform/payment/scheduler/`
- Contains:
  - `OutboxImmediateWorker` — `SmartLifecycle` 구현체; 앱 시작 시 N개(기본 200개)의 VT/PT 워커 스레드를 생성해 `PaymentConfirmChannel`에서 `take()`로 orderId를 꺼내 처리; VT/PT는 `outbox.channel.virtual-threads`로 제어
  - `OutboxProcessingService` — `OutboxImmediateWorker`와 `OutboxWorker` 양쪽이 공유하는 단일 처리 서비스; `claimToInFlight → getStatus → RecoveryDecision → applyDecision (success/retry/quarantine/FCG)` 복구 사이클 로직을 캡슐화
  - `OutboxWorker` — `@Scheduled(fixedDelayString)` 폴백 전용; `PaymentConfirmChannel` 오버플로우 시 누락된 PENDING 레코드를 배치로 처리; 내부적으로 `OutboxProcessingService.process()` 위임
  - `PaymentScheduler` — 만료 스케줄러; `@Scheduled(fixedRateString)` 기본 5분마다 READY 상태 오래된 결제를 만료 처리
- Port interfaces: `scheduler/port/PaymentExpirationService`
- Depends on: `application` use-case services directly, `core/channel/PaymentConfirmChannel`

**Listener (`payment/listener`):**
- Purpose: Outbox 즉시 처리 핸들러 및 Spring 이벤트 리스너
- Location: `src/main/java/com/hyoguoo/paymentplatform/payment/listener/`
- Contains:
  - `OutboxImmediateEventHandler` — `@TransactionalEventListener(AFTER_COMMIT)`; confirm() 트랜잭션 커밋 직후 `PaymentConfirmChannel.offer(orderId)`를 호출해 큐에 적재; 큐 가득 찬 경우 warn 로그 — OutboxWorker(polling)가 처리
  - `PaymentHistoryEventListener` — handles Spring `ApplicationEvent` subtypes from `domain/event/`
- Port interfaces: `listener/port/PaymentHistoryService`
- Depends on: `core/channel/PaymentConfirmChannel`

---

## Confirm Flow (Outbox 단일 전략)

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

OutboxProcessingService.process(orderId)  [ImmediateWorker/OutboxWorker 공유 — 복구 사이클]
  Step 1: claimToInFlight(orderId)           [atomic UPDATE PENDING→IN_FLIGHT, REQUIRES_NEW TX]
  Step 2: loadPaymentEvent(orderId)          [실패 시 incrementRetryOrFail → return]
  Step 3: 로컬 종결 재진입 차단              [isTerminal() → rejectReentry(outbox toDone)]
  Step 4: retryCount==0 → 바로 confirm (PG 선조회 불필요)
          retryCount>=1 → getPaymentStatusByOrderId(orderId, gatewayType) [PG 상태 선행 조회, no TX]
          → RecoveryDecision.from(event, result, retryCount, maxRetries)
  Step 5: applyDecision — RecoveryDecision.Type에 따라 분기:
    COMPLETE_SUCCESS        → executePaymentSuccessCompletionWithOutbox()
    COMPLETE_FAILURE        → executePaymentFailureCompensationWithOutbox() (D12 가드)
    ATTEMPT_CONFIRM         → confirmPaymentWithGateway() → 2차 분기 (SUCCESS/FAILURE/RETRYABLE)
    RETRY_LATER (미소진)    → executePaymentRetryWithOutbox()
    RETRY_LATER (소진)      → FCG(Final Confirmation Gate) → getStatus 1회 재조회 → success/failure/quarantine
    GUARD_MISSING_APPROVED_AT (미소진) → executePaymentRetryWithOutbox()
    GUARD_MISSING_APPROVED_AT (소진)   → FCG → quarantine
    QUARANTINE              → FCG → quarantine
    REJECT_REENTRY          → rejectReentry(outbox toDone)

OutboxWorker.process()  [@Scheduled every 5s — 폴백 전용]
  — 폴백 경로: 큐 오버플로우 또는 서버 재시작으로 누락된 PENDING 레코드 배치 처리
  Step 0: recoverTimedOutInFlightRecords()  [IN_FLIGHT timeout → reset to PENDING]
  Step 1: findPendingBatch(batchSize)       [기본 50건]
  per record: OutboxProcessingService.process(outbox.getOrderId())
```

---

## Key Design Decisions

**PaymentTransactionCoordinator — shared transactional boundary:**
- A plain `@Service` (no interface) shared by `OutboxAsyncConfirmService`, `OutboxWorker`, and `OutboxImmediateWorker`
- Every method annotated with `@Transactional`; individual use-case services are not `@Transactional` themselves unless they have single-operation needs
- Key methods: `executePaymentAndStockDecreaseWithOutbox`, `executePaymentSuccessCompletionWithOutbox`, `executePaymentFailureCompensationWithOutbox` (D12 가드: TX 내 outbox/event 재조회 후 조건 충족 시에만 재고 복구), `executePaymentRetryWithOutbox`, `executePaymentQuarantineWithOutbox` (격리 전이: outbox FAILED + event QUARANTINED)
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

**Cross-Context Communication pattern:**
- `payment` calls `paymentgateway` via `InternalPaymentGatewayAdapter` → `PaymentGatewayInternalReceiver` (Toss) / `NicepayGatewayInternalReceiver` (NicePay) (internal Java facades, not HTTP endpoints from outside)
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

**Strategy — Recovery Cycle:** `OutboxProcessingService`는 PG 상태 선행 조회(`getPaymentStatusByOrderId`) → `RecoveryDecision` 값 객체로 복구 결정을 수립한다.
- `RecoveryDecision.Type`: `COMPLETE_SUCCESS`, `COMPLETE_FAILURE`, `ATTEMPT_CONFIRM`, `RETRY_LATER`, `GUARD_MISSING_APPROVED_AT`, `QUARANTINE`, `REJECT_REENTRY`
- **FCG(Final Confirmation Gate)**: retry 소진 시 getStatus 1회 재조회 → 판별 가능하면 success/failure, 불가하면 quarantine

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

**Exception handlers:**
- `src/main/java/com/hyoguoo/paymentplatform/core/common/exception/GlobalExceptionHandler.java`

---

## Cross-Cutting Concerns

**Logging:** `LogFmt` (`core/common/log/LogFmt.java`) produces structured `key=value` lines; `MaskingPatternLayout` masks sensitive values in logback
**Validation:** Domain object guard clauses (`PaymentEvent.execute()`, `done(approvedAt null 가드)`, `fail(종결 no-op)`, `expire()`, `toRetrying()`, `quarantine()`)
**Transaction boundary:** All multi-step DB operations go through `PaymentTransactionCoordinator`
**Metrics:** `PaymentStateMetrics`, `PaymentHealthMetrics`, `PaymentTransitionMetrics`, `TossApiMetrics`, `PaymentQuarantineMetrics` (격리 카운터) in `core/common/metrics/`

---

*Architecture analysis: 2026-04-14*
