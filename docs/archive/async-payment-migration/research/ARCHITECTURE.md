# Architecture Patterns

**Domain:** Async payment processing — Outbox Pattern + Kafka consumer in Hexagonal Architecture
**Researched:** 2026-03-14

---

## Recommended Architecture

The new async layer wraps the existing synchronous confirm flow behind a single port interface (`PaymentConfirmAsyncPort`). The controller dispatches to this port. Depending on which adapter is wired by Spring configuration, the request is handled synchronously, written to an outbox record, or published to Kafka.

```
POST /confirm
  → PaymentController
  → PaymentConfirmAsyncPort          (presentation/port — new interface)
        |
        ├── SyncConfirmAdapter       (infrastructure — delegates to PaymentConfirmServiceImpl unchanged)
        ├── OutboxConfirmAdapter     (infrastructure — saves PaymentOutboxRecord, returns 202)
        └── KafkaConfirmAdapter     (infrastructure — publishes to Kafka topic, returns 202)

[Async path only]
OutboxWorker (@Scheduled)            (scheduler — polls PaymentProcess PENDING records)
  → PaymentConfirmServiceImpl        (reuses existing sync confirm logic)

KafkaConfirmListener (@KafkaListener) (infrastructure/kafka — inbound driven adapter)
  → PaymentConfirmServiceImpl        (reuses existing sync confirm logic)
```

---

## Component Boundaries

### Presentation Layer

| Component | Location | Responsibility |
|-----------|----------|---------------|
| `PaymentController` | `presentation/` | Existing. Change `confirm()` to inject `PaymentConfirmAsyncPort` instead of `PaymentConfirmService`. Return 200 (sync) or 202 (async) based on adapter behavior. |
| `PaymentConfirmAsyncPort` | `presentation/port/` | New interface replacing `PaymentConfirmService` for the confirm endpoint. Method signature: `PaymentConfirmAsyncResult confirm(PaymentConfirmCommand cmd)`. |
| `PaymentConfirmAsyncResult` | `application/dto/response/` | New DTO. Carries `orderId`, `status` (ACCEPTED or DONE), `amount` (null when async). |

### Application Layer

| Component | Location | Responsibility |
|-----------|----------|---------------|
| `PaymentConfirmServiceImpl` | `application/` | **No change.** All three adapters ultimately call this same service for actual Toss API execution. |
| `PaymentLoadUseCase` | `application/usecase/` | Add `getPaymentStatusByOrderId(String orderId)` for the new status-polling endpoint. |

### Domain Layer

| Component | Location | Responsibility |
|-----------|----------|---------------|
| `PaymentProcess` | `domain/` | **Extend, do not replace.** Add `PENDING` status for outbox use case. `PENDING → PROCESSING → COMPLETED/FAILED`. This keeps the Outbox record inside the existing job-tracking entity. |
| `PaymentProcessStatus` (enum) | `domain/enums/` | Add `PENDING` value. Existing `PROCESSING`, `COMPLETED`, `FAILED` are unchanged. |

**Outbox entity placement decision:** `PaymentProcess` lives in the domain layer and already functions as a job tracker. Adding `PENDING` status turns it into the outbox record without introducing a new table or entity class. The infrastructure layer (`PaymentProcessRepositoryImpl`) requires no structural change — only new query methods for `findAllByStatus(PENDING)`. This is the correct placement: the concept of "a payment job that has been requested but not yet executed" is a domain concern, not an infrastructure concern.

### Infrastructure Layer (Adapters)

| Component | Location | Responsibility |
|-----------|----------|---------------|
| `SyncConfirmAdapter` | `infrastructure/async/` | Implements `PaymentConfirmAsyncPort`. Delegates directly to `PaymentConfirmServiceImpl`. Returns `DONE` result. Used when `spring.payment.async-strategy=sync`. |
| `OutboxConfirmAdapter` | `infrastructure/async/` | Implements `PaymentConfirmAsyncPort`. In a single transaction: decrease stock, create `PaymentProcess(PENDING)`. Returns `ACCEPTED`. No Toss API call here. |
| `KafkaConfirmAdapter` | `infrastructure/async/` | Implements `PaymentConfirmAsyncPort`. In a single transaction: decrease stock, publish `PaymentConfirmCommand` to Kafka topic `payment.confirm`. Returns `ACCEPTED`. |
| `KafkaConfirmListener` | `infrastructure/kafka/` | `@KafkaListener`. Inbound driving adapter. Receives message, calls `PaymentConfirmServiceImpl`. Handles at-least-once delivery (idempotency via `PaymentProcess` existence check). |
| `KafkaProducerAdapter` | `infrastructure/kafka/` | Outbound driven adapter. Wraps `KafkaTemplate`. Implements `PaymentEventPublisherPort` (new port in `application/port/`). |
| `PaymentEventPublisherPort` | `application/port/` | New outbound port interface. `void publish(PaymentConfirmCommand cmd)`. Infrastructure layer implements with `KafkaTemplate`. |

### Scheduler Layer (Outbox Worker)

| Component | Location | Responsibility |
|-----------|----------|---------------|
| `OutboxWorkerService` | `scheduler/port/` | New port interface. `void processPendingJobs()`. |
| `OutboxWorkerServiceImpl` | `application/` | Implements `OutboxWorkerService`. Finds all `PaymentProcess(PENDING)`, calls `PaymentConfirmServiceImpl` for each. Transitions record `PENDING → PROCESSING` before calling. |
| `PaymentScheduler` | `scheduler/` | Add `@Scheduled` + `@ConditionalOnProperty` method for outbox worker. Pattern already exists for `recoverRetryablePayment()` — reuse exactly. |

---

## Data Flow

### Flow 1: Sync Strategy (unchanged behavior, 200 OK)

```
PaymentController.confirm()
  → SyncConfirmAdapter.confirm()
      → PaymentConfirmServiceImpl.confirm()   [existing full sync flow]
  ← PaymentConfirmAsyncResult{status=DONE, amount=X}
← 200 OK
```

### Flow 2: Outbox Strategy (202 Accepted)

```
PaymentController.confirm()
  → OutboxConfirmAdapter.confirm()
      @Transactional {
        OrderedProductUseCase.decreaseStockForOrders()   [pessimistic lock, same as sync]
        PaymentProcessUseCase.createPendingJob(orderId)  [PaymentProcess status=PENDING]
      }
  ← PaymentConfirmAsyncResult{status=ACCEPTED}
← 202 Accepted

[Background — OutboxWorkerServiceImpl @Scheduled]
  find PaymentProcess WHERE status=PENDING
  for each record:
    mark PROCESSING
    PaymentConfirmServiceImpl.confirm()     [executes Toss API, handles all existing error paths]
    → on success: COMPLETED
    → on retryable failure: back to PENDING (retry on next poll)
    → on non-retryable failure: FAILED
```

### Flow 3: Kafka Strategy (202 Accepted)

```
PaymentController.confirm()
  → KafkaConfirmAdapter.confirm()
      @Transactional {
        OrderedProductUseCase.decreaseStockForOrders()   [pessimistic lock]
        PaymentEventPublisherPort.publish(confirmCommand) [KafkaTemplate → topic payment.confirm]
      }
  ← PaymentConfirmAsyncResult{status=ACCEPTED}
← 202 Accepted

[Background — KafkaConfirmListener @KafkaListener]
  consume PaymentConfirmCommand from topic payment.confirm
  idempotency check: PaymentProcessRepository.existsByOrderId()
  PaymentConfirmServiceImpl.confirm()   [same existing flow]
  commit offset on success
```

### Flow 4: Status Polling (new endpoint)

```
GET /api/v1/payments/{orderId}/status
  → PaymentController.getStatus()
  → PaymentLoadUseCase.getPaymentStatusByOrderId()
  ← PaymentEventStatus (READY|IN_PROGRESS|DONE|FAILED|UNKNOWN|EXPIRED)
← 200 OK
```

---

## Dependency Direction (unchanged rule)

```
Presentation → Application → Domain ← Infrastructure
```

- `PaymentConfirmAsyncPort` lives in `presentation/port/` — infrastructure adapters implement it (inversion of control, standard for this codebase).
- `PaymentEventPublisherPort` lives in `application/port/` — infrastructure Kafka adapter implements it.
- `OutboxWorkerService` lives in `scheduler/port/` — application service implements it (matches existing `PaymentRecoverService` / `PaymentExpirationService` pattern).
- `KafkaConfirmListener` lives in `infrastructure/kafka/` — it calls `PaymentConfirmServiceImpl` through `presentation/port/PaymentConfirmService` to preserve the inbound adapter contract (or directly via application service since it's infrastructure-to-application, which is the driven direction).

---

## Strategy Switching Mechanism

`@ConditionalOnProperty` on each adapter `@Component`. One and only one adapter bean is registered per application start. No `if/else` in controller logic.

```yaml
# application.yml
spring:
  payment:
    async-strategy: sync   # sync | outbox | kafka
```

```java
// infrastructure/async/SyncConfirmAdapter.java
@Component
@ConditionalOnProperty(name = "spring.payment.async-strategy", havingValue = "sync", matchIfMissing = true)
public class SyncConfirmAdapter implements PaymentConfirmAsyncPort { ... }

// infrastructure/async/OutboxConfirmAdapter.java
@Component
@ConditionalOnProperty(name = "spring.payment.async-strategy", havingValue = "outbox")
public class OutboxConfirmAdapter implements PaymentConfirmAsyncPort { ... }

// infrastructure/kafka/KafkaConfirmAdapter.java
@Component
@ConditionalOnProperty(name = "spring.payment.async-strategy", havingValue = "kafka")
public class KafkaConfirmAdapter implements PaymentConfirmAsyncPort { ... }
```

`matchIfMissing = true` on the sync adapter ensures existing behavior is preserved with no config change — critical for not breaking existing flows.

The Outbox scheduler and Kafka listener should also be gated:

```java
// PaymentScheduler — new method
@Scheduled(fixedDelayString = "${scheduler.outbox-worker.interval-ms:5000}")
@ConditionalOnProperty(name = "spring.payment.async-strategy", havingValue = "outbox")
public void processOutboxJobs() {
    outboxWorkerService.processPendingJobs();
}
```

```java
// KafkaConfirmListener
@Component
@ConditionalOnProperty(name = "spring.payment.async-strategy", havingValue = "kafka")
public class KafkaConfirmListener { ... }
```

This ensures the outbox scheduler thread is dormant when running sync or Kafka strategy, and the Kafka listener is not registered when running sync or outbox strategy.

---

## Patterns to Follow

### Pattern 1: Reuse `PaymentConfirmServiceImpl` as the single execution point

**What:** All three adapter paths — sync (direct), outbox (background worker), kafka (consumer) — converge on the same `PaymentConfirmServiceImpl.confirm()` call.

**When:** Always. Do not duplicate the Toss API call logic.

**Why:** The existing service already handles all error paths (retryable, non-retryable, unknown), state transitions (`READY→IN_PROGRESS→DONE`), and compensation. Duplicating this logic in Kafka listener or outbox worker is a maintenance trap.

### Pattern 2: Stock decrease in the accepting transaction, Toss API call in the processing phase

**What:** Stock decrease and outbox record creation (or Kafka publish) happen in the same transaction as the confirm request. Toss API call happens later in the background.

**When:** Outbox and Kafka adapters only.

**Why:** This preserves the atomicity guarantee: either both the stock is reserved and the job is queued, or neither happens. The existing `PaymentTransactionCoordinator.executeStockDecreaseWithJobCreation()` already handles this transaction boundary — the adapters should call it directly.

### Pattern 3: Port interface in `presentation/port/`, not `application/port/`

**What:** `PaymentConfirmAsyncPort` goes in `presentation/port/` following the existing pattern (`PaymentConfirmService`, `PaymentCheckoutService`).

**When:** All controller-facing service interfaces.

**Why:** This is how the codebase separates "what the controller calls" from "what the service depends on". The existing `PaymentConfirmService` in `presentation/port/` sets the precedent.

---

## Anti-Patterns to Avoid

### Anti-Pattern 1: New Outbox entity separate from `PaymentProcess`

**What:** Creating a new `OutboxEvent` domain entity and `outbox_events` table.

**Why bad:** `PaymentProcess` already tracks payment jobs with status, timestamps, and failure reasons. A separate outbox table duplicates this concern and adds a join to every status query. The existing `PaymentRecoverServiceImpl` already queries `PaymentProcess` — the outbox worker fits the same model.

**Instead:** Add `PENDING` status to `PaymentProcessStatus`. The record with `status=PENDING` is the outbox entry. The existing `findAllByStatus()` query is already in `PaymentProcessRepository`.

### Anti-Pattern 2: Stock decrease inside the background worker or Kafka listener

**What:** Deferring stock decrease to happen inside `OutboxWorkerServiceImpl` or `KafkaConfirmListener`.

**Why bad:** Stock must be reserved at request time to prevent overselling. The pessimistic lock in `OrderedProductUseCase.decreaseStockForOrders()` was designed for the confirm request transaction. Moving it to an async thread removes the contention protection.

**Instead:** Always call `executeStockDecreaseWithJobCreation()` in the adapter's request-handling transaction before returning 202.

### Anti-Pattern 3: Returning 200 from async adapters

**What:** Having `OutboxConfirmAdapter` and `KafkaConfirmAdapter` return the same response shape as `SyncConfirmAdapter` with HTTP 200.

**Why bad:** 200 implies the operation is complete. Clients polling for status would not know they need to poll.

**Instead:** `PaymentConfirmAsyncResult` carries a `status` field. The controller maps `DONE → 200`, `ACCEPTED → 202`. This keeps the decision at the presentation boundary and lets each adapter communicate its semantics through the DTO, not the HTTP status directly.

### Anti-Pattern 4: `@Profile` instead of `@ConditionalOnProperty`

**What:** Using Spring `@Profile("outbox")` or `@Profile("kafka")` for adapter selection.

**Why bad:** Profiles conflate deployment environment (dev, prod, test) with feature configuration. Switching strategy in production would require a profile change, which typically implies a different application context than intended.

**Instead:** `@ConditionalOnProperty` on a dedicated config key (`spring.payment.async-strategy`) is semantically correct and allows switching purely through `application.yml` without touching profile infrastructure.

---

## Build Order

The correct build order respects the dependency chain from port definition to implementation to wiring.

### Phase 1 — Port + DTO (no infrastructure, no Spring wiring)

1. `PaymentProcessStatus` — add `PENDING` value
2. `PaymentConfirmAsyncResult` — new DTO (replaces `PaymentConfirmResult` for async paths)
3. `PaymentConfirmAsyncPort` — new interface in `presentation/port/`
4. `PaymentEventPublisherPort` — new interface in `application/port/`
5. `PaymentProcessRepository` — add `findAllByStatus(PENDING)` query if not already covered
6. `GET /status` endpoint — `PaymentLoadUseCase` extension + controller method

### Phase 2 — Sync Adapter + Controller Wiring (verify existing flows not broken)

7. `SyncConfirmAdapter` — trivial wrapper, `matchIfMissing=true`
8. `PaymentController.confirm()` — change injection from `PaymentConfirmService` to `PaymentConfirmAsyncPort`; add HTTP 200/202 branching
9. Integration test: existing confirm flow with `async-strategy=sync` must pass unchanged

### Phase 3 — Outbox Adapter + Worker

10. `OutboxConfirmAdapter` — creates `PaymentProcess(PENDING)` in transaction
11. `OutboxWorkerService` port interface + `OutboxWorkerServiceImpl`
12. `PaymentScheduler` — new `processOutboxJobs()` method with `@ConditionalOnProperty`
13. Integration test: end-to-end outbox flow (202 → poll status → DONE)

### Phase 4 — Kafka Adapter + Consumer

14. Docker Compose: Kafka + Zookeeper (or KRaft) service definition
15. `PaymentEventPublisherPort` implementation: `KafkaProducerAdapter` with `KafkaTemplate`
16. `KafkaConfirmAdapter` — publishes to topic in transaction
17. `KafkaConfirmListener` — consumes, idempotency check, calls confirm service
18. `KafkaConfirmListener` gated with `@ConditionalOnProperty`
19. Integration test: Kafka end-to-end (202 → poll status → DONE) using Testcontainers Kafka

### Phase 5 — k6 Scripts + Comparison

20. k6 scripts for each strategy (parameterized by base URL and expected status code)
21. Performance comparison document

---

## Scalability Considerations

| Concern | Single Instance (current scope) | Notes |
|---------|--------------------------------|-------|
| Outbox worker concurrency | `@Scheduled` single-threaded, no distributed lock needed | Per project constraints — single instance only |
| Kafka consumer concurrency | Single consumer instance, single partition sufficient | Scale partition count if multi-instance later |
| Stock lock contention | Pessimistic lock scoped to confirm transaction — same as sync path | No change |
| Outbox poll interval | 5 seconds default (`scheduler.outbox-worker.interval-ms`) | Tunable via config, observable via k6 latency |
| Kafka at-least-once delivery | Idempotency guard: check `PaymentProcessRepository.existsByOrderId()` before processing | Prevents double-charge on redelivery |

---

## Sources

- Codebase: `payment/infrastructure/gateway/PaymentGatewayStrategy.java` — existing strategy pattern (infrastructure-level, not port-level) confirms how the project already uses interface-per-strategy
- Codebase: `payment/scheduler/PaymentScheduler.java` — `@ConditionalOnProperty` + `@Scheduled` pattern already established
- Codebase: `payment/application/port/PaymentProcessRepository.java` + `domain/PaymentProcess.java` — confirms `PaymentProcess` is a domain entity with status transitions, making it the correct Outbox record
- Codebase: `payment/presentation/port/PaymentConfirmService.java` — establishes that controller-facing interfaces live in `presentation/port/`
- [Ports and Adapters Architecture with Kafka, Avro, and Spring-Boot — DZone](https://dzone.com/articles/ports-and-adapters-architecture-with-kafka-avro-and-spring-boot) — Kafka consumer as inbound driving adapter; producer as outbound driven adapter (MEDIUM confidence)
- [Spring @ConditionalOnProperty — Baeldung](https://www.baeldung.com/spring-conditionalonproperty) — `matchIfMissing`, `havingValue` semantics (HIGH confidence)
- [Transactional Outbox Pattern with Spring Boot — Wim Deblauwe](https://www.wimdeblauwe.com/blog/2024/06/25/transactional-outbox-pattern-with-spring-boot/) — Outbox entity in infrastructure or domain, polling scheduler pattern (MEDIUM confidence)
- [Microservices.io — Transactional Outbox Pattern](https://microservices.io/patterns/data/transactional-outbox.html) — Canonical outbox pattern definition (HIGH confidence)
- [Curious case of Kafka Streams and Hexagonal Architecture — DEV Community](https://dev.to/ujja/curious-case-of-kafka-streams-and-hexagonal-architecture-37o5) — Kafka listener as primary (driving) adapter, Kafka producer as secondary (driven) adapter (MEDIUM confidence)
