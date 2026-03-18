---
phase: 03-db-outbox-adapter
plan: "02"
subsystem: payment
tags: [outbox, scheduler, tdd, async, virtual-threads, transaction]
dependency_graph:
  requires:
    - phase: 03-db-outbox-adapter-01
      provides: PaymentOutbox 도메인 엔티티, PaymentOutboxRepository 포트, PaymentOutboxUseCase 스텁
  provides:
    - PaymentOutboxUseCase 완전 구현 (createPendingRecord, claimToInFlight, markDone, markFailed, incrementRetryOrFail, recoverTimedOutInFlightRecords, findPendingBatch, findActiveOutboxStatus)
    - OutboxWorker (@Scheduled fixedDelayString, 3단계 트랜잭션 분리, 재시도/실패 처리, 가상 스레드 병렬 모드)
  affects:
    - PaymentTransactionCoordinator (executeStockDecreaseWithOutboxCreation 동작 완성)
    - Phase 4 Kafka 어댑터 (동일한 PaymentOutboxUseCase 인터페이스 재사용 가능)
tech_stack:
  added: []
  patterns:
    - TDD (RED → GREEN 두 사이클 per task)
    - REQUIRES_NEW 트랜잭션으로 클레임 원자성 보장
    - Java 21 가상 스레드 try-with-resources 병렬 처리 패턴
    - 멱등 상태 전이 (markDone/markFailed 중복 호출 안전)
key_files:
  created:
    - src/main/java/com/hyoguoo/paymentplatform/payment/scheduler/OutboxWorker.java
    - src/test/java/com/hyoguoo/paymentplatform/payment/application/usecase/PaymentOutboxUseCaseTest.java
    - src/test/java/com/hyoguoo/paymentplatform/payment/scheduler/OutboxWorkerTest.java
  modified:
    - src/main/java/com/hyoguoo/paymentplatform/payment/application/usecase/PaymentOutboxUseCase.java
key_decisions:
  - "claimToInFlight()는 PaymentOutbox 객체를 파라미터로 받음 (findPendingBatch로 이미 로드됨) — orderId 기반 findByOrderId 중복 조회 불필요"
  - "incrementRetryOrFail()에서 retryCount 한도 초과 시 markFailed() 위임 — 단일 책임 원칙 유지"
  - "OutboxWorkerTest의 checked exception 메서드 선언: confirmPaymentWithGateway가 checked exception 선언하므로 테스트 메서드에 throws Exception 추가"
patterns_established:
  - "Outbox 워커 3단계 트랜잭션: claimToInFlight(REQUIRES_NEW) → HTTP 호출(트랜잭션 밖) → 결과 저장(별도 트랜잭션)"
  - "멱등 상태 전이: 이미 해당 상태이면 save() 없이 즉시 return"
requirements_completed:
  - OUTBOX-03
  - OUTBOX-04
  - OUTBOX-05
  - OUTBOX-06
duration: 6min
completed: "2026-03-15"
---

# Phase 3 Plan 02: OutboxUseCase와 OutboxWorker 구현 Summary

**PaymentOutboxUseCase 완전 구현(멱등 CRUD + REQUIRES_NEW 클레임) + OutboxWorker(@Scheduled 3단계 트랜잭션 분리, 재시도/실패 처리, Java 21 가상 스레드 병렬 모드)**

## Performance

- **Duration:** 6 min
- **Started:** 2026-03-15T02:23:15Z
- **Completed:** 2026-03-15T02:29:00Z
- **Tasks:** 2
- **Files modified:** 4

## Accomplishments

- Plan 01 스텁(UnsupportedOperationException) 삭제, PaymentOutboxUseCase 8개 메서드 완전 구현
- claimToInFlight() REQUIRES_NEW 트랜잭션으로 즉시 커밋 — 동일 레코드 중복 처리 방지
- OutboxWorker 구현: 타임아웃 복구 → PENDING 배치 조회 → IN_FLIGHT 클레임 → Toss API → 성공/실패 분기
- Java 21 가상 스레드 try-with-resources 병렬 모드 (parallelEnabled=true 시 활성화)
- PaymentOutboxUseCaseTest 11개 + OutboxWorkerTest 6개 GREEN, 전체 248개 기존 테스트 회귀 없음

## Task Commits

Each task was committed atomically:

1. **Task 1: PaymentOutboxUseCase 완전 구현 (TDD)** - `204f257` (feat)
2. **Task 2: OutboxWorker 구현 (TDD)** - `e3eebce` (feat)

_Note: TDD tasks — RED(컴파일 실패 확인) → GREEN(구현) 사이클 per task_

## Files Created/Modified

- `src/main/java/.../payment/application/usecase/PaymentOutboxUseCase.java` - 스텁 삭제, 8개 메서드 완전 구현
- `src/main/java/.../payment/scheduler/OutboxWorker.java` - @Scheduled 3단계 트랜잭션 Outbox 워커
- `src/test/.../payment/application/usecase/PaymentOutboxUseCaseTest.java` - 11개 케이스 (CRUD + 멱등 + 재시도)
- `src/test/.../payment/scheduler/OutboxWorkerTest.java` - 6개 케이스 (정상/클레임 실패/예외 분기)

## Decisions Made

- `claimToInFlight()`은 PaymentOutbox 객체를 파라미터로 받음 — findPendingBatch로 이미 로드된 객체를 그대로 사용, 중복 DB 조회 불필요
- `incrementRetryOrFail()`에서 retryCount 한도 초과 시 `markFailed()` 위임 — `markFailed()`는 findByOrderId로 최신 레코드를 조회하므로 일관성 유지
- OutboxWorkerTest 테스트 메서드에 `throws Exception` 추가 — `confirmPaymentWithGateway`가 checked exception 선언하여 mock 설정 시 컴파일러가 예외 처리 요구

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

- `PaymentTossRetryableException`/`PaymentTossNonRetryableException`이 `extends Exception` (checked exception)임을 확인 후, OutboxWorkerTest 메서드에 `throws Exception` 추가하여 컴파일 오류 해결. 구현 로직 변경 없음.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- OUTBOX-03/04/05/06 완료: OutboxWorker 스케줄러 기반 비동기 결제 처리 전체 구현 완료
- Phase 3 DB Outbox Adapter 완성 — Phase 4 Kafka 어댑터에서 동일한 PaymentOutboxUseCase 인터페이스 재사용 가능
- 전체 테스트 GREEN (Docker 미설치 환경의 3개 통합 테스트 제외 — 기존 실패)

## Self-Check: PASSED

- PaymentOutboxUseCase.java: FOUND
- OutboxWorker.java: FOUND
- PaymentOutboxUseCaseTest.java: FOUND
- OutboxWorkerTest.java: FOUND
- 03-02-SUMMARY.md: FOUND
- Commit 204f257: FOUND
- Commit e3eebce: FOUND

---
*Phase: 03-db-outbox-adapter*
*Completed: 2026-03-15*
