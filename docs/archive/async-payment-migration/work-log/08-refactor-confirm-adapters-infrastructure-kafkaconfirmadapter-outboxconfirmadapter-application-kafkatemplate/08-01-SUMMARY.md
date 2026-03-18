---
phase: 08-refactor-confirm-adapters-infrastructure-kafkaconfirmadapter-outboxconfirmadapter-application-kafkatemplate
plan: 01
subsystem: payments
tags: [kafka, hexagonal-architecture, port-abstraction, tdd]

# Dependency graph
requires: []
provides:
  - PaymentConfirmPublisherPort 인터페이스 (application/port/out/)
  - KafkaConfirmPublisher 구현체 (infrastructure/kafka/)
  - KafkaAsyncConfirmServiceTest Wave 0 스텁 (Plan 02에서 GREEN)
  - OutboxAsyncConfirmServiceTest Wave 0 스텁 (Plan 02에서 GREEN)
  - KafkaConfirmPublisherTest GREEN
affects:
  - 08-02 (KafkaAsyncConfirmService, OutboxAsyncConfirmService 생성 시 스텁 테스트 해소)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - application/port/out/ 서브패키지로 outbound 포트 분리
    - KafkaTemplate 직접 의존을 PaymentConfirmPublisherPort로 추상화
    - 테스트에서 FakePaymentConfirmPublisher inner class 패턴 (Fake > Mock 원칙)

key-files:
  created:
    - src/main/java/com/hyoguoo/paymentplatform/payment/application/port/out/PaymentConfirmPublisherPort.java
    - src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/kafka/KafkaConfirmPublisher.java
    - src/test/java/com/hyoguoo/paymentplatform/payment/application/KafkaAsyncConfirmServiceTest.java
    - src/test/java/com/hyoguoo/paymentplatform/payment/application/OutboxAsyncConfirmServiceTest.java
    - src/test/java/com/hyoguoo/paymentplatform/payment/infrastructure/kafka/KafkaConfirmPublisherTest.java
  modified:
    - .gitignore

key-decisions:
  - "application/port/out/ 서브패키지 사용: CONTEXT.md 명시 따라 out/ 서브패키지에 outbound 포트 배치"
  - ".gitignore out/ 규칙이 src/**/out/ 소스 디렉토리를 무시하는 문제 수정: !src/**/out/ 예외 규칙 추가"

patterns-established:
  - "FakePaymentConfirmPublisher를 테스트 파일 내 private static inner class로 구현 (Fake > Mock 원칙)"
  - "KafkaConfirmPublisher에서만 TOPIC 상수 관리 — application 레이어에 Kafka 관심사 누수 방지"

requirements-completed: []

# Metrics
duration: 15min
completed: 2026-03-16
---

# Phase 08 Plan 01: Kafka 발행 포트 추상화 및 Wave 0 테스트 스텁 생성 Summary

**PaymentConfirmPublisherPort 인터페이스 + KafkaConfirmPublisher 구현체 생성, Wave 0 테스트 스텁 3종으로 Phase 8 TDD 프레임 확립**

## Performance

- **Duration:** 15 min
- **Started:** 2026-03-16T23:14:00Z
- **Completed:** 2026-03-16T23:29:19Z
- **Tasks:** 2 (Task 1 + Task 2 단일 커밋)
- **Files modified:** 6

## Accomplishments

- `PaymentConfirmPublisherPort` 인터페이스를 `application/port/out/` 패키지에 생성하여 KafkaTemplate 직접 의존 추상화 확립
- `KafkaConfirmPublisher`가 `PaymentConfirmPublisherPort`를 구현하고 `payment-confirm` 토픽에 발행, `KafkaConfirmPublisherTest` GREEN 달성
- Wave 0 테스트 스텁 3종(`KafkaAsyncConfirmServiceTest`, `OutboxAsyncConfirmServiceTest`, `KafkaConfirmPublisherTest`) 생성 — Plan 02 구현을 위한 TDD 계약 확립

## Task Commits

1. **Task 1 + Task 2: 포트 인터페이스, 구현체, 테스트 스텁 3종** - `8cf7d42` (feat)

_Note: Task 1 (테스트 스텁)과 Task 2 (포트 + 구현체)를 단일 커밋으로 처리 — CLAUDE.md 컨벤션 "테스트 코드는 구현 코드와 같은 커밋에 포함"_

## Files Created/Modified

- `src/main/java/.../payment/application/port/out/PaymentConfirmPublisherPort.java` - KafkaTemplate 추상화 outbound 포트 인터페이스
- `src/main/java/.../payment/infrastructure/kafka/KafkaConfirmPublisher.java` - `payment-confirm` 토픽 발행 구현체
- `src/test/.../payment/application/KafkaAsyncConfirmServiceTest.java` - Wave 0 스텁 (Plan 02에서 GREEN)
- `src/test/.../payment/application/OutboxAsyncConfirmServiceTest.java` - Wave 0 스텁 (Plan 02에서 GREEN)
- `src/test/.../payment/infrastructure/kafka/KafkaConfirmPublisherTest.java` - GREEN
- `.gitignore` - `!src/**/out/` 예외 규칙 추가

## Decisions Made

- `application/port/out/` 서브패키지 사용: CONTEXT.md에 명시된 경로 적용. 기존 포트들은 `application/port/` 바로 아래에 있지만, outbound 포트는 `out/` 서브패키지로 분리하는 헥사고날 컨벤션 적용
- `.gitignore` `out/` 패턴이 `src/**/out/` 소스 디렉토리를 무시하는 문제 발견 → `!src/**/out/` 예외 규칙 추가로 해결

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] .gitignore out/ 패턴이 port/out/ 소스 디렉토리 무시**
- **Found during:** Task 2 (PaymentConfirmPublisherPort 커밋 시도)
- **Issue:** `.gitignore`의 `out/` 규칙이 `src/**/out/` 디렉토리에도 매칭되어 `PaymentConfirmPublisherPort.java`가 git에서 무시됨
- **Fix:** `.gitignore`에 `!src/**/out/` 예외 규칙 추가
- **Files modified:** `.gitignore`
- **Verification:** `git ls-files --others --exclude-standard`로 파일 노출 확인
- **Committed in:** `8cf7d42` (Task 1+2 커밋에 포함)

---

**Total deviations:** 1 auto-fixed (1 blocking)
**Impact on plan:** .gitignore 수정은 소스 파일 추적을 위한 필수 수정. 범위 이탈 없음.

## Issues Encountered

- `KafkaAsyncConfirmServiceTest`, `OutboxAsyncConfirmServiceTest`는 Plan 01 완료 시점에 컴파일 오류 상태 — 예상된 상태이며 Plan 02에서 `KafkaAsyncConfirmService`, `OutboxAsyncConfirmService` 생성 후 해소됨
- `./gradlew test`는 전체 테스트 소스 컴파일 실패로 현재 실행 불가 — Plan 02 완료 후 통과 예정

## Self-Check

- `PaymentConfirmPublisherPort.java` — FOUND
- `KafkaConfirmPublisher.java` — FOUND
- `KafkaAsyncConfirmServiceTest.java` — FOUND
- `OutboxAsyncConfirmServiceTest.java` — FOUND
- `KafkaConfirmPublisherTest.java` — FOUND
- commit `8cf7d42` — FOUND

## Self-Check: PASSED

## Next Phase Readiness

- Plan 02에서 `KafkaAsyncConfirmService`, `OutboxAsyncConfirmService` 생성 후 Wave 0 스텁 3종 모두 GREEN 달성 가능
- `PaymentConfirmPublisherPort`가 계약으로 확립되어 Plan 02 구현 의존성 해소됨

---
*Phase: 08-refactor-confirm-adapters*
*Completed: 2026-03-16*
