# Phase 3: DB Outbox Adapter - Research

**Researched:** 2026-03-15
**Domain:** DB Outbox Pattern / Spring Scheduler / Java 21 Virtual Threads / JPA
**Confidence:** HIGH

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Outbox 스토리지**
- 전용 `payment_outbox` 테이블 신규 생성 — `PaymentProcess` 테이블 재활용 안 함
- 이유: `PaymentRecoverServiceImpl.recoverStuckPayments()`가 `PaymentProcess.PROCESSING`을 조회하므로 재활용 시 race condition 위험
- 상태: `PENDING` / `IN_FLIGHT` / `DONE` / `FAILED`
  - PENDING: confirm 요청 접수, 워커 처리 대기
  - IN_FLIGHT: 워커가 처리 시작 (중복 실행 방지용)
  - DONE: Toss API 호출 성공, PaymentEvent DONE으로 전환 완료
  - FAILED: 최대 재시도 초과, 보상 트랜잭션 완료
- 페이로드: `order_id`만 저장 — 나머지 정보는 워커가 PaymentEvent에서 재조회
- 재시도: `retry_count` 컬럼 포함 — RETRYABLE_LIMIT=5 적용
- IN_FLIGHT 타임아웃 컬럼: `in_flight_at` — 앱 재시작 등 비정상 케이스 복구에 사용

**워커 동작**
- `@Scheduled(fixedDelay)` 방식, 기본값 `scheduler.outbox-worker.fixed-delay-ms=1000`
- 배치 조회: 1회 실행 시 PENDING 레코드를 최대 N건 조회 (FIFO, createdAt 오름차순), 기본값 `scheduler.outbox-worker.batch-size=10`
- 처리 모드 전환 가능: `scheduler.outbox-worker.parallel-enabled`
  - `false` (기본): 배치 N건 순차 처리
  - `true`: Java 21 가상 스레드(`Executors.newVirtualThreadPerTaskExecutor()`)로 배치 N건 병렬 처리
- IN_FLIGHT timeout: `scheduler.outbox-worker.in-flight-timeout-minutes=5`
- FAILED 최종 처리 시 워커가 직접 `executePaymentFailureCompensation` 호출 (재고 복원 + PaymentProcess 실패 + PaymentEvent FAILED)
- 보상 트랜잭션은 멱등하게 동작해야 함 (OUTBOX-06)

**Status 엔드포인트 PENDING 매핑**
- `GET /api/v1/payments/{orderId}/status`는 payment_outbox 먼저 조회
  - PENDING 레코드 존재 → `PENDING` 반환
  - IN_FLIGHT 레코드 존재 → `PROCESSING` 반환
  - DONE/FAILED → PaymentEvent fallback으로 최종 상태 반환
  - payment_outbox에 없음 → PaymentEvent.status 기반 기존 매핑 로직 유지
- Phase 1에서 정의한 Status enum (`PENDING / PROCESSING / DONE / FAILED`) 변경 없음

**트랜잭션 경계**
- confirm 수신 시: 재고 감소 + `payment_outbox` PENDING 생성 → 같은 트랜잭션 (원자성 보장)
- 워커 처리 시:
  1. PENDING → IN_FLIGHT 전환: 별도 트랜잭션 (즉시 flush/commit)
  2. Toss API 호출: 트랜잭션 밖 (HTTP 호출이 롤백 대상에 포함되지 않도록)
  3. 결과 업데이트 (DONE/FAILED + PaymentEvent 전환): 별도 트랜잭션

### Claude's Discretion
- `payment_outbox` 테이블 DDL 정확한 컬럼 타입/인덱스 설계
- `OutboxConfirmAdapter` 패키지 위치 (infrastructure/adapter/ 기존 패턴 따름)
- 워커 클래스명 (예: `OutboxWorker`, `OutboxProcessorService`)
- IN_FLIGHT 타임아웃 복구 로직을 메인 워커에 포함할지 별도 스케줄러로 분리할지

### Deferred Ideas (OUT OF SCOPE)
없음 — 논의가 Phase 3 범위 내에서 유지됨
</user_constraints>

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| OUTBOX-01 | confirm 요청 수신 시 `payment_outbox` 테이블에 PENDING 레코드를 저장하고 즉시 202를 반환한다 | OutboxConfirmAdapter + PaymentOutboxRepository.save() + ASYNC_202 responseType |
| OUTBOX-02 | 재고 감소(`executeStockDecreaseWithJobCreation`)는 202 반환 전 동기적으로 완료된다 | `OrderedProductUseCase.decreaseStockForOrders()` 호출은 기존 패턴 유지, 단 `createProcessingJob` 없이 outbox 저장으로 대체 |
| OUTBOX-03 | `@Scheduled` 워커가 PENDING 레코드를 조회해 Toss API 호출 및 상태 업데이트를 처리한다 | OutboxWorker + `@Scheduled(fixedDelayString)` 패턴 (PaymentScheduler 참고) |
| OUTBOX-04 | 워커는 처리 시작 시 레코드를 IN_FLIGHT로 전환해 중복 실행을 방지한다 (`fixedDelay` 방식) | 별도 트랜잭션으로 PENDING→IN_FLIGHT 전환 후 커밋, 이후 HTTP 호출 |
| OUTBOX-05 | 기존 `RETRYABLE_LIMIT = 5` 제한을 그대로 적용해 최대 재시도 후 FAILED 처리한다 | PaymentOutbox 도메인 엔티티에 `retry_count` + `isRetryable()` 메서드 |
| OUTBOX-06 | 보상 트랜잭션(`executePaymentFailureCompensation`)은 멱등하게 동작한다 (중복 호출 시 안전) | `PaymentTransactionCoordinator.executePaymentFailureCompensation()`의 기존 멱등 가드(`existsByOrderId`) 확인 완료 |
</phase_requirements>

---

## Summary

Phase 3는 기존 동기 confirm 흐름을 DB Outbox 패턴으로 래핑하는 작업이다. 핵심은 세 가지다: (1) `payment_outbox` 전용 테이블 신규 생성, (2) confirm 요청 시 재고 감소 + PENDING 레코드 저장을 원자적 트랜잭션으로 처리하고 즉시 202 반환, (3) `@Scheduled` 워커가 PENDING 레코드를 IN_FLIGHT로 전환한 뒤 Toss API를 호출하고 결과를 반영.

기존 코드베이스가 이미 대부분의 인프라를 제공한다. `PaymentTransactionCoordinator.executePaymentSuccessCompletion()` / `executePaymentFailureCompensation()`은 그대로 재사용 가능하다. `executeStockDecreaseWithJobCreation()`은 내부적으로 `PaymentProcessUseCase.createProcessingJob()`을 호출하는데, Outbox 어댑터에서는 이 대신 `OutboxUseCase.createPendingRecord()`를 호출하는 별도 메서드가 필요하다 — 기존 `PaymentTransactionCoordinator`를 수정하거나 새 조합 메서드를 추가해야 한다.

`PaymentController.getPaymentStatus()`는 현재 `PaymentLoadUseCase.getPaymentEventByOrderId()`만 조회하는데, OUTBOX-01이 활성화되면 payment_outbox 우선 조회 → PaymentEvent fallback 순서로 바꿔야 한다. 이를 위해 새로운 포트/유즈케이스(OutboxLoadUseCase 또는 PaymentLoadUseCase 확장)가 필요하다.

**Primary recommendation:** `OutboxConfirmAdapter` → `PaymentTransactionCoordinator`에 신규 메서드 `executeStockDecreaseWithOutboxCreation()` 추가 → `OutboxWorker`(@Scheduled) 흐름으로 구현한다. 기존 도메인/coordinator를 최대한 재사용하고, outbox 전용 도메인 클래스(`PaymentOutbox`)를 새로 추가한다.

---

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Spring Boot `@Scheduled` | 3.3.3 (managed) | 워커 스케줄링 | 기존 `PaymentScheduler`에서 이미 사용 중 |
| Spring `@Transactional` | 3.3.3 (managed) | 트랜잭션 경계 분리 | `PaymentProcessUseCase` 패턴과 동일 |
| Spring Data JPA | 3.3.3 (managed) | `payment_outbox` 테이블 접근 | 기존 모든 repository 패턴 |
| Java 21 Virtual Threads | JDK 21 | 병렬 워커 모드 | `Executors.newVirtualThreadPerTaskExecutor()` |
| Lombok | managed | 보일러플레이트 제거 | 프로젝트 전반 표준 |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `@ConditionalOnProperty` | Spring Boot 3.3.3 | 어댑터 조건부 등록 | `havingValue="outbox"` |
| `TransactionTemplate` (테스트) | Spring 6.x | 트랜잭션 콜백 단위 테스트 | 워커 트랜잭션 분리 검증 |

**Installation:** 추가 의존성 없음 — 기존 스택 재사용

---

## Architecture Patterns

### New Classes Overview

```
payment/
├── domain/
│   ├── PaymentOutbox.java                 # 신규 도메인 엔티티
│   └── enums/
│       └── PaymentOutboxStatus.java       # PENDING/IN_FLIGHT/DONE/FAILED
├── application/
│   ├── port/
│   │   └── PaymentOutboxRepository.java   # 신규 포트 인터페이스
│   └── usecase/
│       ├── PaymentOutboxUseCase.java       # CRUD + 상태 전이 유즈케이스
│       └── PaymentTransactionCoordinator.java  # executeStockDecreaseWithOutboxCreation() 추가
├── infrastructure/
│   ├── adapter/
│   │   └── OutboxConfirmAdapter.java      # 신규 어댑터 (havingValue="outbox")
│   ├── entity/
│   │   └── PaymentOutboxEntity.java       # JPA 엔티티
│   └── repository/
│       ├── JpaPaymentOutboxRepository.java
│       └── PaymentOutboxRepositoryImpl.java
└── scheduler/
    └── OutboxWorker.java                   # 신규 @Scheduled 워커
```

### Pattern 1: OutboxConfirmAdapter — `@ConditionalOnProperty`

`SyncConfirmAdapter`와 동일한 패턴으로 등록한다.

```java
// infrastructure/adapter/OutboxConfirmAdapter.java
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "spring.payment.async-strategy",
        havingValue = "outbox"
)
public class OutboxConfirmAdapter implements PaymentConfirmService {

    private final PaymentTransactionCoordinator transactionCoordinator;
    private final PaymentLoadUseCase paymentLoadUseCase;

    @Override
    public PaymentConfirmAsyncResult confirm(PaymentConfirmCommand command) {
        PaymentEvent paymentEvent = paymentLoadUseCase.getPaymentEventByOrderId(command.getOrderId());
        transactionCoordinator.executeStockDecreaseWithOutboxCreation(
                command.getOrderId(),
                paymentEvent.getPaymentOrderList()
        );
        return PaymentConfirmAsyncResult.builder()
                .responseType(ResponseType.ASYNC_202)
                .orderId(command.getOrderId())
                .amount(command.getAmount())
                .build();
    }
}
```

### Pattern 2: PaymentTransactionCoordinator — 신규 메서드 추가

기존 `executeStockDecreaseWithJobCreation()`은 변경하지 않는다. 새 메서드만 추가한다.

```java
// 기존 메서드 유지, 아래 신규 메서드만 추가
@Transactional(rollbackFor = PaymentOrderedProductStockException.class)
public PaymentOutbox executeStockDecreaseWithOutboxCreation(
        String orderId,
        List<PaymentOrder> paymentOrderList
) throws PaymentOrderedProductStockException {
    orderedProductUseCase.decreaseStockForOrders(paymentOrderList);
    return paymentOutboxUseCase.createPendingRecord(orderId);
}
```

### Pattern 3: OutboxWorker — 3단계 트랜잭션 분리

워커는 트랜잭션을 3단계로 분리한다. 각 단계는 독립 트랜잭션이고, Toss API 호출은 트랜잭션 밖에서 실행된다.

```java
// scheduler/OutboxWorker.java
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxWorker {

    private final PaymentOutboxUseCase paymentOutboxUseCase;
    private final PaymentLoadUseCase paymentLoadUseCase;
    private final PaymentCommandUseCase paymentCommandUseCase;
    private final PaymentTransactionCoordinator transactionCoordinator;
    @Value("${scheduler.outbox-worker.batch-size:10}")
    private int batchSize;
    @Value("${scheduler.outbox-worker.parallel-enabled:false}")
    private boolean parallelEnabled;
    @Value("${scheduler.outbox-worker.in-flight-timeout-minutes:5}")
    private int inFlightTimeoutMinutes;

    @Scheduled(fixedDelayString = "${scheduler.outbox-worker.fixed-delay-ms:1000}")
    public void process() {
        // IN_FLIGHT 타임아웃 레코드 복구
        paymentOutboxUseCase.recoverTimedOutInFlightRecords(inFlightTimeoutMinutes);

        // PENDING 배치 조회
        List<PaymentOutbox> pendingRecords =
                paymentOutboxUseCase.findPendingBatch(batchSize);

        if (pendingRecords.isEmpty()) {
            return;
        }

        if (parallelEnabled) {
            processParallel(pendingRecords);
        } else {
            pendingRecords.forEach(this::processRecord);
        }
    }

    private void processParallel(List<PaymentOutbox> records) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            records.forEach(record ->
                    executor.submit(() -> processRecord(record)));
        }
    }

    private void processRecord(PaymentOutbox outbox) {
        // Step 1: PENDING → IN_FLIGHT (별도 트랜잭션, 즉시 커밋)
        boolean claimed = paymentOutboxUseCase.claimToInFlight(outbox.getId());
        if (!claimed) {
            return; // 다른 워커가 이미 처리 중 (미래 멀티 인스턴스 대비)
        }

        String orderId = outbox.getOrderId();
        PaymentEvent paymentEvent = paymentLoadUseCase.getPaymentEventByOrderId(orderId);

        try {
            // Step 2: Toss API 호출 (트랜잭션 밖)
            PaymentConfirmCommand command = buildConfirmCommand(paymentEvent);
            PaymentGatewayInfo gatewayInfo =
                    paymentCommandUseCase.confirmPaymentWithGateway(command);

            // Step 3-A: DONE (별도 트랜잭션)
            transactionCoordinator.executePaymentSuccessCompletion(
                    orderId, paymentEvent, gatewayInfo.getPaymentDetails().getApprovedAt());
            paymentOutboxUseCase.markDone(orderId);

        } catch (PaymentTossNonRetryableException | RetryLimitExceededException e) {
            // Step 3-B: FAILED (별도 트랜잭션) — 보상 트랜잭션
            transactionCoordinator.executePaymentFailureCompensation(
                    orderId, paymentEvent, paymentEvent.getPaymentOrderList(), e.getMessage());
            paymentOutboxUseCase.markFailed(orderId, e.getMessage());

        } catch (PaymentTossRetryableException e) {
            // 재시도 카운트 증가, 한도 초과 시 FAILED
            paymentOutboxUseCase.incrementRetryOrFail(orderId, e.getMessage(), outbox);
        }
    }
}
```

### Pattern 4: PaymentOutbox 도메인 엔티티

```java
// domain/PaymentOutbox.java
@Getter
@Builder(builderMethodName = "allArgsBuilder", buildMethodName = "allArgsBuild")
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentOutbox {

    public static final int RETRYABLE_LIMIT = 5;

    private Long id;
    private String orderId;
    private PaymentOutboxStatus status;
    private int retryCount;
    private LocalDateTime inFlightAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static PaymentOutbox createPending(String orderId) {
        return PaymentOutbox.allArgsBuilder()
                .orderId(orderId)
                .status(PaymentOutboxStatus.PENDING)
                .retryCount(0)
                .allArgsBuild();
    }

    public boolean isRetryable() {
        return this.retryCount < RETRYABLE_LIMIT;
    }

    public void toInFlight(LocalDateTime inFlightAt) {
        if (this.status != PaymentOutboxStatus.PENDING) {
            throw PaymentStatusException.of(PaymentErrorCode.INVALID_STATUS_TO_IN_FLIGHT);
        }
        this.status = PaymentOutboxStatus.IN_FLIGHT;
        this.inFlightAt = inFlightAt;
    }

    public void toDone() {
        this.status = PaymentOutboxStatus.DONE;
    }

    public void toFailed() {
        this.status = PaymentOutboxStatus.FAILED;
    }

    public void incrementRetryCount() {
        this.retryCount++;
        this.status = PaymentOutboxStatus.PENDING; // 재시도를 위해 PENDING으로 복귀
    }
}
```

### Pattern 5: Status 엔드포인트 — outbox 우선 조회

현재 `PaymentController.getPaymentStatus()`는 `PaymentLoadUseCase.getPaymentEventByOrderId()`만 호출한다. outbox 우선 조회 로직을 추가해야 한다.

```java
// PaymentController.getPaymentStatus() 수정
@GetMapping("/api/v1/payments/{orderId}/status")
public ResponseEntity<PaymentStatusApiResponse> getPaymentStatus(
        @PathVariable String orderId) {
    // outbox 상태 우선 확인
    Optional<PaymentOutboxStatus> outboxStatus =
            paymentOutboxUseCase.findActiveOutboxStatus(orderId);

    if (outboxStatus.isPresent()) {
        return ResponseEntity.ok(
                PaymentPresentationMapper.toPaymentStatusApiResponseFromOutbox(orderId, outboxStatus.get())
        );
    }

    // fallback: PaymentEvent 기반 기존 로직
    PaymentEvent paymentEvent = paymentLoadUseCase.getPaymentEventByOrderId(orderId);
    return ResponseEntity.ok(PaymentPresentationMapper.toPaymentStatusApiResponse(paymentEvent));
}
```

**매핑 규칙:**
- `PaymentOutboxStatus.PENDING` → `PaymentStatusResponse.PENDING`
- `PaymentOutboxStatus.IN_FLIGHT` → `PaymentStatusResponse.PROCESSING`
- `PaymentOutboxStatus.DONE / FAILED` → PaymentEvent fallback 사용

### Anti-Patterns to Avoid

- **PaymentProcess 테이블 재활용:** `PaymentRecoverServiceImpl.recoverStuckPayments()`가 `PaymentProcessStatus.PROCESSING` 레코드를 전부 조회하므로 outbox 레코드를 같은 테이블에 넣으면 recovery scheduler가 중복 처리를 시도한다. 전용 테이블 사용이 필수다.
- **Toss HTTP 호출을 트랜잭션 안에서 실행:** HTTP 타임아웃 발생 시 DB 커넥션을 오래 점유하고, 롤백 대상에 HTTP 결과가 포함되어 일관성이 깨진다.
- **IN_FLIGHT 전환과 API 호출을 같은 트랜잭션에 포함:** 커밋 전에 장애가 나면 IN_FLIGHT 상태가 유실되어 중복 처리가 발생한다.
- **`fixedRate` 대신 `fixedDelay` 사용:** `fixedRate`는 이전 실행이 끝나지 않아도 다음 실행이 겹칠 수 있다. `fixedDelay`는 이전 실행 완료 후 지정 시간 뒤에 실행되므로 중복 방지에 유리하다.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| 재고 감소 원자적 처리 | 직접 stock 쿼리 | `OrderedProductUseCase.decreaseStockForOrders()` | 비관적 락, 재고 검증 이미 구현됨 |
| 보상 트랜잭션 | 수동 롤백 로직 | `PaymentTransactionCoordinator.executePaymentFailureCompensation()` | `existsByOrderId()` 멱등 가드 포함 |
| Toss 성공 처리 | 직접 PaymentEvent 업데이트 | `PaymentTransactionCoordinator.executePaymentSuccessCompletion()` | `completeJob()` + `markPaymentAsDone()` 원자적으로 결합 |
| Toss API 호출 | HTTP 클라이언트 직접 구현 | `PaymentCommandUseCase.confirmPaymentWithGateway()` | 재시도 분류(`RETRYABLE/NON_RETRYABLE`) 포함 |
| PaymentEvent 조회 | Repository 직접 DI | `PaymentLoadUseCase.getPaymentEventByOrderId()` | not-found 예외 처리 포함 |

---

## Common Pitfalls

### Pitfall 1: executeStockDecreaseWithJobCreation 재사용 시도

**What goes wrong:** 기존 `executeStockDecreaseWithJobCreation()`을 그대로 호출하면 `payment_process` 테이블에 PROCESSING 레코드가 생성된다. 이 레코드는 `PaymentRecoverServiceImpl.recoverStuckPayments()`가 주기적으로 조회해 Toss API를 재호출하는데, outbox 워커도 동시에 처리하면 중복 결제 시도가 발생한다.

**How to avoid:** `PaymentTransactionCoordinator`에 신규 메서드 `executeStockDecreaseWithOutboxCreation()`을 추가하고, 내부에서 `paymentProcessUseCase.createProcessingJob()` 대신 `paymentOutboxUseCase.createPendingRecord()`를 호출한다.

**Warning signs:** `payment_process` 테이블에 PROCESSING 레코드가 생기면 구현 오류.

### Pitfall 2: IN_FLIGHT 전환 후 API 호출 실패 시 레코드 고착

**What goes wrong:** 워커가 IN_FLIGHT로 전환한 뒤 JVM 크래시 등 비정상 종료가 발생하면 레코드가 영구 IN_FLIGHT 상태로 남아 재처리되지 않는다.

**How to avoid:** `in_flight_at` 컬럼에 전환 시각을 기록하고, 워커 실행 시작 시 `in_flight_at < now - timeout`인 레코드를 PENDING으로 되돌리는 복구 쿼리를 실행한다. `scheduler.outbox-worker.in-flight-timeout-minutes=5` 설정값 사용.

**Warning signs:** `in_flight_at`이 5분 이상 지난 IN_FLIGHT 레코드가 존재하면 복구 쿼리 미작동.

### Pitfall 3: 보상 트랜잭션 비멱등 처리

**What goes wrong:** 워커 재시도 시 이미 FAILED 처리된 레코드에 대해 `executePaymentFailureCompensation()`을 중복 호출하면 재고가 이중으로 복원된다.

**How to avoid:** `PaymentTransactionCoordinator.executePaymentFailureCompensation()`은 이미 `existsByOrderId(orderId)` 가드로 `payment_process` Job 존재 여부를 확인한다. Outbox 어댑터에서는 `payment_process` Job이 없으므로 이 가드는 false를 반환하고 `failJob()`을 건너뛴다 — 이는 정상 동작이다. 그러나 재고 복원(`increaseStockForOrders()`)은 여전히 호출된다. outbox FAILED 상태 확인으로 이중 호출을 방지해야 한다. `paymentOutboxUseCase.markFailed()` 전에 상태를 먼저 확인하거나, `markFailed()` 자체를 멱등하게 설계한다 (이미 FAILED이면 no-op).

**Warning signs:** 재고가 실제 구매 수량보다 더 많이 증가하면 이중 보상 트랜잭션 발생.

### Pitfall 4: PaymentEvent 상태가 READY인 채로 Toss API 호출

**What goes wrong:** 기존 `PaymentConfirmServiceImpl`은 `PaymentCommandUseCase.executePayment()`를 호출해 PaymentEvent를 READY → IN_PROGRESS로 전환한 뒤 Toss API를 호출한다. Outbox 어댑터는 즉시 202를 반환하므로 이 전환 시점이 다르다. 워커가 Toss API를 호출할 때 PaymentEvent가 여전히 READY 상태일 수 있다.

**How to avoid:** 워커가 Toss API 호출 전에 `paymentCommandUseCase.executePayment()`를 호출해 IN_PROGRESS로 전환해야 한다. `executePayment()`는 READY/IN_PROGRESS/UNKNOWN을 허용하므로 멱등 처리된다.

**Warning signs:** Toss 성공 후 `markPaymentAsDone()`에서 상태 전이 예외 발생.

### Pitfall 5: `fixedDelayString` vs `fixedRateString` 혼동

**What goes wrong:** `@Scheduled(fixedRateString = "...")` 사용 시 이전 실행이 완료되지 않아도 다음 실행이 트리거된다. 단일 인스턴스에서도 동일 레코드 중복 처리 가능성이 생긴다.

**How to avoid:** `@Scheduled(fixedDelayString = "${scheduler.outbox-worker.fixed-delay-ms:1000}")` 사용. 기존 `PaymentScheduler.recoverStuckPayments()`도 `fixedDelayString` 패턴을 사용한다.

---

## Code Examples

### 테이블 DDL (payment_outbox)

```sql
CREATE TABLE payment_outbox (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    order_id    VARCHAR(100) NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    retry_count INT          NOT NULL DEFAULT 0,
    in_flight_at DATETIME(6),
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_payment_outbox_order_id (order_id),
    INDEX idx_payment_outbox_status_created (status, created_at)  -- 워커 PENDING 배치 조회 최적화
);
```

**인덱스 설계 근거:**
- `uq_payment_outbox_order_id`: 동일 orderId로 중복 outbox 생성 방지 (confirm 중복 요청 방어)
- `idx_payment_outbox_status_created`: 워커의 `WHERE status = 'PENDING' ORDER BY created_at ASC LIMIT N` 쿼리 커버링 인덱스

### PaymentOutboxStatus enum

```java
// domain/enums/PaymentOutboxStatus.java
@Getter
@RequiredArgsConstructor
public enum PaymentOutboxStatus {
    PENDING("PENDING"),
    IN_FLIGHT("IN_FLIGHT"),
    DONE("DONE"),
    FAILED("FAILED");

    private final String value;
}
```

### PaymentOutboxRepository 포트

```java
// application/port/PaymentOutboxRepository.java
public interface PaymentOutboxRepository {
    PaymentOutbox save(PaymentOutbox paymentOutbox);
    Optional<PaymentOutbox> findByOrderId(String orderId);
    List<PaymentOutbox> findPendingBatch(int limit);               // FIFO, createdAt ASC
    List<PaymentOutbox> findTimedOutInFlight(LocalDateTime before); // in_flight_at < before
    boolean existsByOrderId(String orderId);
}
```

### PaymentOutboxUseCase — 핵심 메서드

```java
@Service
@RequiredArgsConstructor
public class PaymentOutboxUseCase {

    private final PaymentOutboxRepository paymentOutboxRepository;
    private final LocalDateTimeProvider localDateTimeProvider;

    @Transactional
    public PaymentOutbox createPendingRecord(String orderId) {
        PaymentOutbox outbox = PaymentOutbox.createPending(orderId);
        return paymentOutboxRepository.save(outbox);
    }

    // IN_FLIGHT 전환: 별도 트랜잭션, 즉시 커밋
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimToInFlight(Long outboxId) {
        // PENDING인 경우에만 전환 (낙관적 접근 또는 단순 findById 후 상태 확인)
        ...
    }

    @Transactional
    public void markDone(String orderId) {
        PaymentOutbox outbox = getByOrderId(orderId);
        if (outbox.getStatus() == PaymentOutboxStatus.DONE) {
            return; // 멱등 처리
        }
        outbox.toDone();
        paymentOutboxRepository.save(outbox);
    }

    @Transactional
    public void markFailed(String orderId, String reason) {
        PaymentOutbox outbox = getByOrderId(orderId);
        if (outbox.getStatus() == PaymentOutboxStatus.FAILED) {
            return; // 멱등 처리
        }
        outbox.toFailed();
        paymentOutboxRepository.save(outbox);
    }

    @Transactional
    public void incrementRetryOrFail(String orderId, String reason, PaymentOutbox outbox) {
        if (outbox.isRetryable()) {
            outbox.incrementRetryCount(); // status → PENDING으로 복귀
            paymentOutboxRepository.save(outbox);
        } else {
            markFailed(orderId, reason);
        }
    }

    @Transactional
    public void recoverTimedOutInFlightRecords(int timeoutMinutes) {
        LocalDateTime threshold = localDateTimeProvider.now().minusMinutes(timeoutMinutes);
        List<PaymentOutbox> timedOut = paymentOutboxRepository.findTimedOutInFlight(threshold);
        timedOut.forEach(o -> {
            o.incrementRetryCount(); // PENDING으로 복귀
            paymentOutboxRepository.save(o);
        });
    }

    public List<PaymentOutbox> findPendingBatch(int batchSize) {
        return paymentOutboxRepository.findPendingBatch(batchSize);
    }

    public Optional<PaymentOutboxStatus> findActiveOutboxStatus(String orderId) {
        return paymentOutboxRepository.findByOrderId(orderId)
                .map(PaymentOutbox::getStatus)
                .filter(s -> s == PaymentOutboxStatus.PENDING || s == PaymentOutboxStatus.IN_FLIGHT);
        // DONE/FAILED는 PaymentEvent fallback 사용
    }
}
```

### Virtual Thread 병렬 처리 패턴

```java
// Java 21: try-with-resources로 ExecutorService 자동 종료 (awaitTermination 불필요)
try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
    List<Future<?>> futures = records.stream()
            .map(record -> executor.submit(() -> processRecord(record)))
            .toList();
    // try 블록 종료 시 executor.close() → 모든 태스크 완료까지 블록
}
```

**주의:** `ExecutorService.close()`는 Java 19+에서 `AutoCloseable` 구현. `awaitTermination()` 별도 호출 불필요. (HIGH confidence — Java 21 공식 API)

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `Thread.sleep()` 기반 폴링 | `@Scheduled(fixedDelay)` | Spring 3.x | 설정 기반 스케줄링, 관리 용이 |
| 플랫폼 스레드 풀 | Java 21 Virtual Threads | JDK 21 (2023) | IO 블로킹 시 캐리어 스레드 비점유, TPS 향상 |
| `ExecutorService` 수동 종료 | try-with-resources + `close()` | Java 19 (AutoCloseable) | 코드 간결, 누수 방지 |

---

## Open Questions

1. **`executePayment()` 호출 시점 (PaymentEvent READY → IN_PROGRESS 전환)**
   - What we know: 기존 `PaymentConfirmServiceImpl`은 Toss API 전에 `executePayment()`를 호출해 IN_PROGRESS로 전환한다.
   - What's unclear: Outbox 어댑터에서 202 반환 시 PaymentEvent는 여전히 READY 상태다. 워커가 Toss API 전에 `executePayment()`를 호출해야 하는지, 아니면 워커 성공 시 바로 DONE으로 전환할 수 있는지.
   - Recommendation: `PaymentEvent.done()`이 `IN_PROGRESS`에서만 허용하는지 확인 필요. 도메인 메서드를 확인하거나, 워커에서 `executePayment()` 호출 후 Toss API 호출 순서로 구현하는 것이 안전하다.

2. **IN_FLIGHT 타임아웃 복구를 메인 워커에 포함할지 분리할지**
   - What we know: CONTEXT.md에서 Claude's Discretion으로 남겨둠.
   - Recommendation: 메인 워커(`OutboxWorker.process()`) 첫 단계에 포함한다. 복구 레코드 수가 적을 것이고, 별도 스케줄러를 추가하면 설정 복잡도가 증가한다.

---

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Mockito (Spring Boot 3.3.3 managed) |
| Config file | `build.gradle` (Gradle managed) |
| Quick run command | `./gradlew test --tests "*.OutboxWorker*" --tests "*.PaymentOutbox*" --tests "*.OutboxConfirmAdapter*"` |
| Full suite command | `./gradlew test` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| OUTBOX-01 | confirm 시 202 반환 + PENDING 저장 | unit | `./gradlew test --tests "*.OutboxConfirmAdapterTest"` | ❌ Wave 0 |
| OUTBOX-02 | 재고 감소가 202 반환 전 동기적으로 완료됨 | unit | `./gradlew test --tests "*.PaymentTransactionCoordinatorTest"` | ✅ (기존 파일에 신규 메서드 테스트 추가) |
| OUTBOX-03 | 워커가 PENDING 레코드 조회 후 Toss API 호출 | unit | `./gradlew test --tests "*.OutboxWorkerTest"` | ❌ Wave 0 |
| OUTBOX-04 | 처리 시작 시 IN_FLIGHT 전환 (중복 방지) | unit | `./gradlew test --tests "*.OutboxWorkerTest"` | ❌ Wave 0 |
| OUTBOX-05 | 5회 재시도 후 FAILED 처리 | unit (ParameterizedTest) | `./gradlew test --tests "*.PaymentOutboxTest"` | ❌ Wave 0 |
| OUTBOX-06 | 보상 트랜잭션 멱등 (FAILED 상태에서 중복 호출 안전) | unit | `./gradlew test --tests "*.PaymentOutboxUseCaseTest"` | ❌ Wave 0 |

### Sampling Rate
- **Per task commit:** `./gradlew test --tests "*.OutboxWorker*" --tests "*.PaymentOutbox*"`
- **Per wave merge:** `./gradlew test`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps
- [ ] `src/test/java/.../payment/infrastructure/adapter/OutboxConfirmAdapterTest.java` — covers OUTBOX-01
- [ ] `src/test/java/.../payment/scheduler/OutboxWorkerTest.java` — covers OUTBOX-03, OUTBOX-04
- [ ] `src/test/java/.../payment/domain/PaymentOutboxTest.java` — covers OUTBOX-05 (`@ParameterizedTest` 상태 전이)
- [ ] `src/test/java/.../payment/application/usecase/PaymentOutboxUseCaseTest.java` — covers OUTBOX-06

---

## Sources

### Primary (HIGH confidence)
- 코드베이스 직접 분석 — `PaymentTransactionCoordinator`, `SyncConfirmAdapter`, `PaymentScheduler`, `PaymentProcess`, `PaymentProcessRepositoryImpl` 전체 읽기
- `.planning/codebase/ARCHITECTURE.md`, `CONVENTIONS.md`, `TESTING.md`, `STACK.md` — 프로젝트 아키텍처 규칙
- `.planning/phases/03-db-outbox-adapter/03-CONTEXT.md` — Phase 3 설계 결정

### Secondary (MEDIUM confidence)
- Java 21 `ExecutorService.close()` (AutoCloseable) — JDK 21 공식 변경사항, 지식 기반

### Tertiary (LOW confidence)
- 없음

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — 기존 코드베이스에서 모든 패턴 직접 확인
- Architecture: HIGH — 기존 SyncConfirmAdapter, PaymentScheduler 패턴 그대로 적용
- Pitfalls: HIGH — 코드베이스 직접 분석으로 PaymentTransactionCoordinator 멱등 가드, recoverStuckPayments 쿼리 확인

**Research date:** 2026-03-15
**Valid until:** 2026-04-15 (안정적 스택, 30일)
