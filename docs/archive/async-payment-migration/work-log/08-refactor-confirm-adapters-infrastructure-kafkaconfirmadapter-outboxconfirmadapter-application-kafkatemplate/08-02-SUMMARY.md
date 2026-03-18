---
phase: 08-refactor-confirm-adapters-infrastructure-kafkaconfirmadapter-outboxconfirmadapter-application-kafkatemplate
plan: 02
subsystem: payments
tags: [kafka, hexagonal-architecture, conditional-on-property, tdd, application-service]

# Dependency graph
requires:
  - phase: 08-01
    provides: PaymentConfirmPublisherPort 인터페이스, KafkaAsyncConfirmServiceTest/OutboxAsyncConfirmServiceTest Wave 0 스텁
provides:
  - KafkaAsyncConfirmService (application 레이어 Kafka 전략 오케스트레이션 서비스)
  - OutboxAsyncConfirmService (application 레이어 Outbox 전략 오케스트레이션 서비스)
  - KafkaAsyncConfirmServiceTest 4개 GREEN
  - OutboxAsyncConfirmServiceTest 3개 GREEN
affects:
  - 08-03 (KafkaConfirmAdapter/OutboxConfirmAdapter 삭제 플랜 — 새 서비스가 완성되어 어댑터 삭제 가능)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - application 레이어에서 PaymentConfirmService 구현 (@ConditionalOnProperty 로 Bean 활성화 제어)
    - KafkaTemplate 직접 의존 없이 PaymentConfirmPublisherPort 포트를 통해 발행
    - TOPIC 상수는 KafkaConfirmPublisher에만 위치 (application 레이어에 Kafka 관심사 누수 방지)

key-files:
  created:
    - src/main/java/com/hyoguoo/paymentplatform/payment/application/KafkaAsyncConfirmService.java
    - src/main/java/com/hyoguoo/paymentplatform/payment/application/OutboxAsyncConfirmService.java
  modified: []

key-decisions:
  - "두 서비스를 단일 커밋으로 처리: 논리적으로 동일한 작업 단위(Wave 0 스텁 GREEN 달성)"

patterns-established:
  - "application 레이어 서비스가 PaymentConfirmService 구현 — infrastructure 어댑터 아님"
  - "오케스트레이션 로직은 application 레이어에서만 존재 (hexagonal 원칙 준수)"

requirements-completed: []

# Metrics
duration: 5min
completed: 2026-03-17
---

# Phase 08 Plan 02: KafkaAsyncConfirmService + OutboxAsyncConfirmService 생성 Summary

**KafkaConfirmAdapter/OutboxConfirmAdapter의 오케스트레이션 로직을 application 레이어 서비스로 이동하고 Wave 0 스텁 7개 GREEN 달성**

## Performance

- **Duration:** 5 min
- **Started:** 2026-03-16T23:30:36Z
- **Completed:** 2026-03-16T23:33:03Z
- **Tasks:** 2
- **Files modified:** 2

## Accomplishments

- `KafkaAsyncConfirmService`: `PaymentConfirmPublisherPort`를 통해 Kafka 발행하는 application 레이어 서비스, `@ConditionalOnProperty(havingValue="kafka")` 적용
- `OutboxAsyncConfirmService`: `executeStockDecreaseWithOutboxCreation()` 호출하는 application 레이어 서비스, `@ConditionalOnProperty(havingValue="outbox")` 적용
- Wave 0 테스트 스텁 7개(KafkaAsyncConfirmServiceTest 4개 + OutboxAsyncConfirmServiceTest 3개) GREEN 달성

## Task Commits

1. **Task 1 + Task 2: KafkaAsyncConfirmService + OutboxAsyncConfirmService 생성** - `a10349b` (feat)

_Note: 두 서비스가 동일한 Wave 0 스텁 GREEN 달성 목표를 공유하므로 단일 커밋으로 처리_

## Files Created/Modified

- `src/main/java/.../payment/application/KafkaAsyncConfirmService.java` - Kafka 전략 오케스트레이션 서비스 (application 레이어)
- `src/main/java/.../payment/application/OutboxAsyncConfirmService.java` - Outbox 전략 오케스트레이션 서비스 (application 레이어)

## Decisions Made

- 두 서비스를 단일 커밋으로 처리: 두 서비스 모두 Wave 0 스텁 GREEN 달성이라는 동일한 목적을 가지며, 테스트 코드는 이미 Plan 01에서 생성되었으므로 구현 코드 2개를 하나의 논리적 단위로 묶음

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

- `./gradlew test --tests "*.KafkaAsyncConfirmServiceTest"` 단독 실행 시 `OutboxAsyncConfirmServiceTest` 컴파일 오류로 실패 — 두 구현 파일을 동시에 생성하여 해결. 예상된 동작이며 Plan 01 SUMMARY에도 기록된 상황.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- `KafkaAsyncConfirmService`와 `OutboxAsyncConfirmService`가 application 레이어에 완성됨
- Plan 03에서 `KafkaConfirmAdapter`와 `OutboxConfirmAdapter` 삭제 가능 (동일 `havingValue` Bean 중복 해소)
- `./gradlew test --tests "*.payment.application.*"` 78개 전체 GREEN 확인

---
*Phase: 08-refactor-confirm-adapters*
*Completed: 2026-03-17*
