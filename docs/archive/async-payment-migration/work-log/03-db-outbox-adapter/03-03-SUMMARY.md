---
phase: 03-db-outbox-adapter
plan: "03"
subsystem: payment-presentation
tags: [outbox, status-endpoint, outbox-first, mvc-test]
dependency_graph:
  requires: [03-01, 03-02]
  provides: [outbox-status-polling]
  affects: [PaymentController, PaymentPresentationMapper]
tech_stack:
  added: []
  patterns: [outbox-first-query, optional-fallback]
key_files:
  created: []
  modified:
    - src/main/java/com/hyoguoo/paymentplatform/payment/presentation/PaymentController.java
    - src/main/java/com/hyoguoo/paymentplatform/payment/presentation/PaymentPresentationMapper.java
    - src/test/java/com/hyoguoo/paymentplatform/payment/presentation/PaymentControllerMvcTest.java
decisions:
  - "PaymentController에 PaymentOutboxUseCase 직접 DI — 기존 PaymentLoadUseCase와 동일한 패턴 유지"
  - "mapOutboxStatusToPaymentStatusResponse()의 default → PROCESSING: findActiveOutboxStatus가 DONE/FAILED를 이미 필터링하므로 방어적 처리"
metrics:
  duration: 3m
  completed_date: "2026-03-15"
  tasks: 1
  files: 3
---

# Phase 3 Plan 03: Status Endpoint Outbox-First Query Summary

Outbox 우선 조회 로직을 GET /status 엔드포인트에 추가 — PENDING/IN_FLIGHT outbox 레코드가 있으면 즉시 반환, 없으면 PaymentEvent 기반 fallback으로 비동기 confirm 폴링 완전 지원.

## Objective

비동기 confirm 후 클라이언트가 GET /api/v1/payments/{orderId}/status로 폴링할 때 `payment_outbox`의 PENDING/IN_FLIGHT 상태를 올바르게 반환.

## Tasks Executed

### Task 1: Status 엔드포인트 outbox-first 로직 + @WebMvcTest 케이스 (RED → GREEN)

**Commit:** 394252d

**RED phase:** PaymentControllerMvcTest에 MockBean PaymentOutboxUseCase 추가 및 신규 outbox 테스트 2개 작성 → 기대대로 2개 FAILED (500 응답)

**GREEN phase:**
- `PaymentController.getPaymentStatus()` — outbox-first 로직 구현: `paymentOutboxUseCase.findActiveOutboxStatus(orderId)` Optional 반환 시 즉시 응답, empty 시 `paymentLoadUseCase.getPaymentEventByOrderId()` fallback
- `PaymentController` 생성자에 `PaymentOutboxUseCase paymentOutboxUseCase` 추가
- `PaymentPresentationMapper.toPaymentStatusApiResponseFromOutbox()` 신규 static 메서드 추가 (approvedAt=null, outbox status 매핑)
- `mapOutboxStatusToPaymentStatusResponse()` private helper 추가: PENDING→PENDING, IN_FLIGHT→PROCESSING

**Result:** 6/6 테스트 PASSED (기존 4개 유지 + OUTBOX-01 케이스 2개 신규)

## Verification

- `./gradlew test --tests "*.PaymentControllerMvcTest"` — 6/6 PASS
- `./gradlew test` — 250/253 PASS (3 failures = 기존 Docker/Testcontainers 환경 문제, 이번 변경과 무관)

## Deviations from Plan

None — 플랜 명세대로 정확히 실행됨.

## Decisions Made

1. `PaymentController`에 `PaymentOutboxUseCase` 직접 DI: 기존 `PaymentLoadUseCase`와 동일한 usecase 직접 주입 패턴 유지
2. `mapOutboxStatusToPaymentStatusResponse()` default branch → `PROCESSING`: `findActiveOutboxStatus()`가 DONE/FAILED를 이미 필터링하므로 도달하지 않지만, 컴파일러 안전을 위해 방어적 처리

## Self-Check: PASSED

- src/main/java/com/hyoguoo/paymentplatform/payment/presentation/PaymentController.java — FOUND
- src/main/java/com/hyoguoo/paymentplatform/payment/presentation/PaymentPresentationMapper.java — FOUND
- src/test/java/com/hyoguoo/paymentplatform/payment/presentation/PaymentControllerMvcTest.java — FOUND
- Commit 394252d — FOUND
