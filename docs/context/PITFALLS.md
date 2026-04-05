# Domain Pitfalls

**Domain:** Async payment processing (DB Outbox 단일 전략)
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
Write the Outbox record inside `executePaymentAndStockDecreaseWithOutbox`'s existing `@Transactional` boundary — exactly where stock decrease already happens. The poller only ever reads rows that a committed transaction created. Never create the Outbox row before committing the business transaction.

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
Warning sign: `ALREADY_PROCESSED_PAYMENT` errors appear in logs during load tests. Monitor for `ALREADY_PROCESSED_PAYMENT` Toss API responses for the same `orderId`.

**Phase:** Outbox adapter worker implementation.

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

**Phase:** Outbox worker Toss API response handling.

---

## Phase-Specific Warnings

| Phase Topic | Likely Pitfall | Mitigation |
|-------------|---------------|------------|
| Outbox worker implementation | Overlapping scheduler invocations causing double processing (Pitfall 2) | Use `fixedDelay` + IN_FLIGHT status transition |
| Outbox worker error handling | Compensation double-execution on retry (Pitfall 9) | Add idempotency guard to `executePaymentFailureCompensation` before use in async path |
| Outbox worker startup | Transaction commit timing vs poller read window (Pitfall 1) | Write Outbox record inside existing `@Transactional` boundary |
| Outbox worker Toss response handling | Silent duplicate masked by `ALREADY_PROCESSED_PAYMENT` (Pitfall 11) | Add counter metric; verify domain state before marking done |
| k6 Outbox latency | Poll interval dominates latency, not architecture (Pitfall 8) | Tune poll interval before benchmark; document it in results |
| Integration testing | `PaymentHistory` AOP silent miss for async paths (Pitfall 10) | Assert history record exists after each adapter's integration test |

---

## Sources

- Codebase analysis (HIGH confidence):
  - `PaymentTransactionCoordinator.java` — transaction boundaries, compensation logic
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
