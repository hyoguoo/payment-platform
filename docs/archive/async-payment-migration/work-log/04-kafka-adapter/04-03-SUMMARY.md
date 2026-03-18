---
phase: 04-kafka-adapter
plan: 03
subsystem: payments
tags: [kafka, spring-kafka, retryable-topic, dlt, consumer, non-blocking-retry]

# Dependency graph
requires:
  - phase: 04-kafka-adapter
    plan: 02
    provides: KafkaConfirmAdapter 프로듀서 스텁 + KafkaConfirmListener 컴파일 스텁
provides:
  - KafkaConfirmListener @RetryableTopic(attempts="6") + @DltHandler 완성 구현
  - KAFKA-04(컨슈머 처리), KAFKA-05(Toss 멱등키 위임), KAFKA-06(DLT 라우팅) 검증
  - KafkaConfirmAdapter.TOPIC "payment-confirm" (DLT 토픽 "payment-confirm-dlq" 달성)
affects: [integration-tests, k6-benchmark, phase-05]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "@RetryableTopic 선언적 비동기 재시도: attempts, dltTopicSuffix, include/exclude로 라우팅 분기"
    - "NonRetryable 예외는 consume() 내부에서 catch → 즉시 보상, re-throw 없음"
    - "Retryable 예외는 re-throw → @RetryableTopic이 retry 토픽 라우팅 담당"
    - "@DltHandler handleDlt(): 재시도 소진 시 executePaymentFailureCompensation() 호출"
    - "consume()에 @Transactional 없음 — transactionCoordinator가 트랜잭션 경계 담당"

key-files:
  created:
    - src/main/java/com/hyoguoo/paymentplatform/payment/listener/KafkaConfirmListener.java
    - src/test/java/com/hyoguoo/paymentplatform/payment/listener/KafkaConfirmListenerTest.java
  modified:
    - src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/adapter/KafkaConfirmAdapter.java
    - src/test/java/com/hyoguoo/paymentplatform/payment/infrastructure/adapter/KafkaConfirmAdapterTest.java

key-decisions:
  - "KafkaConfirmListener.consume()는 checked PaymentTossRetryableException을 throws 선언으로 re-throw — @RetryableTopic include 설정으로 retry 토픽 라우팅"
  - "KafkaConfirmAdapter.TOPIC = payment-confirm (기존 payment-confirm-requests에서 변경) — DLT 토픽명 payment-confirm-dlq로 KAFKA-06 충족"
  - "NonRetryable 예외는 DLT 라우팅 불필요 — consume() 내부에서 직접 보상 트랜잭션 실행"

patterns-established:
  - "Kafka 컨슈머 예외 분기: NonRetryable→catch+보상, Retryable→re-throw(@RetryableTopic 위임)"
  - "DLT 핸들러는 orderId만 받아 paymentLoadUseCase.getPaymentEventByOrderId() 재조회 후 보상"

requirements-completed: [KAFKA-04, KAFKA-05, KAFKA-06]

# Metrics
duration: 8min
completed: 2026-03-15
---

# Phase 4 Plan 03: KafkaConfirmListener Summary

**@RetryableTopic(attempts="6") + @DltHandler로 Kafka 비동기 결제 컨슈머 구현 — NonRetryable 즉시 보상, Retryable re-throw 분기, DLT 소진 시 보상 트랜잭션**

## Performance

- **Duration:** 8 min
- **Started:** 2026-03-15T11:30:00Z
- **Completed:** 2026-03-15T11:38:00Z
- **Tasks:** 1
- **Files modified:** 4

## Accomplishments
- KafkaConfirmListener.consume() 완성: orderId로 PaymentEvent 조회 → Toss API 호출 → 상태 업데이트
- @RetryableTopic(attempts="6", dltTopicSuffix="-dlq") 선언으로 최초 1회 + 5회 재시도 구성
- @DltHandler handleDlt(): DLT 도달 시 executePaymentFailureCompensation() 호출
- KafkaConfirmAdapter.TOPIC "payment-confirm"으로 변경 → DLT 토픽 "payment-confirm-dlq" (KAFKA-06 충족)
- KafkaConfirmListenerTest 8개 + KafkaConfirmAdapterTest 5개 = 13개 테스트 모두 GREEN

## Task Commits

1. **Task 1: KafkaConfirmListener 구현 + TOPIC 변경** - `ec0c404` (feat)

**Plan metadata:** (docs 커밋에 포함 예정)

## Files Created/Modified
- `src/main/java/.../payment/listener/KafkaConfirmListener.java` - @RetryableTopic + @DltHandler 완성 구현
- `src/test/java/.../payment/listener/KafkaConfirmListenerTest.java` - 8개 테스트 (스텁 → GREEN)
- `src/main/java/.../payment/infrastructure/adapter/KafkaConfirmAdapter.java` - TOPIC "payment-confirm"으로 변경
- `src/test/java/.../payment/infrastructure/adapter/KafkaConfirmAdapterTest.java` - 토픽명 검증 업데이트

## Decisions Made
- `consume()` 메서드에 `throws PaymentTossRetryableException` 선언: checked exception이므로 메서드 시그니처에 명시 후 re-throw, @RetryableTopic include 설정으로 retry 토픽 라우팅 위임
- TOPIC = "payment-confirm" 선택: Plan 02의 "payment-confirm-requests" 대신 변경하여 dltTopicSuffix="-dlq" 조합으로 "payment-confirm-dlq" DLT 토픽 생성 (KAFKA-06 명세 정확히 충족)
- NonRetryable 예외 처리: @RetryableTopic exclude 설정 + consume() 내부에서 catch 후 즉시 보상 트랜잭션 실행 (DLT 경유 불필요)

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
- `PaymentTossRetryableException extends Exception` (checked)이므로 consume() 메서드 시그니처에 `throws PaymentTossRetryableException` 추가 필요 — 플랜의 "throw e" 지시와 일치하며, Spring Kafka @RetryableTopic include 설정이 해당 예외를 인식하여 retry 토픽으로 라우팅

## Next Phase Readiness
- KafkaConfirmListener 완성으로 Kafka 비동기 전략 구현 완료
- KAFKA-01~06 요구사항 모두 단위 테스트 GREEN 검증됨
- Phase 04 마무리 후 Phase 05(k6 벤치마크) 진행 가능

---
*Phase: 04-kafka-adapter*
*Completed: 2026-03-15*
