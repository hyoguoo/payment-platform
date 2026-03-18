---
phase: 04-kafka-adapter
plan: 02
subsystem: payments
tags: [kafka, spring-kafka, conditional-bean, tdd, transaction-coordinator]

# Dependency graph
requires:
  - phase: 04-kafka-adapter-01
    provides: "KafkaConfirmAdapterTest 스텁, KafkaConfirmListenerTest 스텁, spring-kafka 의존성, BaseKafkaIntegrationTest"
  - phase: 03-db-outbox-adapter
    provides: "PaymentTransactionCoordinator(executeStockDecreaseWithOutboxCreation), PaymentOutboxUseCase, PaymentConfirmService 포트"
provides:
  - "PaymentTransactionCoordinator.executeStockDecreaseOnly() — Outbox 생성 없이 재고 감소만 수행"
  - "KafkaConfirmAdapter — @ConditionalOnProperty(kafka) PaymentConfirmService 구현체, ASYNC_202 반환"
  - "KafkaConfirmListener 컴파일 스텁 — Plan 03에서 완성 예정"
affects:
  - 04-kafka-adapter-03
  - 04-kafka-adapter-04

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "executeStockDecreaseOnly(): Kafka 어댑터 전용 트랜잭션 메서드 — Outbox 없이 재고 감소만"
    - "kafkaTemplate.send() 호출을 @Transactional 메서드 외부에서 실행하여 트랜잭션 커밋 이후 발행 보장"
    - "KafkaException 별도 catch 없이 전파 — GlobalExceptionHandler 위임"

key-files:
  created:
    - src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/adapter/KafkaConfirmAdapter.java
    - src/main/java/com/hyoguoo/paymentplatform/payment/listener/KafkaConfirmListener.java
  modified:
    - src/main/java/com/hyoguoo/paymentplatform/payment/application/usecase/PaymentTransactionCoordinator.java
    - src/test/java/com/hyoguoo/paymentplatform/payment/application/usecase/PaymentTransactionCoordinatorTest.java
    - src/test/java/com/hyoguoo/paymentplatform/payment/infrastructure/adapter/KafkaConfirmAdapterTest.java

key-decisions:
  - "kafkaTemplate.send()는 executeStockDecreaseOnly() 리턴 이후(트랜잭션 커밋 이후) 호출: 소비자 타이밍 레이스 방지"
  - "executeStockDecreaseOnly() 시그니처에 orderId 포함(현재 미사용): 확장성 유지 목적"

patterns-established:
  - "Kafka 발행은 @Transactional 경계 밖에서: 트랜잭션 성공 후 발행 보장"
  - "KafkaConfirmAdapter: getPaymentEventByOrderId → executePayment → executeStockDecreaseOnly → kafkaTemplate.send → ASYNC_202"

requirements-completed: [KAFKA-02, KAFKA-03]

# Metrics
duration: 4min
completed: 2026-03-15
---

# Phase 4 Plan 02: KafkaConfirmAdapter Summary

**KafkaConfirmAdapter 구현 완료: executeStockDecreaseOnly()로 재고 감소 후 kafkaTemplate.send()로 비동기 발행, ASYNC_202 반환**

## Performance

- **Duration:** 4 min
- **Started:** 2026-03-15T11:22:11Z
- **Completed:** 2026-03-15T11:26:28Z
- **Tasks:** 2
- **Files modified:** 5

## Accomplishments

- `PaymentTransactionCoordinator.executeStockDecreaseOnly()` 추가: Outbox 레코드 생성 없이 재고 감소만 수행하는 Kafka 어댑터 전용 트랜잭션 메서드
- `KafkaConfirmAdapter` 구현: `@ConditionalOnProperty(havingValue="kafka")` 빈, confirm() 순서(executePayment → executeStockDecreaseOnly → kafkaTemplate.send → ASYNC_202) 완성
- `KafkaConfirmAdapterTest` 5개 테스트 모두 GREEN (executePayment 1회, executeStockDecreaseOnly 1회, kafkaTemplate.send 1회, @ConditionalOnProperty 검증, ASYNC_202 반환 검증)

## Task Commits

Each task was committed atomically:

1. **Task 1: PaymentTransactionCoordinator.executeStockDecreaseOnly() 추가 + 테스트 GREEN** - `ec093a8` (feat)
2. **Task 2: KafkaConfirmAdapter 구현 + KafkaConfirmAdapterTest GREEN** - `613ee6c` (feat)

## Files Created/Modified

- `src/main/java/.../payment/application/usecase/PaymentTransactionCoordinator.java` - `executeStockDecreaseOnly()` 메서드 추가
- `src/main/java/.../payment/infrastructure/adapter/KafkaConfirmAdapter.java` - Kafka 전략 PaymentConfirmService 구현체 신규 생성
- `src/main/java/.../payment/listener/KafkaConfirmListener.java` - Plan 03용 컴파일 스텁 신규 생성
- `src/test/.../payment/application/usecase/PaymentTransactionCoordinatorTest.java` - ExecuteStockDecreaseOnlyTest 2개 추가
- `src/test/.../payment/infrastructure/adapter/KafkaConfirmAdapterTest.java` - 스텁에서 완성 (executeStockDecreaseOnly 검증으로 교체, kafkaTemplate.send 검증 추가)

## Decisions Made

- `kafkaTemplate.send()`를 `executeStockDecreaseOnly()` 리턴 이후(트랜잭션 커밋 이후)에 호출: DB 트랜잭션이 커밋되기 전에 Kafka 소비자가 메시지를 처리하는 타이밍 레이스 방지
- `executeStockDecreaseOnly()` 시그니처에 `orderId` 파라미터 포함(현재 메서드 내에서 미사용): 향후 확장성 및 로깅 목적 유지
- `KafkaConfirmListener` 최소 스텁 생성(Rule 3): `KafkaConfirmListenerTest`가 Plan 01에서 생성되었으나 main 클래스 없이 컴파일 실패 — 최소 스텁으로 unblock

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] KafkaConfirmListener 컴파일 스텁 생성**
- **Found during:** Task 1 (PaymentTransactionCoordinatorTest 실행 시도)
- **Issue:** Plan 01에서 `KafkaConfirmListenerTest.java` 스텁이 생성되었으나 `KafkaConfirmListener` main 클래스가 없어 컴파일 실패. `PaymentTransactionCoordinatorTest` 단독 실행도 전체 컴파일 실패로 불가능
- **Fix:** `KafkaConfirmListener.java` 최소 컴파일 스텁 생성 (`consume()`, `handleDlt()` 메서드 시그니처 포함, 본문은 `UnsupportedOperationException` — Plan 03에서 완성 예정)
- **Files modified:** `src/main/java/.../payment/listener/KafkaConfirmListener.java` (신규)
- **Verification:** `PaymentTransactionCoordinatorTest` 및 `KafkaConfirmAdapterTest` 컴파일 성공, 테스트 GREEN
- **Committed in:** `613ee6c` (Task 2 commit에 포함)

---

**Total deviations:** 1 auto-fixed (1 blocking)
**Impact on plan:** 필수 수정 — Plan 01에서 생성된 테스트 스텁과 main 클래스의 불일치 해소. 스코프 크리프 없음.

## Issues Encountered

- `KafkaConfirmListenerTest` 2개 테스트(consume_CallsConfirmPaymentWithGateway_Once, handleDlt_CallsFailureCompensation_Once)는 Plan 03 구현 전까지 예상된 RED 상태 유지
- Docker 미사용 환경으로 인한 통합 테스트 3개 실패(PaymentControllerTest, PaymentSchedulerTest, PaymentGatewayServiceImplErrorCaseTest)는 기존 환경 제약 — 이번 플랜 무관

## Next Phase Readiness

- Plan 03: `KafkaConfirmListener.java` 스텁 존재, `consume()` + `handleDlt()` 구현 및 `@RetryableTopic` 설정 필요
- Plan 04: 통합 테스트 대상 `KafkaConfirmAdapter`/`KafkaConfirmListener` 모두 준비됨

---
*Phase: 04-kafka-adapter*
*Completed: 2026-03-15*
