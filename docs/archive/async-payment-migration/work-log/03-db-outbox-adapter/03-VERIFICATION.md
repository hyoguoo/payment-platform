---
phase: 03-db-outbox-adapter
verified: 2026-03-15T03:00:00Z
status: passed
score: 10/10 must-haves verified
re_verification: false
---

# Phase 3: DB Outbox Adapter Verification Report

**Phase Goal:** DB Outbox 어댑터를 구현하여 비동기 결제 확인 전략을 제공한다.
**Verified:** 2026-03-15T03:00:00Z
**Status:** PASSED
**Re-verification:** No — initial verification

---

## Goal Achievement

### Observable Truths

All must-haves are drawn from the three plan frontmatter `must_haves` blocks (Plan 01, 02, 03).

| #  | Truth | Status | Evidence |
|----|-------|--------|----------|
| 1  | spring.payment.async-strategy=outbox 설정 시 OutboxConfirmAdapter가 활성화되고 202를 반환한다 | VERIFIED | `OutboxConfirmAdapter.java` — `@ConditionalOnProperty(name="spring.payment.async-strategy", havingValue="outbox")`; `confirm()` returns `ResponseType.ASYNC_202`; `PaymentController.confirm()` branches on `ASYNC_202` to return `ResponseEntity.accepted()` |
| 2  | 재고 감소와 payment_outbox PENDING 레코드 저장이 같은 트랜잭션으로 원자적으로 실행된다 | VERIFIED | `PaymentTransactionCoordinator.executeStockDecreaseWithOutboxCreation()` annotated `@Transactional(rollbackFor=PaymentOrderedProductStockException.class)` — calls `orderedProductUseCase.decreaseStockForOrders()` then `paymentOutboxUseCase.createPendingRecord()` in one transaction |
| 3  | PaymentOutbox 도메인 엔티티가 PENDING/IN_FLIGHT/DONE/FAILED 상태 전이를 강제하고 잘못된 전이 시 예외를 던진다 | VERIFIED | `PaymentOutbox.toInFlight()` throws `PaymentStatusException` if status != PENDING; `PaymentOutboxTest` covers 10 cases with `@ParameterizedTest @EnumSource` |
| 4  | OutboxConfirmAdapter가 confirm() 내에서 paymentCommandUseCase.executePayment()를 호출해 PaymentEvent에 paymentKey를 기록한다 | VERIFIED | `OutboxConfirmAdapter.confirm()` lines 36: `paymentCommandUseCase.executePayment(paymentEvent, command.getPaymentKey())` called before `executeStockDecreaseWithOutboxCreation()` |
| 5  | @Scheduled(fixedDelayString) OutboxWorker가 PENDING 레코드를 배치 조회해 Toss API를 호출하고 상태를 업데이트한다 | VERIFIED | `OutboxWorker.process()` annotated `@Scheduled(fixedDelayString="${scheduler.outbox-worker.fixed-delay-ms:1000}")`; calls `findPendingBatch`, `confirmPaymentWithGateway`, `markDone`; `OutboxWorkerTest` Test 2 covers full flow |
| 6  | 워커가 처리 시작 시 레코드를 IN_FLIGHT로 전환하므로 동일 레코드가 중복 처리되지 않는다 | VERIFIED | `PaymentOutboxUseCase.claimToInFlight()` annotated `@Transactional(propagation=REQUIRES_NEW)` — immediately commits IN_FLIGHT status; returns `false` if transition fails |
| 7  | retryCount가 RETRYABLE_LIMIT(5)에 도달하면 레코드가 FAILED로 전환되고 보상 트랜잭션이 실행된다 | VERIFIED | `PaymentOutboxUseCase.incrementRetryOrFail()` — delegates to `markFailed()` when `!currentOutbox.isRetryable()`; `OutboxWorker.processRecord()` calls `executePaymentFailureCompensation()` on `PaymentTossNonRetryableException`; `OutboxWorkerTest` Test 4 and 5 verify |
| 8  | markDone()/markFailed()는 멱등하다 — 이미 해당 상태이면 no-op | VERIFIED | `PaymentOutboxUseCase.markDone()`: early return if `status == DONE`; `markFailed()`: early return if `status == FAILED`; `PaymentOutboxUseCaseTest` Tests 4 and 6 assert `save()` not called |
| 9  | GET /api/v1/payments/{orderId}/status 호출 시 payment_outbox를 먼저 조회하고 PENDING/IN_FLIGHT이면 즉시 반환한다 | VERIFIED | `PaymentController.getPaymentStatus()` — calls `paymentOutboxUseCase.findActiveOutboxStatus(orderId)` first; if `isPresent()` returns immediately via `toPaymentStatusApiResponseFromOutbox()` |
| 10 | payment_outbox에 없거나 DONE/FAILED이면 기존 PaymentEvent 기반 조회 로직으로 fallback된다 | VERIFIED | `findActiveOutboxStatus()` filters to PENDING/IN_FLIGHT only — DONE/FAILED returns `Optional.empty()`; controller falls through to `paymentLoadUseCase.getPaymentEventByOrderId()` |

**Score:** 10/10 truths verified

---

### Required Artifacts

| Artifact | Provides | Level 1: Exists | Level 2: Substantive | Level 3: Wired | Status |
|----------|----------|-----------------|----------------------|----------------|--------|
| `src/main/java/.../payment/domain/enums/PaymentOutboxStatus.java` | PENDING, IN_FLIGHT, DONE, FAILED enum | FOUND | 4 enum constants with String value field | Used by PaymentOutbox, PaymentOutboxUseCase, PaymentPresentationMapper | VERIFIED |
| `src/main/java/.../payment/domain/PaymentOutbox.java` | createPending(), toInFlight(), toDone(), toFailed(), isRetryable(), incrementRetryCount() | FOUND | All 6 methods implemented with real state-machine logic | Used by OutboxConfirmAdapter, PaymentOutboxUseCase, OutboxWorker | VERIFIED |
| `src/main/java/.../payment/application/port/PaymentOutboxRepository.java` | save, findByOrderId, findPendingBatch, findTimedOutInFlight, existsByOrderId | FOUND | All 5 methods declared | Implemented by PaymentOutboxRepositoryImpl; used by PaymentOutboxUseCase | VERIFIED |
| `src/main/java/.../payment/infrastructure/adapter/OutboxConfirmAdapter.java` | PaymentConfirmService impl, @ConditionalOnProperty havingValue=outbox, ASYNC_202 | FOUND | Full confirm() logic — executePayment then executeStockDecreaseWithOutboxCreation | Registered as @Service; PaymentController uses via PaymentConfirmService port | VERIFIED |
| `src/main/java/.../payment/application/usecase/PaymentTransactionCoordinator.java` | executeStockDecreaseWithOutboxCreation() added | FOUND | Method present, @Transactional rollbackFor=PaymentOrderedProductStockException, calls decreaseStockForOrders + createPendingRecord | Called by OutboxConfirmAdapter | VERIFIED |
| `src/main/java/.../payment/application/usecase/PaymentOutboxUseCase.java` | createPendingRecord, claimToInFlight, markDone, markFailed, incrementRetryOrFail, recoverTimedOutInFlightRecords, findPendingBatch, findActiveOutboxStatus | FOUND | All 8 methods with real implementations (no UnsupportedOperationException stub remaining) | Used by OutboxWorker and PaymentController | VERIFIED |
| `src/main/java/.../payment/scheduler/OutboxWorker.java` | @Scheduled fixedDelayString, 3-step transaction, retry/fail handling | FOUND | Full process() and processRecord() with IN_FLIGHT claim, Toss API call, success/failure branches | @Component registered; uses PaymentOutboxUseCase, PaymentCommandUseCase, PaymentTransactionCoordinator | VERIFIED |
| `src/main/java/.../payment/infrastructure/entity/PaymentOutboxEntity.java` | JPA entity for payment_outbox table | FOUND | @Entity @Table(name="payment_outbox") with composite index; from()/toDomain() converters | Used by PaymentOutboxRepositoryImpl via JpaPaymentOutboxRepository | VERIFIED |
| `src/main/java/.../payment/infrastructure/repository/PaymentOutboxRepositoryImpl.java` | PaymentOutboxRepository implementation | FOUND | All 5 methods delegating to JpaPaymentOutboxRepository with entity conversions | Implements PaymentOutboxRepository port; injected into PaymentOutboxUseCase | VERIFIED |
| `src/main/java/.../payment/presentation/PaymentController.java` | getPaymentStatus() outbox-first logic | FOUND | PaymentOutboxUseCase injected; findActiveOutboxStatus() called first; fallback to PaymentLoadUseCase | @RestController serving GET /api/v1/payments/{orderId}/status | VERIFIED |
| `src/main/java/.../payment/presentation/PaymentPresentationMapper.java` | toPaymentStatusApiResponseFromOutbox() + mapOutboxStatusToPaymentStatusResponse() | FOUND | Both static methods present; PENDING→PENDING, IN_FLIGHT→PROCESSING mapping | Called from PaymentController.getPaymentStatus() | VERIFIED |

---

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `OutboxConfirmAdapter.confirm()` | `PaymentCommandUseCase.executePayment()` | paymentCommandUseCase 직접 주입 | WIRED | Line 36 of OutboxConfirmAdapter.java — called before executeStockDecreaseWithOutboxCreation() |
| `OutboxConfirmAdapter.confirm()` | `PaymentTransactionCoordinator.executeStockDecreaseWithOutboxCreation()` | transactionCoordinator 직접 주입 | WIRED | Lines 38-40 of OutboxConfirmAdapter.java |
| `PaymentTransactionCoordinator.executeStockDecreaseWithOutboxCreation()` | `PaymentOutboxRepository.save()` | via PaymentOutboxUseCase.createPendingRecord() | WIRED | Line 31: `paymentOutboxUseCase.createPendingRecord(orderId)` which calls `paymentOutboxRepository.save()` |
| `OutboxWorker.processRecord()` | `PaymentOutboxUseCase.claimToInFlight()` | REQUIRES_NEW 트랜잭션 | WIRED | Line 71: `boolean claimed = paymentOutboxUseCase.claimToInFlight(outbox)` |
| `OutboxWorker.processRecord()` | `PaymentCommandUseCase.confirmPaymentWithGateway()` | 트랜잭션 밖 HTTP 호출 | WIRED | Line 89: `PaymentGatewayInfo gatewayInfo = paymentCommandUseCase.confirmPaymentWithGateway(command)` |
| `OutboxWorker.processRecord()` | `PaymentTransactionCoordinator.executePaymentSuccessCompletion()` | Toss API 성공 시 별도 트랜잭션 | WIRED | Lines 92-93: called after successful gatewayInfo retrieval |
| `PaymentController.getPaymentStatus()` | `PaymentOutboxUseCase.findActiveOutboxStatus()` | outbox 우선 조회 | WIRED | Lines 67-72: `paymentOutboxUseCase.findActiveOutboxStatus(orderId)` with `isPresent()` guard |
| `PaymentController.getPaymentStatus()` | `PaymentLoadUseCase.getPaymentEventByOrderId()` | outbox empty 시 fallback | WIRED | Line 73: called only when outboxStatus is empty |

---

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| OUTBOX-01 | Plan 01, Plan 03 | confirm 요청 수신 시 payment_outbox 테이블에 PENDING 레코드를 저장하고 즉시 202를 반환한다 | SATISFIED | OutboxConfirmAdapter stores PENDING via createPendingRecord(); returns ASYNC_202; PaymentControllerMvcTest verifies PENDING/IN_FLIGHT status responses. Note: REQUIREMENTS.md text says "PaymentProcess 테이블" but intent (PENDING + 202) is met with dedicated payment_outbox table — a deliberate architectural decision documented in Plan 01 decisions |
| OUTBOX-02 | Plan 01 | 재고 감소는 202 반환 전 동기적으로 완료된다 | SATISFIED | executeStockDecreaseWithOutboxCreation() is called synchronously in OutboxConfirmAdapter.confirm() before returning; @Transactional with rollbackFor guarantees atomicity. Note: REQUIREMENTS.md names executeStockDecreaseWithJobCreation (old method) but the implemented method executeStockDecreaseWithOutboxCreation serves the same requirement |
| OUTBOX-03 | Plan 02 | @Scheduled 워커가 PENDING 레코드를 조회해 Toss API 호출 및 상태 업데이트를 처리한다 | SATISFIED | OutboxWorker.process() with @Scheduled; findPendingBatch → claimToInFlight → confirmPaymentWithGateway → markDone; 6 OutboxWorkerTest cases GREEN |
| OUTBOX-04 | Plan 02 | 워커는 처리 시작 시 레코드를 IN_FLIGHT로 전환해 중복 실행을 방지한다 (fixedDelay 방식) | SATISFIED | claimToInFlight() with @Transactional(propagation=REQUIRES_NEW) immediately commits; @Scheduled(fixedDelayString) not fixedRateString |
| OUTBOX-05 | Plan 02 | RETRYABLE_LIMIT = 5 제한 적용, 최대 재시도 후 FAILED 처리 | SATISFIED | PaymentOutbox.RETRYABLE_LIMIT=5; incrementRetryOrFail() delegates to markFailed() when !isRetryable(); OutboxWorkerTest Test 4 verifies RETRYABLE path |
| OUTBOX-06 | Plan 02 | 보상 트랜잭션(executePaymentFailureCompensation)은 멱등하게 동작한다 | SATISFIED | markDone()/markFailed() are idempotent — no-op if already in target state; PaymentOutboxUseCaseTest Tests 4 and 6 assert save() not called on repeat |

**Note on REQUIREMENTS.md stale text:** OUTBOX-01 mentions "PaymentProcess 테이블" and OUTBOX-02 names "executeStockDecreaseWithJobCreation". These are documentation artifacts from before the architectural decision (Plan 01 decision: dedicated payment_outbox table chosen over PaymentProcess reuse to prevent race conditions). The implementation intent of both requirements is fully satisfied.

---

### Anti-Patterns Found

No anti-patterns detected across all phase-03 files:

- No TODO/FIXME/HACK/PLACEHOLDER comments in production code
- No `UnsupportedOperationException` stubs remaining in PaymentOutboxUseCase (Plan 01 stub was fully replaced in Plan 02)
- No empty return stubs (`return null`, `return {}`, `return []`)
- No console.log-only handlers
- `buildMethodName = "build"` used instead of plan-specified `"allArgsBuild"` — functionally equivalent, not a defect

---

### Human Verification Required

The following items cannot be verified programmatically and require a running environment:

#### 1. End-to-End Outbox Flow

**Test:** With `spring.payment.async-strategy=outbox` set, POST /api/v1/payments/confirm with a valid order. Then poll GET /api/v1/payments/{orderId}/status repeatedly.
**Expected:** POST returns 202; first few GET calls return `status=PENDING` or `status=PROCESSING`; after the OutboxWorker fires (within ~1s), GET returns `status=DONE`.
**Why human:** Requires a running Spring Boot instance, a real (or mock) Toss API endpoint, and database. Cannot verify the timing of scheduler execution or the actual DB state transitions.

#### 2. REQUIRES_NEW Transaction Isolation Under Concurrent Load

**Test:** Submit two simultaneous confirm requests for the same orderId (or simulate two OutboxWorker threads picking up the same PENDING record).
**Expected:** Only one worker successfully claims the record to IN_FLIGHT; the second returns false from claimToInFlight() and skips processing.
**Why human:** Requires concurrent database access — cannot verify transaction isolation semantics through static code analysis alone.

#### 3. Java 21 Virtual Thread Parallel Mode

**Test:** Set `scheduler.outbox-worker.parallel-enabled=true`. Inject multiple PENDING records and observe parallel processing.
**Expected:** All records processed concurrently via virtual threads; try-with-resources ExecutorService shuts down cleanly after all tasks complete.
**Why human:** Requires runtime observation of thread behavior.

---

### Gaps Summary

No gaps. All 10 observable truths are verified. All 11 key artifacts are substantive and wired. All 6 OUTBOX requirements are satisfied. All 5 documented commits (c7cb945, e56b71d, 204f257, e3eebce, 394252d) exist in git history.

The two stale requirement descriptions in REQUIREMENTS.md (OUTBOX-01 naming "PaymentProcess 테이블", OUTBOX-02 naming "executeStockDecreaseWithJobCreation") are documentation artifacts predating the architectural decision — not implementation gaps.

---

_Verified: 2026-03-15T03:00:00Z_
_Verifier: Claude (gsd-verifier)_
