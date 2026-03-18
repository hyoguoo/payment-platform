---
phase: 03-db-outbox-adapter
plan: "01"
subsystem: payment
tags: [outbox, domain, jpa, adapter, tdd]
dependency_graph:
  requires: []
  provides:
    - PaymentOutbox 도메인 엔티티 (PENDING/IN_FLIGHT/DONE/FAILED 상태 머신)
    - PaymentOutboxRepository 포트 인터페이스
    - PaymentOutboxEntity JPA 엔티티 (payment_outbox 테이블)
    - PaymentOutboxRepositoryImpl
    - PaymentOutboxUseCase 스텁 (Plan 02에서 완성)
    - OutboxConfirmAdapter (@ConditionalOnProperty havingValue=outbox)
    - PaymentTransactionCoordinator.executeStockDecreaseWithOutboxCreation()
  affects:
    - PaymentConfirmService 포트 (throws PaymentOrderedProductStockException 추가)
    - PaymentController (throws 선언 추가)
    - PaymentTransactionCoordinator (PaymentOutboxUseCase 의존성 추가)
    - application.yml (outbox-worker 설정 추가)
tech_stack:
  added: []
  patterns:
    - TDD (RED → GREEN 두 사이클)
    - Hexagonal Architecture (Domain → Port → Infrastructure → Adapter)
    - @ConditionalOnProperty 전략 패턴 (SyncConfirmAdapter와 동일)
    - allArgsBuilder/build 커스텀 빌더 패턴
key_files:
  created:
    - src/main/java/com/hyoguoo/paymentplatform/payment/domain/enums/PaymentOutboxStatus.java
    - src/main/java/com/hyoguoo/paymentplatform/payment/domain/PaymentOutbox.java
    - src/main/java/com/hyoguoo/paymentplatform/payment/application/port/PaymentOutboxRepository.java
    - src/main/java/com/hyoguoo/paymentplatform/payment/application/usecase/PaymentOutboxUseCase.java
    - src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/entity/PaymentOutboxEntity.java
    - src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/repository/JpaPaymentOutboxRepository.java
    - src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/repository/PaymentOutboxRepositoryImpl.java
    - src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/adapter/OutboxConfirmAdapter.java
    - src/test/java/com/hyoguoo/paymentplatform/payment/domain/PaymentOutboxTest.java
    - src/test/java/com/hyoguoo/paymentplatform/payment/infrastructure/adapter/OutboxConfirmAdapterTest.java
  modified:
    - src/main/java/com/hyoguoo/paymentplatform/payment/exception/common/PaymentErrorCode.java
    - src/main/java/com/hyoguoo/paymentplatform/payment/application/usecase/PaymentTransactionCoordinator.java
    - src/main/java/com/hyoguoo/paymentplatform/payment/presentation/port/PaymentConfirmService.java
    - src/main/java/com/hyoguoo/paymentplatform/payment/presentation/PaymentController.java
    - src/main/resources/application.yml
    - src/test/java/com/hyoguoo/paymentplatform/payment/application/usecase/PaymentTransactionCoordinatorTest.java
decisions:
  - "PaymentConfirmService 포트에 throws PaymentOrderedProductStockException 선언: OutboxConfirmAdapter의 confirm()이 재고 부족 시 checked exception을 전파해야 하므로 포트 인터페이스에 선언, PaymentExceptionHandler가 이를 처리함"
  - "Outbox storage: 전용 payment_outbox 테이블 사용 (PaymentProcess 재사용 없음) — Race condition 방지를 위해 독립 테이블 선택"
metrics:
  duration: 6m
  completed_date: "2026-03-15"
  tasks_completed: 2
  files_created: 10
  files_modified: 6
---

# Phase 3 Plan 01: Outbox 도메인 계층과 어댑터 구축 Summary

**One-liner:** PaymentOutbox 도메인 엔티티(PENDING/IN_FLIGHT/DONE/FAILED 상태 머신) + JPA 인프라 + OutboxConfirmAdapter(@ConditionalOnProperty havingValue=outbox, ASYNC_202 반환)를 TDD로 구현

## Tasks Completed

| # | Task | Commit | Result |
|---|------|--------|--------|
| 1 | PaymentOutbox 도메인 엔티티 + 포트 인터페이스 (RED→GREEN) | c7cb945 | 10 tests GREEN |
| 2 | JPA 인프라 + OutboxConfirmAdapter + executeStockDecreaseWithOutboxCreation() 스텁 (RED→GREEN) | e56b71d | 4 tests GREEN |

## What Was Built

### Task 1: PaymentOutbox 도메인 엔티티

- **PaymentOutboxStatus**: PENDING, IN_FLIGHT, DONE, FAILED 상태 정의
- **PaymentOutbox** 도메인 엔티티:
  - `createPending(orderId)`: PENDING 상태, retryCount=0, inFlightAt=null
  - `toInFlight(inFlightAt)`: PENDING에서만 전환 가능, 그 외 PaymentStatusException
  - `toDone()`, `toFailed()`: 터미널 상태 전환
  - `isRetryable()`: retryCount < RETRYABLE_LIMIT(5)
  - `incrementRetryCount()`: retryCount++, 상태를 PENDING으로 복귀
- **PaymentOutboxRepository** 포트: save, findByOrderId, findPendingBatch, findTimedOutInFlight, existsByOrderId
- **PaymentErrorCode** 추가: INVALID_STATUS_TO_IN_FLIGHT(E03022), PAYMENT_OUTBOX_NOT_FOUND(E03023)

### Task 2: JPA 인프라 + 어댑터

- **PaymentOutboxEntity**: payment_outbox 테이블, status+created_at 복합 인덱스
- **JpaPaymentOutboxRepository**: JPQL로 findPendingBatch(FIFO), findTimedOutInFlight 정의
- **PaymentOutboxRepositoryImpl**: PaymentOutboxRepository 구현체
- **PaymentOutboxUseCase 스텁**: createPendingRecord() — Plan 02에서 완성 예정
- **PaymentTransactionCoordinator**: executeStockDecreaseWithOutboxCreation() 추가, PaymentOutboxUseCase 의존성 주입
- **OutboxConfirmAdapter**: @ConditionalOnProperty(havingValue=outbox), confirm()에서 executePayment() 먼저 호출 후 executeStockDecreaseWithOutboxCreation()
- **application.yml**: outbox-worker 스케줄러 기본 설정 추가

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical Functionality] PaymentConfirmService 포트에 throws 선언 추가**

- **Found during:** Task 2
- **Issue:** `OutboxConfirmAdapter.confirm()`이 `PaymentOrderedProductStockException`(checked exception)을 전파해야 하는데, 포트 인터페이스에 선언이 없어 컴파일 오류 발생
- **Fix:** `PaymentConfirmService.confirm()` 인터페이스에 `throws PaymentOrderedProductStockException` 추가, `PaymentController.confirm()`에도 동일하게 선언. `PaymentExceptionHandler`가 이미 이 예외를 처리하므로 런타임 동작에 영향 없음
- **Files modified:** `PaymentConfirmService.java`, `PaymentController.java`
- **Commit:** e56b71d

## Test Results

| Test Class | Tests | Pass | Fail |
|------------|-------|------|------|
| PaymentOutboxTest | 10 | 10 | 0 |
| OutboxConfirmAdapterTest | 4 | 4 | 0 |
| PaymentTransactionCoordinatorTest (신규 nested class) | 컴파일 OK | Plan 02 verify에서 실행 | - |
| 전체 단위 테스트 | 231+ | 231+ | 0 |

**참고:** PaymentControllerTest, PaymentSchedulerTest, PaymentGatewayServiceImplErrorCaseTest 3개는 Docker 환경 미설치로 인한 기존 실패 (이번 플랜과 무관)

## Self-Check: PASSED

- PaymentOutboxStatus.java: FOUND
- PaymentOutbox.java: FOUND
- PaymentOutboxRepository.java: FOUND
- OutboxConfirmAdapter.java: FOUND
- PaymentTransactionCoordinator.java (updated): FOUND
- Commit c7cb945: FOUND
- Commit e56b71d: FOUND
