# Codebase Concerns

**Analysis Date:** 2026-03-29

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

### PaymentScheduler only runs expiration — no recovery under any profile

- **Issue:** `PaymentScheduler` now has only `expireOldReadyPayments()`. `PaymentRecoverServiceImpl` and `PaymentRecoverService` port were deleted in ASYNC-PAYMENT-CLEANUP(2026-03-29). Recovery of stuck PROCESSING/UNKNOWN payments no longer runs.
- **Files:**
  `src/main/java/com/hyoguoo/paymentplatform/payment/scheduler/PaymentScheduler.java`
- **Impact:** In the Sync strategy, server crashes between TX1(stock decrease) and TX2(executePayment) leave `PaymentEvent` stuck in READY with stock already decreased. Previously `recoverStuckPayments()` handled this; now no recovery runs.
- **Related:** See `docs/context/TODOS.md` — PaymentProcess 삭제 시 이 concern도 함께 정리된다.

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

### Sync strategy separates stock decrease and executePayment across two transactions — 복구 불가

- **Issue:** `PaymentConfirmServiceImpl.doConfirm()` calls `executeStockDecreaseWithJobCreation` (TX1, creates `PaymentProcess`) and then `executePayment` (TX2) in sequence. 서버 크래시 시 TX1 커밋 후 TX2 전이라면 재고는 감소했지만 `PaymentEvent`는 READY 상태로 남는다.
- **Files:**
  `src/main/java/com/hyoguoo/paymentplatform/payment/application/PaymentConfirmServiceImpl.java`
  `src/main/java/com/hyoguoo/paymentplatform/payment/application/usecase/PaymentTransactionCoordinator.java`
- **Impact:** ⚠️ ASYNC-PAYMENT-CLEANUP에서 `PaymentRecoverServiceImpl`이 삭제됐다. 이제 stuck READY+재고감소 상태를 복구하는 메커니즘이 없다. Outbox 전략은 단일 TX(executePaymentAndStockDecreaseWithOutbox)이므로 이 문제가 없다.
- **Fix approach:** PaymentProcess 삭제(TODOS.md 참조) 시 Sync 전략 TX 구조도 Outbox처럼 단일 TX로 통합하거나, 복구 스케줄러를 재도입해야 한다.

### executePaymentFailureCompensation always increases stock regardless of prior state

- **Issue:** `PaymentTransactionCoordinator.executePaymentFailureCompensation()` unconditionally calls `orderedProductUseCase.increaseStockForOrders(paymentOrderList)`. 동일 orderId에 대해 두 경로가 이 메서드를 동시에 호출하면 재고가 이중 복원된다.
- **Files:**
  `src/main/java/com/hyoguoo/paymentplatform/payment/application/usecase/PaymentTransactionCoordinator.java`
- **Impact:** Outbox 전략에서 `OutboxImmediateEventHandler`와 `OutboxWorker`가 동일 레코드를 동시에 처리하는 경쟁 조건 시 발생 가능. `claimToInFlight()` REQUIRES_NEW TX가 1차 방어선이지만 클레임 실패 경로에서 보상이 중복 실행될 여지가 있다.

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
- **Impact:** `IllegalArgumentException` is not a `PaymentTossNonRetryableException` or `PaymentTossRetryableException`. `OutboxImmediateEventHandler` / `OutboxWorker`에서 분류되지 않은 예외는 retryable/non-retryable 분기를 통과하지 못해 Outbox 레코드가 IN_FLIGHT 상태로 타임아웃까지 방치된다.
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
