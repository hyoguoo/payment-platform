# Project Research Summary

**Project:** Payment Platform — Async Strategy Migration
**Domain:** Pluggable async payment processing strategies (Sync / DB Outbox / Kafka)
**Researched:** 2026-03-14
**Confidence:** MEDIUM

## Executive Summary

This project migrates an existing synchronous payment confirm flow into a pluggable async strategy system. The codebase already has a well-structured Hexagonal Architecture with a sync confirm flow, job-tracking via `PaymentProcess`, recovery scheduling, and Micrometer metrics. The task is not to build from scratch but to add an abstraction layer (`PaymentConfirmAsyncPort`) behind which three strategies — Sync (wrapper), DB Outbox, and Kafka — are interchangeable via a single `spring.payment.async-strategy` config key. This is a portfolio demonstration of production-grade async patterns, with k6 load tests producing a quantitative strategy comparison.

The recommended approach is to build in strict dependency order: define the port contract and response DTO first, prove the Sync adapter works without regressions, then build the Outbox adapter (simpler dependency graph, no Docker Compose changes), and finally the Kafka adapter (most operationally complex). Stack additions are minimal: `spring-kafka` (version-managed by the Spring Boot 3.3.3 BOM), Testcontainers Kafka for integration tests, and k6 for load testing. Custom `@Scheduled` polling for the Outbox worker is the right implementation — no Debezium, no Spring Modulith, no external outbox library.

The primary risks fall into three categories: data integrity (duplicate processing from overlapping scheduler ticks or Kafka redelivery), domain model contamination (repurposing `PaymentProcess` as an Outbox record conflates two concerns and creates a race with the recovery scheduler), and benchmark validity (k6 TPS numbers are not comparable across sync vs async strategies unless async scripts poll for terminal status). All three are preventable with deliberate design choices at the outset, before any adapter code is written.

---

## Key Findings

### Recommended Stack

The existing stack (Java 21, Spring Boot 3.3.3, Spring Data JPA, MySQL 8, QueryDSL, Testcontainers 1.19.8) is fixed and must not change. The only additions needed are `spring-kafka` (let the Spring Boot 3.3.3 BOM resolve version 3.2.x — do not manually pin to 3.3.x which targets Boot 3.4.x), `org.testcontainers:kafka` at the existing `1.19.8` version for integration tests, and `grafana/k6` via Docker Compose as a one-shot load test runner.

**Core technologies:**
- `spring-kafka` (Boot-managed 3.2.x): Kafka producer and consumer abstraction — BOM-managed, no version pin required
- `apache/kafka:3.9.0` (Docker): KRaft-mode broker for local dev — 3.9 is the last stable ZooKeeper-compatible release; 4.0 is too new to combine with Spring Kafka 3.2.x
- `org.testcontainers:kafka:1.19.8`: Kafka container in integration tests — matches existing Testcontainers version
- `grafana/k6:latest` (Docker): Load test runner — JS scripts, Docker-native, Prometheus output
- `ghcr.io/kafbat/kafka-ui:latest` (Docker, dev tooling only): Kafka topic inspector — `provectuslabs/kafka-ui` is abandoned; Kafbat is the active community fork

### Expected Features

**Must have (table stakes):**
- `PaymentConfirmAsyncPort` interface — single abstraction enabling strategy interchangeability
- Sync adapter wrapping existing `PaymentConfirmServiceImpl` — proves port compiles and controller works
- 202 Accepted HTTP response from async adapters; 200 from Sync adapter — semantic API contract
- `spring.payment.async-strategy` config switch — zero code change to swap adapters
- DB Outbox adapter with `@Scheduled` polling worker — atomic stock + PENDING record in one transaction, deferred Toss API call
- Kafka adapter with `@KafkaListener` consumer — stock decrease + Kafka publish in one transaction, consumer drives existing confirm logic
- `GET /api/v1/payments/{orderId}/status` polling endpoint — async clients need it; k6 scripts depend on it
- k6 load test scripts (one per strategy) — without measured results the portfolio goal is unachievable

**Should have (differentiators):**
- At-least-once delivery guarantee for Outbox — stock + outbox record atomic, crash-safe
- Idempotent Kafka consumer guard (`existsByOrderId` before processing) — prevents double stock decrease on redelivery
- Dead-letter topic (DLT) for non-retryable Kafka failures — routes unprocessable messages visibly
- Per-strategy Micrometer metric tag (`strategy=sync|outbox|kafka`) — makes k6 dashboard strategy-aware
- Outbox worker retry cap respecting existing `RETRYABLE_LIMIT = 5` — reuses domain logic
- Performance comparison document (`BENCHMARK.md`) with actual TPS, P95, P99 numbers — the portfolio story

**Defer (v2+):**
- WebSocket / SSE push notifications — polling endpoint is sufficient
- Exactly-once Kafka semantics — at-least-once + idempotent consumer is sufficient and simpler
- Kafka Schema Registry / Avro — JSON with Jackson is fine for a single producer/consumer
- Distributed locking (Redisson, DB advisory locks) — single-instance assumption is explicit
- Separate outbox microservice — overkill for a mono-module portfolio app

### Architecture Approach

All three strategies implement `PaymentConfirmAsyncPort` (in `presentation/port/`) and are registered conditionally via `@ConditionalOnProperty(name = "spring.payment.async-strategy")`. The Sync adapter delegates directly to `PaymentConfirmServiceImpl`. The Outbox and Kafka adapters call `PaymentTransactionCoordinator.executeStockDecreaseWithJobCreation()` in the request thread, then return 202 — deferring the Toss API call to either an `OutboxWorkerServiceImpl` (`@Scheduled`) or a `KafkaConfirmListener` (`@KafkaListener`). Both async paths converge back on `PaymentConfirmServiceImpl` for actual execution. The controller derives HTTP status (200 vs 202) from a `PaymentConfirmAsyncResult.status` field, never from the active property. The existing Hexagonal dependency direction (Presentation → Application → Domain ← Infrastructure) is unchanged.

**Major components:**
1. `PaymentConfirmAsyncPort` (presentation/port) — new interface; controller injects this instead of `PaymentConfirmService`
2. `PaymentConfirmAsyncResult` (application/dto) — new DTO carrying `status=DONE|ACCEPTED`; controller maps to 200/202
3. `SyncConfirmAdapter` (infrastructure/async) — wraps existing service; `matchIfMissing=true` preserves default behavior
4. `OutboxConfirmAdapter` + `OutboxWorkerServiceImpl` (infrastructure/async + application) — outbox write in request tx, worker polls PENDING rows
5. `KafkaConfirmAdapter` + `KafkaConfirmListener` + `KafkaProducerAdapter` (infrastructure/kafka) — publish in request tx, listener consumes and executes
6. `PaymentEventPublisherPort` (application/port) — outbound port for Kafka publish; `KafkaProducerAdapter` implements it
7. `GET /api/v1/payments/{orderId}/status` (presentation) — polls `PaymentLoadUseCase.getPaymentStatusByOrderId()`

**Critical design decision — Outbox storage:** PITFALLS research recommends a dedicated `payment_outbox` table (not repurposing `PaymentProcess`) to avoid a race condition with `PaymentRecoverServiceImpl`, which treats PROCESSING records as stuck jobs. This conflicts with the ARCHITECTURE recommendation to reuse `PaymentProcess` with a PENDING status. The safer option for clean architecture is a dedicated table. This must be decided before DDL is written.

### Critical Pitfalls

1. **Outbox worker reads uncommitted rows** — Write the PENDING record inside `executeStockDecreaseWithJobCreation`'s existing `@Transactional` boundary; the worker only polls committed rows
2. **Overlapping scheduler ticks cause double processing** — Use `fixedDelay` (not `fixedRate`) so the next tick waits for the previous to finish; add PENDING → IN_FLIGHT atomic status transition before processing
3. **Kafka consumer redelivery causes double stock decrease** — Add `existsByOrderId` idempotency check as the first line of the consumer handler, before any state mutation
4. **`enable.auto.commit=true` silently drops messages on crash** — Set `spring.kafka.listener.ack-mode=RECORD`; commit offset only after confirm logic succeeds
5. **Port return type does not carry 200 vs 202 signal** — Design `PaymentConfirmAsyncResult` with a `status=DONE|ACCEPTED` field before writing any adapter; the controller must derive HTTP status from this, not from a config property check
6. **k6 TPS numbers are not comparable across strategies** — Async k6 scripts must poll `GET /status` until terminal state; report "payments fully completed per second," not "202 responses per second"
7. **`PaymentProcess` dual-purpose creates race with recovery scheduler** — Dedicated `payment_outbox` table eliminates the overlap with `PaymentRecoverServiceImpl`

---

## Implications for Roadmap

Based on combined research, the correct phase structure follows the port-first dependency chain and groups by risk level. Infrastructure setup (Kafka in Docker Compose) should precede Kafka adapter code.

### Phase 1: Port Contract and Status Endpoint

**Rationale:** Every downstream adapter depends on the port interface and response DTO. Defining these first means each subsequent phase has a stable contract to implement against. The status endpoint has no adapter dependencies and is required by k6 scripts for async strategies — it must exist before any async adapter is tested end-to-end.
**Delivers:** `PaymentConfirmAsyncPort`, `PaymentConfirmAsyncResult`, `PaymentProcessStatus.PENDING` (if reusing table) or `payment_outbox` DDL (if dedicated table), `GET /api/v1/payments/{orderId}/status` endpoint
**Addresses:** Table-stakes features: port interface, 200/202 contract, status polling endpoint
**Avoids:** Pitfall 5 (HTTP status not carried in return type) — the DTO is designed before any adapter code exists; Pitfall 12 (bean wiring fails silently) — smoke tests for all three strategy values written here

### Phase 2: Sync Adapter + Controller Wiring

**Rationale:** Zero business risk. The Sync adapter wraps existing logic and proves the new port does not break any current behavior. `matchIfMissing=true` means the application runs identically to before if no strategy is configured. This is the regression gate before async complexity is introduced.
**Delivers:** `SyncConfirmAdapter`, updated `PaymentController` injecting `PaymentConfirmAsyncPort`, integration tests confirming existing confirm flow is unchanged
**Uses:** No new stack additions — pure Spring DI wiring
**Implements:** Architecture Pattern 1 (reuse `PaymentConfirmServiceImpl` as single execution point)
**Avoids:** Pitfall 5 (controller HTTP status logic validated here with sync=200)

### Phase 3: DB Outbox Adapter + Worker

**Rationale:** Simpler dependency graph than Kafka — no Docker Compose changes, no new infrastructure. Validates the at-least-once delivery pattern and the outbox write-inside-transaction constraint. Must resolve the schema decision (dedicated `payment_outbox` table vs `PaymentProcess.PENDING`) before writing DDL.
**Delivers:** `OutboxConfirmAdapter`, `OutboxWorkerServiceImpl`, `PaymentScheduler.processOutboxJobs()`, DB schema for outbox storage, end-to-end integration test (202 → poll status → DONE)
**Uses:** Spring `@Scheduled` (existing), JPA (existing), MySQL schema migration
**Implements:** Architecture Pattern 2 (stock decrease in request tx, Toss API call deferred)
**Avoids:** Pitfall 1 (transaction commit timing), Pitfall 2 (overlapping scheduler ticks), Pitfall 7 (table dual-purpose), Pitfall 9 (compensation double-execution)

### Phase 4: Kafka Adapter + Consumer + DLT

**Rationale:** Most operationally complex phase. Docker Compose Kafka must be running. Consumer idempotency and offset commit configuration must be deliberate. By this phase, the Outbox pattern has validated the stock-decrease-in-request-tx pattern, so the Kafka adapter can follow the same seam.
**Delivers:** Docker Compose Kafka (KRaft, `apache/kafka:3.9`), `KafkaConfirmAdapter`, `KafkaProducerAdapter`, `PaymentEventPublisherPort`, `KafkaConfirmListener`, DLT listener, Testcontainers Kafka integration tests
**Uses:** `spring-kafka` (Boot-managed 3.2.x), `apache/kafka:3.9.0`, `org.testcontainers:kafka:1.19.8`
**Implements:** Kafka as inbound driving adapter (listener) + outbound driven adapter (producer)
**Avoids:** Pitfall 3 (duplicate consumer execution), Pitfall 4 (auto-commit offset loss), Pitfall 10 (AOP history listener silent miss for async paths), Pitfall 11 (`ALREADY_PROCESSED_PAYMENT` masking duplicate)

### Phase 5: k6 Load Tests + Performance Comparison

**Rationale:** Final phase because all three strategies must be stable end-to-end before meaningful benchmarks are possible. Benchmark validity depends on the async scripts measuring "payment fully completed" (including the polling loop), not "202 responses per second."
**Delivers:** k6 scripts for each strategy (sync.js, outbox.js, kafka.js), Outbox worker poll interval tuned for benchmark, per-strategy Micrometer metric tags, `BENCHMARK.md` with TPS / P95 / P99 comparison table
**Uses:** `grafana/k6:latest`, Docker Compose `loadtest` profile
**Avoids:** Pitfall 6 (incomparable TPS numbers), Pitfall 8 (poll interval creating false latency disadvantage)

### Phase Ordering Rationale

- Phase 1 must be first because `PaymentConfirmAsyncPort` and `PaymentConfirmAsyncResult` are compile-time dependencies for all three adapters. Writing adapters before the port is defined causes interface churn.
- Phase 2 (Sync) before Phase 3 (Outbox) because the Sync adapter proves the controller wiring and HTTP status code logic work correctly with zero risk of breaking business logic.
- Phase 3 (Outbox) before Phase 4 (Kafka) because Outbox has no external infrastructure dependency — it validates the async pattern end-to-end with only JPA and `@Scheduled`, making Kafka debugging cleaner (infrastructure vs business logic failures are distinguishable).
- Phase 5 (k6) last because it depends on all three strategies being stable; running benchmarks on unstable code produces misleading portfolio artifacts.

### Research Flags

Phases likely needing deeper research during planning:
- **Phase 3:** Outbox schema decision (dedicated table vs `PaymentProcess.PENDING`) has conflicting guidance across ARCHITECTURE.md and PITFALLS.md — this must be resolved with a codebase review of `PaymentRecoverServiceImpl`'s query logic before writing DDL.
- **Phase 4:** `spring.kafka.listener.ack-mode=RECORD` interaction with Spring Kafka 3.2.x container lifecycle — verify configuration key names against Boot 3.3.3 autoconfiguration.

Phases with standard patterns (skip research-phase):
- **Phase 1:** Port interface + DTO design follows established Hexagonal patterns already in the codebase; no novel patterns.
- **Phase 2:** `@ConditionalOnProperty` + `matchIfMissing=true` is a well-documented Spring pattern with existing codebase precedent (`PaymentScheduler`).
- **Phase 5:** k6 Docker Compose integration is standard; the only non-obvious aspect (async polling loop) is documented in PITFALLS.md.

---

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | MEDIUM | Spring Boot 3.3.3 BOM → spring-kafka 3.2.x confirmed via official Spring release blog. Kafka 4.0 compatibility with Spring Kafka 3.2.x not formally tested — using 3.9 is the safe choice. |
| Features | HIGH | Based on direct codebase analysis of `PaymentConfirmServiceImpl`, `PaymentTransactionCoordinator`, `PaymentProcess`, and `PaymentScheduler`. Feature dependencies are traced from actual code. |
| Architecture | HIGH | Component boundaries, package placement, and data flow patterns are grounded in existing codebase conventions (`presentation/port/`, `infrastructure/async/`, `scheduler/`). Strategy-switching via `@ConditionalOnProperty` has direct codebase precedent. |
| Pitfalls | MEDIUM | Critical pitfalls 1–5 are derived from direct codebase analysis (HIGH confidence). Pitfalls 6–12 are grounded in codebase analysis combined with community sources (MEDIUM confidence). Compensation idempotency risk (Pitfall 9) is an existing known bug per CONCERNS.md — HIGH confidence on the risk, MEDIUM on the fix approach. |

**Overall confidence:** MEDIUM-HIGH

### Gaps to Address

- **Outbox storage decision (CRITICAL):** ARCHITECTURE.md recommends reusing `PaymentProcess` with a `PENDING` status. PITFALLS.md recommends a dedicated `payment_outbox` table to avoid a race with `PaymentRecoverServiceImpl`. This conflict must be resolved in Phase 3 planning by reviewing the exact query in `PaymentRecoverServiceImpl.recoverStuckPayments()` — if it filters by `updatedAt` threshold and the recovery window is long enough to avoid the race, reuse may be safe.
- **`PaymentHistory` AOP behavior in async threads (MODERATE):** The existing `@PublishDomainEvent` AOP aspect may not fire for the Outbox worker or Kafka consumer because they run in different thread/transaction contexts. This needs an integration test assertion per async adapter before Phase 5.
- **`SchedulerConfig` thread pool (MODERATE):** Whether the existing `SchedulerConfig` uses a single-thread pool or allows concurrent executions affects Pitfall 2 severity. Must be confirmed before choosing between `fixedDelay` only vs `fixedDelay` + IN_FLIGHT status gate.

---

## Sources

### Primary (HIGH confidence)
- Spring Kafka release blog: https://spring.io/blog/2025/03/18/spring-kafka-4-0-0-M1-and-3-3-4-and-3-2-8-available-now/
- Spring Boot dependency versions appendix: https://docs.spring.io/spring-boot/appendix/dependency-versions/index.html
- Spring `@ConditionalOnProperty` (Baeldung): https://www.baeldung.com/spring-conditionalonproperty
- Microservices.io Transactional Outbox Pattern: https://microservices.io/patterns/data/transactional-outbox.html
- Codebase direct analysis: `PaymentConfirmServiceImpl`, `PaymentTransactionCoordinator`, `PaymentProcess`, `PaymentScheduler`, `PaymentRecoverServiceImpl`, `PaymentController`, `.planning/codebase/CONCERNS.md`

### Secondary (MEDIUM confidence)
- Outbox Pattern polling vs CDC comparison: https://architectureway.dev/outbox-pattern
- Transactional Outbox Pattern with Spring Boot (Wim Deblauwe): https://www.wimdeblauwe.com/blog/2024/06/25/transactional-outbox-pattern-with-spring-boot/
- Kafka Idempotent Consumer & Transactional Outbox (lydtechconsulting): https://www.lydtechconsulting.com/blog/kafka-idempotent-consumer-transactional-outbox
- Delivery Semantics for Kafka Consumers (Conduktor): https://learn.conduktor.io/kafka/delivery-semantics-for-kafka-consumers/
- Ports and Adapters with Kafka (DZone): https://dzone.com/articles/ports-and-adapters-architecture-with-kafka-avro-and-spring-boot
- Kafbat UI (active fork of provectuslabs/kafka-ui): https://github.com/kafbat/kafka-ui
- grafana/k6 Docker image: https://hub.docker.com/r/grafana/k6

### Tertiary (LOW confidence)
- At-Least-Once Delivery (oneuptime.com 2026): https://oneuptime.com/blog/post/2026-01-30-at-least-once-delivery/view — single source
- Performance Testing Asynchronous Applications (testrail.com): https://www.testrail.com/blog/performance-test-asynchronous-applications/ — general, not Spring-specific

---
*Research completed: 2026-03-14*
*Ready for roadmap: yes*
