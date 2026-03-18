# Domain Pitfalls

**Domain:** Pluggable async payment processing strategies (Sync / DB Outbox / Kafka)
**Researched:** 2026-03-14

---

## Critical Pitfalls

Mistakes that cause rewrites, data corruption, or make portfolio claims incorrect.

---

### Pitfall 1: Outbox Worker Runs Before the Writing Transaction Commits

**What goes wrong:**
The `executeStockDecreaseWithJobCreation` method in `PaymentTransactionCoordinator` is annotated `@Transactional`. If the Outbox worker polls the outbox table via a separate scheduled thread and reads a `PENDING` row that was inserted but not yet committed, one of two outcomes occurs: (a) the row is invisible under READ COMMITTED isolation and the worker never sees it, or (b) under a weaker isolation level, the worker reads and processes the row, then the outer transaction rolls back, leaving stock decreased but the Outbox record permanently marked COMPLETED with no matching payment.

**Why it happens:**
Scheduled pollers run on a different thread and a different transaction. The interaction between the write transaction's commit timing and the poller's SELECT window is easy to overlook.

**Consequences:**
- Silent data loss: stock is consumed, payment never completes, customer is in limbo.
- No exception is raised because the worker "successfully" processed what it found.

**Prevention:**
Write the Outbox record (or PENDING status on `PaymentProcess`) inside `executeStockDecreaseWithJobCreation`'s existing `@Transactional` boundary — exactly where stock decrease already happens. The poller only ever reads rows that a committed transaction created. Never create the Outbox row before committing the business transaction.

**Detection:**
Unit test that inserts a PENDING row and rolls back the enclosing transaction, then asserts the worker cannot find it. Integration test that verifies PENDING rows only appear after the transaction completes.

**Phase:** Outbox adapter implementation (schema change + worker).

---

### Pitfall 2: Outbox Worker Processes the Same Row Twice

**What goes wrong:**
The `@Scheduled` Outbox poller fires every N milliseconds. Even in a single-instance environment, if the worker thread is still processing batch N when the next scheduler tick fires, a second invocation starts and picks up the same rows (which are still PROCESSING or not yet transitioned to COMPLETED). The `PaymentTransactionCoordinator` will call `executeStockDecreaseWithJobCreation` again, decrease stock a second time, and attempt a second Toss API call with the same `paymentKey`.

**Why it happens:**
Spring's `@Scheduled` with a fixed delay does not prevent overlapping executions unless the method is synchronised or the scheduler has a single-thread pool. The existing `SchedulerConfig` must be checked — if it uses `@EnableScheduling` with default settings, overlap is possible under load.

**Consequences:**
- Double stock decrease (real inventory corruption).
- Toss returns `ALREADY_PROCESSED_PAYMENT` — which `TossPaymentErrorCode.isSuccess()` treats as success — masking the duplicate entirely. The existing tech-debt item in `CONCERNS.md` makes this worse.

**Prevention:**
Two complementary guards:
1. Set `fixedDelay` rather than `fixedRate` for the Outbox worker so the next tick only starts after the previous one finishes.
2. Use a status gate: transition PENDING → IN_FLIGHT atomically before processing (`UPDATE ... SET status = 'IN_FLIGHT' WHERE status = 'PENDING' LIMIT batch_size`). Worker only processes IN_FLIGHT rows it owns; new scheduler tick skips rows it cannot transition.

**Detection:**
Warning sign: `ALREADY_PROCESSED_PAYMENT` errors appear in logs during load tests. Monitor for duplicate `PaymentProcess` COMPLETED records for the same `orderId`.

**Phase:** Outbox adapter worker implementation.

---

### Pitfall 3: Kafka Consumer Processes the Same Message Twice Without an Idempotency Guard

**What goes wrong:**
Kafka at-least-once delivery (the correct production default) means that after a consumer rebalance, uncommitted offset redelivery, or crash-and-restart, the consumer can receive the same `PaymentConfirmCommand` message more than once. Without a guard, the consumer calls `executeStockDecreaseWithJobCreation` and then the Toss API a second time. The existing `PaymentTransactionCoordinator.executePaymentFailureCompensation` already has `existsByOrderId` as a guard — but the consume-then-process path in the new Kafka consumer will not have this guard unless it is deliberately added.

**Why it happens:**
Developers add `@KafkaListener` and call the existing confirm service directly, trusting that the Toss "already processed" response will be harmless. But stock decrease happens before Toss is called, so the stock decrease is duplicated even when Toss rejects the second call.

**Consequences:**
- Double stock decrease before the idempotency shortcut in Toss fires.
- Compensation logic may then double-restore stock, depending on error path.

**Prevention:**
Before processing a consumed message, check whether a `PaymentProcess` row already exists for `orderId` (use `PaymentProcessUseCase.existsByOrderId`). If it exists and is COMPLETED, skip the message. If it is PROCESSING, treat as in-flight and skip or wait. This mirrors what `executePaymentFailureCompensation` already does. Log skipped duplicates explicitly.

**Detection:**
Warning sign: stock levels drift negative or higher than expected in load tests. Add a Micrometer counter `payment.consumer.duplicate.skipped` that increments on each skip.

**Phase:** Kafka adapter consumer implementation.

---

### Pitfall 4: `enable.auto.commit = true` Causes Silent Message Loss on Consumer Crash

**What goes wrong:**
Spring Kafka's default auto-commit behavior commits offsets on a background timer, independently of whether the message was processed successfully. If the consumer crashes after committing the offset but before completing the Toss API call, the message is permanently lost — Kafka will not redeliver it because the offset was already advanced.

**Why it happens:**
The default Spring Kafka `ContainerProperties.AckMode` is `BATCH`, which commits after a successful batch poll. If the application does not set `AckMode.MANUAL` or `AckMode.RECORD`, offset commits are not tied to business logic completion.

**Consequences:**
- Payment silently never completes; customer sees their payment stuck in IN_PROGRESS forever.
- The existing `PaymentRecoverServiceImpl` scheduler will eventually recover IN_PROGRESS payments, but only after a 5-minute window. For a portfolio demonstrating "production patterns correctly," auto-commit is incorrect.

**Prevention:**
Configure `spring.kafka.listener.ack-mode=RECORD` (or `MANUAL`) and commit the offset only after the confirm logic succeeds (or the message is moved to a DLT). This makes offset commits a consequence of successful processing.

**Detection:**
Test: publish 10 messages, crash the consumer mid-batch, restart — verify all 10 are reprocessed. If any are missing, auto-commit is the cause.

**Phase:** Kafka adapter infrastructure configuration.

---

### Pitfall 5: HTTP Response Contract Change Breaks k6 Scripts Silently

**What goes wrong:**
The existing controller returns 200 for sync confirm. When the Kafka or Outbox adapter is active, the controller must return 202. The `PaymentController` currently calls `paymentConfirmService.confirm(...)` and returns whatever the mapper produces — there is no status code logic in the controller body. If the new `PaymentConfirmAsyncPort` does not carry a signal about whether the response should be 202 or 200, the controller has no way to vary the status code, and the async adapters silently return 200 when they should return 202.

**Why it happens:**
The port interface return type drives everything. If `PaymentConfirmAsyncPort.submitConfirm(...)` returns the same `PaymentConfirmResult` as the sync path, the controller cannot distinguish "completed now" from "accepted for later."

**Consequences:**
- k6 scripts checking for 200 pass for async strategies, masking the semantic incorrectness.
- k6 scripts checking for 202 fail for sync strategy, requiring separate scripts per strategy — defeating the comparison goal.
- The portfolio code demonstrates the wrong API contract, which undermines the "real production patterns" objective.

**Prevention:**
The port interface return type must encode the async vs sync semantic. Options:
- Return a sealed type / discriminated union: `SyncResult(PaymentConfirmResult)` vs `AsyncAccepted(orderId)`.
- Return a `ConfirmResponse` wrapper with an `isAsync` flag that the controller maps to `ResponseEntity.ok(...)` vs `ResponseEntity.accepted().build()`.
The controller derives the HTTP status from the return object, not from a `@ConditionalOnProperty` check.

**Detection:**
Write a controller test for each strategy that asserts the exact HTTP status code returned. Fail on the wrong code before the k6 phase.

**Phase:** Port interface design (before writing any adapter).

---

### Pitfall 6: k6 TPS Numbers Are Incomparable Across Sync vs Async Strategies

**What goes wrong:**
For the sync strategy, a k6 VU completes one full payment per iteration: POST confirm → wait for 200 → done. Elapsed time includes Toss API latency. For the async strategy, a k6 VU completes one iteration in milliseconds: POST confirm → receive 202 → done. The TPS for async will be orders of magnitude higher simply because the VU is not waiting for Toss. The resulting benchmark table shows "Kafka: 800 TPS, Sync: 20 TPS" — which is misleading because they are measuring different things.

**Why it happens:**
k6 measures request-response round trip per VU. For async strategies, the "round trip" ends at 202 acceptance. The actual payment processing cost (Toss API call) is hidden in the consumer and not reflected in the k6 TPS number.

**Consequences:**
- The performance comparison is invalid as a benchmark.
- Anyone reading the portfolio README will see the TPS difference and question whether the developer understands what they measured.

**Prevention:**
Define what the benchmark measures before writing scripts:
- **Sync:** POST confirm → 200. Measures end-to-end payment latency from client perspective.
- **Async (Outbox/Kafka):** POST confirm → 202, then poll `GET /status` until DONE/FAILED. Measures total time from client submit to terminal state. This is the comparable metric.
The k6 script for async strategies must include the polling loop and report the total time from POST to terminal status as the meaningful latency figure. TPS for the async strategies should be measured as "payments fully completed per second" not "202 responses per second."

**Detection:**
Warning sign: async TPS is 10x–100x sync TPS. Review whether the k6 script is measuring acceptance throughput or completion throughput.

**Phase:** k6 script design (before running benchmarks).

---

### Pitfall 7: Reusing `PaymentProcess` Table as Outbox Storage Conflates Two Concerns

**What goes wrong:**
`PaymentProcess` currently tracks job execution status (PROCESSING / COMPLETED / FAILED). If the Outbox adapter re-purposes this table by adding a `PENDING` status and a payload column, the same row now serves two roles: "has a processing job started?" and "is there an outbox message to deliver?" When the worker transitions PENDING → PROCESSING, the existing `PaymentRecoverServiceImpl` scheduler — which recovers stale PROCESSING records — may pick up legitimately pending Outbox rows and treat them as stuck jobs, calling `recoverStuckPayments` on records that have not yet been processed by the Outbox worker.

**Why it happens:**
The existing recovery scheduler (`recoverStuckPayments`) does not distinguish between "PROCESSING because an old sync confirm crashed" and "PROCESSING because the Outbox worker just started." The only differentiator is the `createdAt` / `updatedAt` timestamp threshold.

**Consequences:**
- The recovery scheduler attempts to recover a payment that is actively being processed by the Outbox worker, creating a race condition.
- This is a variation of the known bug in `CONCERNS.md`: "Compensation Idempotency Not Guaranteed" — double-run compensation doubles stock restoration.

**Prevention:**
Two options:
1. Add a `source` or `type` column to `PaymentProcess` that distinguishes Outbox records from sync job records, and teach the recovery scheduler to ignore Outbox-sourced records.
2. Create a separate `payment_outbox` table with its own columns, keeping concerns cleanly separated. Slightly more schema work upfront but eliminates the overlap entirely.

For a portfolio project demonstrating clean architecture, option 2 is recommended — reusing the table blurs the domain boundary.

**Detection:**
Warning sign: recovery scheduler logs show it attempting to recover a payment that the Outbox worker also logs as "starting to process" within the same time window.

**Phase:** Outbox schema design (before implementation).

---

## Moderate Pitfalls

Mistakes that cause incorrect behavior or unreliable tests but do not cause data corruption.

---

### Pitfall 8: Outbox Worker Polling Interval Creates False Latency Disadvantage

**What goes wrong:**
If the Outbox worker polls every 5 seconds (matching the existing scheduler interval), the average latency for an Outbox-processed payment will be 2.5 seconds of queuing delay plus Toss API time. The k6 benchmark will show Outbox as dramatically slower than Kafka, not because the pattern is slower but because of the poll interval choice.

**Why it happens:**
Using the existing scheduler interval (5 minutes for recovery, likely seconds for the Outbox worker) without tuning it for demo purposes.

**Prevention:**
For the benchmark, use a short polling interval (e.g., 200–500ms) that represents a reasonable production configuration. Document the interval in the benchmark results. The comparison should measure architectural overhead, not polling interval choice.

**Detection:**
P95 latency for Outbox ≈ poll_interval + Toss_API_time. If P95 is much larger than Toss API P95, the poll interval is too long relative to the test window.

**Phase:** Outbox worker configuration, before k6 runs.

---

### Pitfall 9: Compensation Double-Execution During Outbox Worker Retry

**What goes wrong:**
This is an existing known bug (`CONCERNS.md`: "Compensation Idempotency Not Guaranteed") that becomes worse with the Outbox pattern. If the Outbox worker calls the Toss API and gets a retryable error, then on the next worker tick retries and gets a non-retryable error, `executePaymentFailureCompensation` calls `increaseStockForOrders` without checking if stock was already restored. Under at-least-once delivery, if the worker runs compensation twice, stock is double-restored.

**Prevention:**
Before the Outbox adapter goes live, add a guard in `executePaymentFailureCompensation`: check whether stock was already restored (e.g., check `PaymentEventStatus` — if already FAILED, skip stock restoration). Alternatively, use an idempotency flag on the `PaymentOrder` domain object to track whether compensation has run.

**Detection:**
After a simulated worker crash-and-retry under a non-retryable Toss error, assert stock levels. If stock is higher than starting value, double-compensation occurred.

**Phase:** Outbox adapter worker error handling.

---

### Pitfall 10: `PaymentHistory` AOP Listener Silently Fails for Async Paths

**What goes wrong:**
`PaymentHistoryEventListener` depends on Spring application events published via AOP (`@PublishDomainEvent`). The existing sync confirm path triggers AOP aspects correctly because the domain method is called in the same thread/context as the Spring application event publishing. The Outbox worker and Kafka consumer run in separate threads with their own transaction contexts. If the AOP aspect does not fire (wrong pointcut match, transaction propagation boundary, or the domain method is called via a bean not managed by the AOP proxy), payment history records will be missing for async-processed payments with no error surfaced.

**Prevention:**
After implementing each async adapter, write an integration test that confirms a `PaymentHistory` record exists for a payment processed via that adapter. Do not assume AOP aspect fire automatically because it worked for the sync path.

**Detection:**
Warning sign: admin payment history endpoint shows zero history events for Outbox/Kafka-processed payments. This is the "Fragile Areas" item from `CONCERNS.md` applied to the async paths.

**Phase:** Integration testing of each async adapter.

---

## Minor Pitfalls

---

### Pitfall 11: `ALREADY_PROCESSED_PAYMENT` as Success Masks Duplicate Consumer Execution

**What goes wrong:**
`TossPaymentErrorCode.isSuccess()` returns `true` for `ALREADY_PROCESSED_PAYMENT`. If the idempotency guard (Pitfall 3) is missing or fires too late (after stock decrease), the Toss API returns `ALREADY_PROCESSED_PAYMENT`, the consumer treats it as success, marks the payment DONE, and the duplicate stock decrease is never noticed. The consumer appears to have worked correctly.

**Prevention:**
Treat `ALREADY_PROCESSED_PAYMENT` as a signal to skip, not a signal to complete. When received, verify the existing `PaymentEventStatus` from the DB: if already DONE, skip without modifying domain state. If not DONE, something is inconsistent — log a WARNING and route to investigation rather than silently marking DONE.

**Detection:**
Add a Micrometer counter `payment.toss.already_processed` that increments on this response. Nonzero values during tests indicate duplicate processing is occurring.

**Phase:** Kafka consumer and Outbox worker Toss API response handling.

---

### Pitfall 12: `spring.payment.async-strategy` Bean Wiring Fails Silently at Startup

**What goes wrong:**
If `@ConditionalOnProperty` is used to register the correct `PaymentConfirmAsyncPort` bean, and the property is missing or misspelled in `application.yml`, Spring may fail to find any matching bean and throw `NoSuchBeanDefinitionException` at startup — or, worse, silently fall back to a default bean if one is present without a condition. In Docker Compose environments, misconfigured profiles are common.

**Prevention:**
Add a `@ConditionalOnMissingBean` fallback that throws a descriptive startup exception: "No async strategy configured. Set `spring.payment.async-strategy` to sync|outbox|kafka." Write a smoke test that verifies the bean is resolved for each of the three strategy values. Never let the strategy be ambiguous at runtime.

**Detection:**
Warning sign: application starts without error but confirm requests fail with `NullPointerException` because the port bean is null or unexpectedly falls through to the wrong implementation.

**Phase:** Port interface and configuration wiring.

---

## Phase-Specific Warnings

| Phase Topic | Likely Pitfall | Mitigation |
|-------------|---------------|------------|
| Port interface design | HTTP status code signal (200 vs 202) not carried in return type (Pitfall 5) | Design sealed return type before writing any adapter |
| `PaymentProcess` schema extension | Table dual-purpose conflating Outbox and job-tracking (Pitfall 7) | Decide dedicated table vs reuse before writing DDL |
| Outbox worker implementation | Overlapping scheduler invocations causing double processing (Pitfall 2) | Use `fixedDelay` + IN_FLIGHT status transition |
| Outbox worker error handling | Compensation double-execution on retry (Pitfall 9) | Add idempotency guard to `executePaymentFailureCompensation` before use in async path |
| Outbox worker startup | Transaction commit timing vs poller read window (Pitfall 1) | Write Outbox record inside existing `@Transactional` boundary |
| Kafka consumer configuration | Auto-commit offset loss on crash (Pitfall 4) | Set `ack-mode=RECORD`, test crash-and-restart scenario |
| Kafka consumer business logic | Duplicate message processing without guard (Pitfall 3) | `existsByOrderId` check as first line of consumer handler |
| Kafka consumer Toss response handling | Silent duplicate masked by `ALREADY_PROCESSED_PAYMENT` (Pitfall 11) | Add counter metric; verify domain state before marking done |
| Bean wiring for strategy | Missing property causes silent wrong-bean injection (Pitfall 12) | Smoke test for all three property values |
| k6 script design | Incomparable TPS numbers across sync vs async (Pitfall 6) | Define "payment fully completed" as the unit; use polling loop in async scripts |
| k6 Outbox latency | Poll interval dominates latency, not architecture (Pitfall 8) | Tune poll interval before benchmark; document it in results |
| Integration testing | `PaymentHistory` AOP silent miss for async paths (Pitfall 10) | Assert history record exists after each adapter's integration test |

---

## Sources

- Codebase analysis (HIGH confidence):
  - `PaymentTransactionCoordinator.java` — transaction boundaries, compensation logic
  - `PaymentRecoverServiceImpl.java` — scheduler and recovery overlap risk
  - `PaymentProcess.java` — status machine, existing states
  - `PaymentController.java` — current 200-only response pattern
  - `.planning/codebase/CONCERNS.md` — existing known bugs (compensation idempotency, AOP history listener)

- [Kafka Idempotent Consumer & Transactional Outbox — lydtechconsulting.com](https://www.lydtechconsulting.com/blog/kafka-idempotent-consumer-transactional-outbox) — MEDIUM confidence

- [Delivery Semantics for Kafka Consumers — conduktor.io](https://learn.conduktor.io/kafka/delivery-semantics-for-kafka-consumers/) — MEDIUM confidence

- [Idempotent Reader pattern — Confluent Developer](https://developer.confluent.io/patterns/event-processing/idempotent-reader/) — MEDIUM confidence

- [Transactional Outbox Pattern with Spring Boot — medium.com/AlexanderObregon](https://medium.com/@AlexanderObregon/transactional-outbox-pattern-with-spring-boot-and-jpa-912a812d6a70) — MEDIUM confidence

- [Outbox Pattern Survival Guide — medium.com/tpierrain](https://medium.com/@tpierrain/outbox-pattern-survival-guide-6ad4b57ef189) — MEDIUM confidence

- [Revisiting the Outbox Pattern — decodable.co](https://www.decodable.co/blog/revisiting-the-outbox-pattern) — MEDIUM confidence

- [Performance Testing Asynchronous Applications — testrail.com](https://www.testrail.com/blog/performance-test-asynchronous-applications/) — LOW confidence (general, not Spring-specific)

- [Asynchronous API Performance Testing — octoperf.com](https://octoperf.com/blog/2020/04/08/asynchronous-api) — LOW confidence (older, JMeter-focused, principles apply)
