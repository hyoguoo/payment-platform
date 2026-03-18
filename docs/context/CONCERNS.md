# Codebase Concerns

**Analysis Date:** 2026-03-18

---

## Bean Loading Issues

### KafkaConfirmPublisher loads unconditionally in all strategies

- **Issue:** `KafkaConfirmPublisher` is annotated only with `@Component`, with no `@ConditionalOnProperty`. It is always instantiated regardless of the active strategy.
- **Files:** `src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/kafka/KafkaConfirmPublisher.java:8-10`
- **Impact:** On startup with `spring.payment.async-strategy=sync` or `outbox`, Spring's `KafkaTemplate` still attempts to connect to `localhost:9092`. This generates Kafka connection error logs on every startup in non-Kafka environments (e.g., local dev, CI).
  The corresponding consumer, `KafkaConfirmListener`, is also a plain `@Component` with no guard — it will attempt to subscribe to the `payment-confirm` topic and log broker connectivity errors indefinitely.
- **Fix approach:** Add `@ConditionalOnProperty(name = "spring.payment.async-strategy", havingValue = "kafka")` to both `KafkaConfirmPublisher` (line 8) and `KafkaConfirmListener` (line 22-24). This mirrors the pattern already used by `KafkaAsyncConfirmService`.

---

### KafkaConfirmListener has no @ConditionalOnProperty

- **Issue:** `KafkaConfirmListener` is `@Component` without a strategy guard.
- **Files:** `src/main/java/com/hyoguoo/paymentplatform/payment/listener/KafkaConfirmListener.java:22-25`
- **Impact:** Even when running `sync` or `outbox` strategy, a live `@KafkaListener` is registered and attempts to consume from `payment-confirm`. In the absence of a Kafka broker, the listener thread logs repeated connection errors. In a Docker environment where a Kafka broker is running but a different strategy is active, the listener will consume phantom messages from any pre-existing `payment-confirm` topic content.
- **Fix approach:** Same `@ConditionalOnProperty(name = "spring.payment.async-strategy", havingValue = "kafka")` guard used by `KafkaAsyncConfirmService`.

---

## Test Coverage Gaps

### KafkaConfirmListenerIntegrationTest excluded by @Tag("integration")

- **Issue:** `BaseKafkaIntegrationTest` carries `@Tag("integration")` at line 17, which causes `KafkaConfirmListenerIntegrationTest` to be skipped in the default test run. The exclusion exists because Docker Desktop's API version is incompatible with the Testcontainers setup.
- **Files:**
  `src/test/java/com/hyoguoo/paymentplatform/core/test/BaseKafkaIntegrationTest.java:17`
  `src/test/java/com/hyoguoo/paymentplatform/payment/listener/KafkaConfirmListenerIntegrationTest.java:28`
- **Impact:** The end-to-end Kafka consumer flow (publish → consume → DONE transition, DLT handling, idempotency check) is never verified in standard CI. The duplicate-message test at line 101 uses `Thread.sleep(5000)` (line 120) instead of Awaitility, which is fragile under slow environments.
- **Fix approach:** Resolve Docker Desktop compatibility (upgrade Testcontainers or Docker Desktop). In the interim, document that `./gradlew test -Dgroups=integration` must be run manually before merging Kafka-related changes.

### No integration test for DLT handler in KafkaConfirmListener

- **What's not tested:** `KafkaConfirmListener.handleDlt()` (lines 75-89) performs failure compensation on DLT arrival. This path is only exercisable via the `@Tag("integration")` tests that are currently excluded from the standard run.
- **Files:**
  `src/main/java/com/hyoguoo/paymentplatform/payment/listener/KafkaConfirmListener.java:75-89`
  `src/test/java/com/hyoguoo/paymentplatform/payment/listener/KafkaConfirmListenerIntegrationTest.java`
- **Risk:** The DLT handler could double-compensate stock if `executePaymentFailureCompensation` was already called during earlier retry attempts. The DLT is the last safety net; an untested failure here means stuck FAILED states or over-compensated inventory.
- **Priority:** High

---

## Missing Benchmark Data

### BENCHMARK.md has all "-" placeholder values

- **Issue:** The benchmark results tables in `.planning/BENCHMARK.md` lines 36-53 contain only `-` for every metric across all three strategies and all VU levels (50 / 100 / 200 VU).
- **Files:** `.planning/BENCHMARK.md:34-54`
- **Impact:** The primary project goal — measuring TPS and latency differences between Sync / DB Outbox / Kafka strategies — has not been executed. No quantitative basis exists for choosing a strategy.
- **Fix approach:** Run `./scripts/k6/run-benchmark.sh` with each strategy (restart server with the appropriate `spring.payment.async-strategy` value) and fill in the table. The `application-benchmark.yml` already has `scheduler.enabled=true` (line 22), so the OutboxWorker will be active during the Outbox benchmark.

---

## Configuration Concerns

### Outbox worker sub-properties not tuned in application-benchmark.yml

- **Issue:** `application-benchmark.yml` sets `scheduler.enabled=true` (line 22) which activates `SchedulerConfig` and thus `OutboxWorker`. However, the `scheduler.outbox-worker.*` sub-properties (`fixed-delay-ms`, `batch-size`, `parallel-enabled`, `in-flight-timeout-minutes`) are not overridden in the benchmark profile — they fall back to `application.yml` defaults (`fixedDelay=1000ms`, `batchSize=10`, `parallel-enabled=false`).
- **Files:**
  `src/main/resources/application-benchmark.yml:21-22`
  `src/main/resources/application.yml:58-63`
  `src/main/java/com/hyoguoo/paymentplatform/payment/scheduler/OutboxWorker.java:37-44`
- **Impact:** Under 200 VU benchmark load with FakeTossHttpOperator adding 100–300ms per call, each batch of 10 takes 1–3 seconds serially. The Outbox strategy throughput is capped at roughly 10 TPS for end-to-end processing. Benchmark results may understate the maximum achievable Outbox throughput.
- **Fix approach:** Add benchmark-specific overrides (e.g., `batch-size: 50`, `parallel-enabled: true`) to `application-benchmark.yml` when measuring Outbox peak throughput.

### Recovery schedulers are silently disabled under benchmark profile

- **Issue:** `PaymentScheduler` methods are guarded by `@ConditionalOnProperty` on `scheduler.payment-status-sync.enabled` and `scheduler.payment-recovery.enabled`. Neither key is present in `application-benchmark.yml`. Since `ConditionalOnProperty` defaults to `matchIfMissing = false`, no recovery tasks run despite `scheduler.enabled=true`.
- **Files:**
  `src/main/java/com/hyoguoo/paymentplatform/payment/scheduler/PaymentScheduler.java:20-43`
  `src/main/resources/application-benchmark.yml`
- **Impact:** This is probably correct for a benchmark run (no noise from recovery). However, it is undocumented and could mislead future operators into thinking recovery is running during benchmarks.
- **Fix approach:** Add an explicit comment in `application-benchmark.yml` stating that recovery schedulers are intentionally disabled.

---

## Transaction and Concurrency Edge Cases

### recoverTimedOutInFlightRecords may trigger duplicate Toss API confirm

- **Issue:** `PaymentOutboxUseCase.recoverTimedOutInFlightRecords()` calls `outbox.incrementRetryCount()` on timed-out IN_FLIGHT records. `incrementRetryCount()` resets `status = PENDING` (line 63 of `PaymentOutbox.java`), requeueing the record for re-processing. It does not check whether the Toss API call may have already succeeded during the in-flight window. If the Toss API completed successfully during the timeout window, the OutboxWorker will call `confirmPaymentWithGateway()` again on the same `paymentKey`.
- **Files:**
  `src/main/java/com/hyoguoo/paymentplatform/payment/application/usecase/PaymentOutboxUseCase.java:76-83`
  `src/main/java/com/hyoguoo/paymentplatform/payment/domain/PaymentOutbox.java:61-64`
  `src/main/java/com/hyoguoo/paymentplatform/payment/scheduler/OutboxWorker.java:49`
- **Impact:** Toss Payments uses idempotency keys based on `orderId`; re-confirming a completed payment will return a non-retryable error, triggering `executePaymentFailureCompensation` and rolling back stock decreases for a successfully paid order.
- **Fix approach:** Before resetting to PENDING, call `paymentGatewayPort.getStatus(orderId)` to check whether the payment is already DONE; if so, run `executePaymentSuccessCompletion` instead of requeueing.

### Sync strategy separates stock decrease and executePayment across two transactions

- **Issue:** `PaymentConfirmServiceImpl.doConfirm()` calls `executeStockDecreaseWithJobCreation` (TX1, creates `PaymentProcess` record) and then `executePayment` (TX2) in sequence. If the server crashes between TX1 commit and TX2 start, stock has been decreased but the payment event is still READY.
- **Files:**
  `src/main/java/com/hyoguoo/paymentplatform/payment/application/PaymentConfirmServiceImpl.java:66-82`
  `src/main/java/com/hyoguoo/paymentplatform/payment/application/usecase/PaymentTransactionCoordinator.java:48-54`
- **Impact:** The `recoverStuckPayments()` scheduler detects the orphaned `PaymentProcess` (PROCESSING) via `findAllProcessingJobs()` and calls Toss status API to resolve it. Recovery path exists. Risk is low (requires a crash in a sub-millisecond window) but the asymmetry with Kafka/Outbox strategies (which are atomic) is worth noting.
- **Safe modification:** Do not change without verifying `recoverStuckPayments()` behaviour — the `PaymentProcess` record is the hook the recovery relies on.

### executePaymentFailureCompensation always increases stock regardless of prior state

- **Issue:** `PaymentTransactionCoordinator.executePaymentFailureCompensation()` unconditionally calls `orderedProductUseCase.increaseStockForOrders(paymentOrderList)` (line 80). In the Kafka flow, if both the DLT handler and an earlier retry attempt both reach this method for the same orderId, stock would be increased twice.
- **Files:**
  `src/main/java/com/hyoguoo/paymentplatform/payment/application/usecase/PaymentTransactionCoordinator.java:69-83`
- **Impact:** Edge case requiring a race between recovery paths for the same orderId. The `validateCompletionStatus` guard in `KafkaConfirmListener` mitigates this at the listener level. Risk is low but non-zero in high-latency failure scenarios.

---

## Code Quality Issues

### Duplicate error code E03002 in PaymentErrorCode

- **Issue:** `INVALID_STATUS_TO_EXECUTE` and `INVALID_TOTAL_AMOUNT` both use error code string `"E03002"`.
- **Files:** `src/main/java/com/hyoguoo/paymentplatform/payment/exception/common/PaymentErrorCode.java:12-13`
- **Impact:** When a client receives `E03002`, the error meaning is ambiguous. Error monitoring cannot distinguish a status-transition violation from an amount mismatch.
- **Fix approach:** Assign a new unique code (e.g., `"E03026"`) to `INVALID_TOTAL_AMOUNT`.

### Typo in error code string for TOSS_RETRYABLE_ERROR

- **Issue:** `TOSS_RETRYABLE_ERROR` uses code `"EO3009"` (letter O, not digit 0) instead of `"E03009"`.
- **Files:** `src/main/java/com/hyoguoo/paymentplatform/payment/exception/common/PaymentErrorCode.java:18`
- **Impact:** Any client or monitoring alert filtering on the `"E03"` prefix will miss retryable Toss errors; they will appear as unknown codes in dashboards.
- **Fix approach:** Change `"EO3009"` to `"E03009"`.

### TODO: error response parsing in HttpTossOperator is fragile

- **Issue:** `HttpTossOperator.parseErrorResponse()` has a `// TODO: 파싱 방법 개선 필요` comment (line 150). The implementation uses a regex (`\{.*}`) to extract a JSON fragment from the raw error string. A Toss API error response that does not contain a `{...}` JSON block will throw an uncaught `IllegalArgumentException`.
- **Files:** `src/main/java/com/hyoguoo/paymentplatform/paymentgateway/infrastructure/api/HttpTossOperator.java:150-168`
- **Impact:** `IllegalArgumentException` is not a `PaymentTossNonRetryableException` or `PaymentTossRetryableException`. In `KafkaConfirmListener`, an unclassified exception propagates past the retry/DLT classification and will be treated as an unhandled exception — the message will not be re-queued and the Outbox record may be left IN_FLIGHT until timeout.
- **Fix approach:** Catch `IllegalArgumentException` in `parseErrorResponse` and rethrow as `PaymentTossNonRetryableException`, or switch to direct `ObjectMapper.readValue(rawBody, TossPaymentApiFailResponse.class)` with explicit error handling.

### UNKNOWN PaymentStatus silently maps to READY in convertToTossPaymentStatus

- **Issue:** `PaymentCommandUseCase.convertToTossPaymentStatus()` default arm maps `UNKNOWN` (and any future unmatched status values) to `TossPaymentStatus.READY` with a comment `// UNKNOWN -> READY로 매핑` (line 172).
- **Files:** `src/main/java/com/hyoguoo/paymentplatform/payment/application/usecase/PaymentCommandUseCase.java:172`
- **Impact:** If `validateCompletionStatus` is called on a PaymentEvent in UNKNOWN status, the gateway info will report `TossPaymentStatus.READY`, and the check at `PaymentEvent.validateCompletionStatus()` (lines 100-103 of `PaymentEvent.java`) will throw `NOT_IN_PROGRESS_ORDER` (non-retryable). This converts an UNKNOWN-status payment intended for retry into a permanent failure.
- **Fix approach:** Add an explicit `case UNKNOWN ->` arm returning a sentinel value, and handle it in the caller instead of falling through the default.

---

## PaymentStatusServiceImpl Coverage

### EXPIRED and UNKNOWN events return PROCESSING from the status endpoint

- **Issue:** `PaymentStatusServiceImpl.mapEventStatus()` maps all non-DONE, non-FAILED statuses (including EXPIRED and UNKNOWN) to `StatusType.PROCESSING` via the `default` arm.
- **Files:**
  `src/main/java/com/hyoguoo/paymentplatform/payment/application/PaymentStatusServiceImpl.java:53-58`
  `src/main/java/com/hyoguoo/paymentplatform/payment/application/dto/response/PaymentStatusResult.java:11-16`
- **Impact:** A client polling an expired payment receives `status=PROCESSING` indefinitely rather than a terminal state. This causes the k6 benchmark polling loop (which waits for DONE or FAILED) to time out for expired orders, inflating the error rate metric.
- **Fix approach:** Add `EXPIRED` and `UNKNOWN` to `StatusType` and map them explicitly in `mapEventStatus()`. Alternatively, map `EXPIRED` to `FAILED` as a terminal state to avoid client-visible API changes.

### Status endpoint throws 404 for unknown orderId with no test coverage

- **Issue:** When no Outbox record exists and `getPaymentEventByOrderId` cannot find the event, `PaymentFoundException` propagates as 404. No test in `PaymentStatusServiceImplTest` covers this path.
- **Files:**
  `src/main/java/com/hyoguoo/paymentplatform/payment/application/PaymentStatusServiceImpl.java:29`
  `src/test/java/com/hyoguoo/paymentplatform/payment/application/PaymentStatusServiceImplTest.java`
- **Risk:** Low; the exception propagation is standard and handled by `GlobalExceptionHandler`. Minor test coverage gap.
- **Priority:** Low

---

*Concerns audit: 2026-03-18*
