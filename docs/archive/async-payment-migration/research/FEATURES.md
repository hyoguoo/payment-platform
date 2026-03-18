# Feature Landscape

**Domain:** Pluggable async payment processing strategies (Sync / DB Outbox / Kafka)
**Researched:** 2026-03-14

---

## Context: What Already Exists

The codebase already has:
- Sync confirm flow in `PaymentConfirmServiceImpl` (stock decrease → Toss API call → mark DONE)
- `PaymentProcess` domain entity tracking job status (PROCESSING / COMPLETED / FAILED)
- `PaymentTransactionCoordinator` providing atomic stock + job creation in one transaction
- `PaymentRecoverServiceImpl` / `PaymentScheduler` recovering UNKNOWN/IN_PROGRESS payments (5-minute scheduler)
- Micrometer AOP metrics on payment state transitions (`PaymentTransitionMetrics`, `PaymentStateMetrics`, `TossApiMetrics`)
- Full hexagonal port/adapter structure already in place

This milestone adds three new things: a port interface that abstracts the strategy, two async adapters (Outbox, Kafka), and a status polling endpoint.

---

## Table Stakes

Features the implementation MUST have. Missing any of these means the core goal (strategy interchangeability + k6 comparison) is unachievable.

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| `PaymentConfirmAsyncPort` interface | Single abstraction all three strategies implement; the contract that makes switching possible | Low | One method: `submitConfirm(PaymentConfirmCommand)` returning something that signals 200 vs 202 |
| Sync adapter (wrapper) | Baseline — existing `PaymentConfirmServiceImpl` logic wrapped behind the new port | Low | No business logic change; proves the port does not break the current flow |
| 202 Accepted response for async adapters | Callers must know the request was accepted but not yet processed; Sync adapter returns 200 with result | Low | The controller decides 200 vs 202 based on what the port returns (result vs accepted handle) |
| `spring.payment.async-strategy` config switch | The entire point of the milestone; change `application.yml` to switch adapters with zero code change | Low | `@ConditionalOnProperty` or `@Value` qualifier on the port bean |
| DB Outbox adapter | Writes `PaymentConfirmCommand` payload atomically into the `PaymentProcess` table (reusing existing domain) in the same transaction as stock decrease; worker picks it up | Medium | Reuses existing `PaymentProcess` entity — add `PENDING` status and payload column, or create a thin `outbox_pending` table |
| Outbox worker thread | `@Scheduled` task or `@Async` thread that polls PENDING outbox rows and drives the existing confirm use-cases | Medium | Already has `PaymentScheduler` + `PaymentRecoverServiceImpl` as a pattern to follow |
| Kafka adapter | Publishes `PaymentConfirmCommand` to a Kafka topic and returns 202 immediately; separate `@KafkaListener` consumer drives the confirm use-cases | Medium-High | Requires Docker Compose Kafka; consumer group id, topic name configurable |
| `GET /api/v1/payments/{orderId}/status` polling endpoint | Clients use this to learn when an async-accepted payment reaches DONE/FAILED | Low | Returns current `PaymentEventStatus`; leverages existing `PaymentLoadUseCase.getPaymentEventByOrderId` |
| k6 load test scripts (one per strategy) | Without these, the entire quantitative goal of the project is missing | Medium | Scripts must produce TPS and P95 latency numbers for the README comparison |

---

## Differentiators

Features that go beyond the minimum and make this a portfolio-worthy implementation.

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| At-least-once delivery guarantee (Outbox) | Demonstrates production knowledge: stock + outbox record written atomically in one transaction, so a crash before the worker runs never loses the intent | Medium | The existing `@Transactional` in `PaymentTransactionCoordinator.executeStockDecreaseWithJobCreation` is the right seam — add outbox record write inside that same transaction |
| Idempotent consumer (Kafka adapter) | Kafka at-least-once delivery means the consumer may receive a message twice; checking `PaymentProcess` existence before processing prevents duplicate Toss API calls | Medium | `PaymentTransactionCoordinator.executePaymentFailureCompensation` already guards with `existsByOrderId` — apply the same guard in the Kafka consumer |
| Dead-letter handling (Kafka) | Non-retryable failures published to a dead-letter topic instead of silently dropped; visible in logs/metrics | Medium | Add a `@KafkaListener` on the DLT, call `PaymentFailureUseCase.handleNonRetryableFailure` |
| Per-strategy metric tag | Add `strategy=sync|outbox|kafka` tag to existing Micrometer counters so the k6 dashboard shows which strategy produced which numbers | Low | One-line addition to `PaymentTransitionMetrics` / `TossApiMetrics` AOP |
| Outbox worker retry cap | Outbox worker should respect the existing `RETRYABLE_LIMIT = 5` on `PaymentEvent` rather than retrying indefinitely | Low | Reuses existing domain logic, zero new code beyond the guard |
| Performance comparison document | A table in README (or a dedicated `BENCHMARK.md`) with actual k6 numbers — TPS, P95, P99 — for each strategy under identical load | Low (writing) | The entire project motivation; without it the portfolio story is incomplete |
| Structured log correlation (`orderId` + `strategy` in every log line) | Makes it trivially easy to trace a single payment through the async path in logs | Low | `LogFmt` pattern already exists; add `strategy` field to confirm-related log calls |

---

## Anti-Features

Features explicitly excluded because they exceed portfolio scope or introduce complexity disproportionate to the learning goal.

| Anti-Feature | Why Avoid | What to Do Instead |
|--------------|-----------|-------------------|
| WebSocket / SSE push notifications | High implementation complexity; the project already decided polling is sufficient | Implement `GET /api/v1/payments/{orderId}/status` polling endpoint |
| Multi-instance / distributed locking | Single-instance assumption is stated in PROJECT.md; distributed locking (Redisson, DB advisory locks) would obscure the strategy comparison | Document the single-instance assumption in comments and README |
| Debezium / CDC-based outbox relay | Operationally complex, requires MySQL binlog config, heavy for a portfolio demo | Use a simple `@Scheduled` polling worker — same reliability guarantee, far less setup |
| Exactly-once Kafka semantics | Requires idempotent producer + transactional consumer; adds significant Kafka config complexity for marginal gain in this context | At-least-once + idempotent consumer is sufficient and demonstrates production awareness |
| Kafka Schema Registry / Avro | Unnecessary for a single-consumer, single-producer system in a portfolio context | Use JSON serialization with Jackson |
| Admin UI for outbox monitoring | Frontend is out of scope per PROJECT.md | Expose outbox state via existing `GET /api/v1/admin/payments` endpoint |
| Authentication / authorization | Explicitly out of scope per PROJECT.md | Leave all endpoints open; note it in README |
| Separate outbox microservice | Overengineering for a portfolio mono-module app | Keep the worker as a `@Scheduled` bean in the same Spring context |

---

## Feature Dependencies

```
PaymentConfirmAsyncPort (interface)
  └── Sync adapter          (wraps existing PaymentConfirmServiceImpl logic)
  └── DB Outbox adapter
        └── Outbox worker thread
              └── PaymentProcess PENDING status / payload field   [needs schema change]
              └── Outbox worker retry cap                          [uses existing RETRYABLE_LIMIT]
              └── At-least-once delivery guarantee                 [uses existing @Transactional seam]
  └── Kafka adapter
        └── Kafka producer (publish to topic)
        └── Kafka consumer (@KafkaListener)
              └── Idempotent consumer guard                        [uses existing existsByOrderId]
              └── Dead-letter handling                             [needs DLT listener]

202 Accepted response
  └── PaymentConfirmAsyncPort (interface must signal sync vs async to controller)

GET /api/v1/payments/{orderId}/status
  └── PaymentLoadUseCase.getPaymentEventByOrderId  [already exists]

Per-strategy metric tag
  └── Micrometer AOP (already exists in PaymentTransitionMetrics / TossApiMetrics)

k6 load test scripts
  └── All three adapters working end-to-end
  └── GET /api/v1/payments/{orderId}/status (for async strategies — wait for terminal state)

Performance comparison document
  └── k6 scripts run + results captured
```

---

## MVP Recommendation

Implement in this order:

1. **`PaymentConfirmAsyncPort` interface + response model** — establish the contract (200 vs 202 signal) before writing any adapter
2. **Sync adapter** — zero business risk, proves the port compiles and the controller works with both response types
3. **`GET /api/v1/payments/{orderId}/status`** — needed by k6 scripts for async strategies before those adapters exist
4. **DB Outbox adapter + worker** — simpler dependency graph than Kafka (no Docker Compose changes); validates the at-least-once pattern
5. **Kafka adapter + consumer + DLT** — most operationally complex; do last when Outbox proves the pattern
6. **k6 scripts + benchmark document** — final step once all three strategies are stable

Defer without risk:
- Structured log `strategy` field: add at any point, no dependency
- Per-strategy metric tag: add after all adapters are wired, before k6 runs

---

## Strategy Behaviour Contract

Each adapter must satisfy this behavioural contract for the port to be truly interchangeable:

| Behaviour | Sync Adapter | Outbox Adapter | Kafka Adapter |
|-----------|-------------|----------------|---------------|
| HTTP response code | 200 + result body | 202 (no result body) | 202 (no result body) |
| Stock decrease timing | Synchronous, before Toss call | Synchronous, in same tx as outbox write | Synchronous, before Kafka publish |
| Toss API call timing | Synchronous, in request thread | Deferred to worker thread | Deferred to Kafka consumer |
| Delivery guarantee | At-most-once (if crash after Toss call, lost) | At-least-once (outbox survives crash) | At-least-once (Kafka retains message) |
| Duplicate protection | N/A | existsByOrderId guard in worker | existsByOrderId guard in consumer |
| Failure path | Exception → PaymentFailureUseCase in request thread | Worker marks FAILED, calls PaymentFailureUseCase | Consumer calls PaymentFailureUseCase; DLT for non-retryable |

---

## Sources

- Codebase analysis: `PaymentConfirmServiceImpl`, `PaymentTransactionCoordinator`, `PaymentProcess`, `PaymentEvent`, `PaymentRecoverServiceImpl`
- [Transactional Outbox Pattern — microservices.io](https://microservices.io/patterns/data/transactional-outbox.html) — MEDIUM confidence (authoritative pattern reference)
- [Spring Blog: Outbox Pattern with Spring Cloud Stream Kafka Binder](https://spring.io/blog/2023/10/24/a-use-case-for-transactions-adapting-to-transactional-outbox-pattern/) — MEDIUM confidence
- [At-Least-Once Delivery — oneuptime.com 2026](https://oneuptime.com/blog/post/2026-01-30-at-least-once-delivery/view) — LOW confidence (single source)
- Codebase patterns: `PaymentStateMetrics`, `TossApiMetrics`, `LogFmt` — HIGH confidence (direct code reading)
