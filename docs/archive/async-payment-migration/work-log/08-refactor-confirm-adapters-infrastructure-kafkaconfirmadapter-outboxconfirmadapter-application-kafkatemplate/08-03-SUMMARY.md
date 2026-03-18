---
phase: 08-refactor-confirm-adapters-infrastructure-kafkaconfirmadapter-outboxconfirmadapter-application-kafkatemplate
plan: 03
subsystem: payments
tags: [refactor, hexagonal-architecture, conditional-on-property, tdd, application-service]

# Dependency graph
requires:
  - phase: 08-02
    provides: KafkaAsyncConfirmService, OutboxAsyncConfirmService (application 레이어 완성)
provides:
  - PaymentConfirmServiceImpl이 PaymentConfirmService를 직접 implements (infrastructure 어댑터 제거)
  - infrastructure/adapter/ 패키지 완전 제거 (6개 파일 삭제)
  - application/ 패키지에 3개 전략 구현체 완비 (KafkaAsyncConfirmService, OutboxAsyncConfirmService, PaymentConfirmServiceImpl)
affects: []

# Tech tracking
tech-stack:
  added: []
  patterns:
    - PaymentConfirmServiceImpl이 @ConditionalOnProperty(sync, matchIfMissing=true)로 PaymentConfirmService 직접 구현
    - infrastructure 레이어에 PaymentConfirmService 구현체 없음 — hexagonal 레이어 역전 해소 완료
    - doConfirm() private 메서드로 기존 로직 캡슐화, confirm()은 SYNC_200 래핑만 담당

key-files:
  created: []
  modified:
    - src/main/java/com/hyoguoo/paymentplatform/payment/application/PaymentConfirmServiceImpl.java
    - src/test/java/com/hyoguoo/paymentplatform/payment/application/PaymentConfirmServiceImplTest.java
  deleted:
    - src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/adapter/KafkaConfirmAdapter.java
    - src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/adapter/OutboxConfirmAdapter.java
    - src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/adapter/SyncConfirmAdapter.java
    - src/test/java/com/hyoguoo/paymentplatform/payment/infrastructure/adapter/KafkaConfirmAdapterTest.java
    - src/test/java/com/hyoguoo/paymentplatform/payment/infrastructure/adapter/OutboxConfirmAdapterTest.java
    - src/test/java/com/hyoguoo/paymentplatform/payment/infrastructure/adapter/SyncConfirmAdapterTest.java

key-decisions:
  - "어댑터 삭제를 Task 1 컴파일 블로커 해결 과정에서 선행 처리: SyncConfirmAdapter가 PaymentConfirmServiceImpl.confirm()의 반환 타입 변경으로 즉시 컴파일 오류 발생"

patterns-established:
  - "application 레이어 3개 전략 구현체가 @ConditionalOnProperty로 Bean 활성화 제어 — sync/outbox/kafka 하나만 활성"
  - "PaymentConfirmResult는 application 레이어 내부 DTO로 유지, PaymentConfirmAsyncResult가 포트 인터페이스 반환 타입"

requirements-completed: []

# Metrics
duration: 3min
completed: 2026-03-17
---

# Phase 08 Plan 03: SyncConfirmAdapter 제거 + PaymentConfirmServiceImpl 리팩터 Summary

**SyncConfirmAdapter를 제거하고 PaymentConfirmServiceImpl이 PaymentConfirmService를 직접 implements하도록 변경하여 infrastructure 레이어 역전 완전 해소**

## Performance

- **Duration:** 3 min
- **Started:** 2026-03-16T23:35:00Z
- **Completed:** 2026-03-16T23:38:07Z
- **Tasks:** 2
- **Files modified:** 8 (2 수정, 6 삭제)

## Accomplishments

- `PaymentConfirmServiceImpl`: `PaymentConfirmService` 직접 구현, `@ConditionalOnProperty(sync, matchIfMissing=true)` 추가, confirm() 반환 타입 `PaymentConfirmAsyncResult(SYNC_200)` 변경
- `PaymentConfirmServiceImplTest`: 반환 타입 변경 반영 + `@ConditionalOnProperty` 어노테이션 검증 테스트 신규 추가 (6개 GREEN)
- `infrastructure/adapter/` 패키지 완전 제거: KafkaConfirmAdapter, OutboxConfirmAdapter, SyncConfirmAdapter + 테스트 3종 삭제
- 전체 테스트 스위트 263개 GREEN 달성 (KafkaConfirmListenerIntegrationTest 포함)
- `application/` 패키지에 3개 전략 구현체 완비: sync/outbox/kafka 전략별 Bean 하나씩 활성화

## Task Commits

1. **Task 1: PaymentConfirmServiceImpl 리팩터 + 테스트 업데이트** — `5faecab` (refactor)
2. **Task 2: 어댑터 3종 + 테스트 삭제** — `dd944f9` (refactor)

## Files Created/Modified

- `src/main/java/.../payment/application/PaymentConfirmServiceImpl.java` — PaymentConfirmService implements 추가, confirm() 반환 타입 변경, doConfirm() 추출
- `src/test/java/.../payment/application/PaymentConfirmServiceImplTest.java` — 반환 타입 수정, @ConditionalOnProperty 검증 테스트 추가

## Files Deleted

- `src/main/java/.../payment/infrastructure/adapter/KafkaConfirmAdapter.java`
- `src/main/java/.../payment/infrastructure/adapter/OutboxConfirmAdapter.java`
- `src/main/java/.../payment/infrastructure/adapter/SyncConfirmAdapter.java`
- `src/test/java/.../payment/infrastructure/adapter/KafkaConfirmAdapterTest.java`
- `src/test/java/.../payment/infrastructure/adapter/OutboxConfirmAdapterTest.java`
- `src/test/java/.../payment/infrastructure/adapter/SyncConfirmAdapterTest.java`

## Decisions Made

- 어댑터 삭제를 Task 1 컴파일 블로커 해결 과정에서 선행 처리: `SyncConfirmAdapter`가 `PaymentConfirmServiceImpl.confirm()`의 반환 타입 변경으로 즉시 컴파일 오류 발생. Task 1과 Task 2를 순차적으로 커밋하되 삭제 작업을 앞당겨 처리.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] SyncConfirmAdapter 컴파일 오류로 Task 1 검증 선행 차단**
- **Found during:** Task 1 (GREEN phase)
- **Issue:** `SyncConfirmAdapter`가 `paymentConfirmServiceImpl.confirm()`을 호출하며 반환 타입이 `PaymentConfirmResult`임을 기대했으나, Task 1 변경으로 `PaymentConfirmAsyncResult`가 됨 → 컴파일 오류
- **Fix:** Task 2에서 예정된 어댑터 삭제 작업을 Task 1 GREEN 단계에서 선행 처리. 모든 어댑터 파일(6개)을 삭제 후 Task 1 테스트 검증 완료, Task 2로 커밋 분리
- **Files modified:** 어댑터 6개 파일 삭제
- **Commits:** `5faecab` (Task 1), `dd944f9` (Task 2)

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- Phase 08 완료: infrastructure/adapter/ 패키지 제거, application 레이어 3개 전략 구현체 완비
- `spring.payment.async-strategy` 설정값에 따라 sync/outbox/kafka 전략 Bean 하나만 활성화
- 전체 테스트 스위트 263개 GREEN

---
*Phase: 08-refactor-confirm-adapters*
*Completed: 2026-03-17*
