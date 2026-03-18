---
phase: 01-port-contract-status-endpoint
plan: "03"
subsystem: payment-status-endpoint
tags: [status-endpoint, get-mapping, enum-mapping, mvc-test]
dependency_graph:
  requires: [PaymentLoadUseCase, PaymentFoundException-404-handler, PaymentEvent-domain]
  provides: [GET-/api/v1/payments/{orderId}/status, PaymentStatusApiResponse, PaymentStatusResponse, STATUS-01, STATUS-02, STATUS-03]
  affects: [PaymentController, PaymentPresentationMapper, PaymentControllerMvcTest]
tech_stack:
  added: []
  patterns: [switch-expression-enum-mapping, Builder-DTO, WebMvcTest-status-verification]
key_files:
  created:
    - src/main/java/com/hyoguoo/paymentplatform/payment/presentation/dto/response/PaymentStatusResponse.java
    - src/main/java/com/hyoguoo/paymentplatform/payment/presentation/dto/response/PaymentStatusApiResponse.java
    - src/test/java/com/hyoguoo/paymentplatform/mixin/PaymentStatusApiResponseMixin.java
  modified:
    - src/main/java/com/hyoguoo/paymentplatform/payment/presentation/PaymentPresentationMapper.java
    - src/main/java/com/hyoguoo/paymentplatform/payment/presentation/PaymentController.java
    - src/test/java/com/hyoguoo/paymentplatform/payment/presentation/PaymentControllerMvcTest.java
    - src/test/java/com/hyoguoo/paymentplatform/payment/presentation/PaymentControllerTest.java
decisions:
  - "PaymentLoadUseCase를 PaymentController에 직접 DI: 포트 인터페이스 없이 사용 — 플랜 명세에 따라 usecase 직접 주입"
  - "PaymentControllerMvcTest에 STATUS 테스트 3개 추가: Docker API 불일치 환경에서 @WebMvcTest로 STATUS-01~03 자동 검증 달성, PaymentControllerTest의 통합 테스트는 Docker 가용 환경에서 실행 가능"
metrics:
  duration: "6m"
  completed_date: "2026-03-15"
  tasks_completed: 2
  files_changed: 7
requirements_satisfied: [STATUS-01, STATUS-02, STATUS-03]
---

# Phase 01 Plan 03: Status 엔드포인트 추가 Summary

**One-liner:** `GET /api/v1/payments/{orderId}/status` 엔드포인트를 추가하여 `PaymentEventStatus`를 클라이언트용 `PaymentStatusResponse`(DONE/FAILED/PROCESSING)로 매핑하고, `@WebMvcTest` 단위 테스트 3개로 STATUS-01~03 요구사항을 검증했다.

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | PaymentStatusResponse enum + PaymentStatusApiResponse DTO 생성 | 178a0b0 | PaymentStatusResponse.java (생성), PaymentStatusApiResponse.java (생성) |
| 2 | 매퍼 메서드 추가 + 컨트롤러 엔드포인트 추가 + 통합 테스트 | 068db77 (RED), d3b18c4 (GREEN) | PaymentPresentationMapper.java, PaymentController.java, PaymentControllerMvcTest.java, PaymentControllerTest.java, PaymentStatusApiResponseMixin.java (생성) |

## Verification Results

- `./gradlew compileJava compileTestJava` 오류 없음: PASS
- `PaymentControllerMvcTest` 4건 통과 (STATUS-01, STATUS-02, STATUS-03, PORT-02): PASS
- 전체 unit test 86건 통과, 회귀 없음: PASS
- `GET /api/v1/payments/{orderId}/status` 200 반환 + orderId, status, approvedAt 포함: PASS (단위 테스트로 검증)
- 존재하지 않는 orderId 조회 시 404 반환: PASS
- `PaymentEventStatus.DONE → "DONE"`, `READY → "PROCESSING"` 매핑 확인: PASS
- `application.yml`에 `spring.payment.async-strategy: sync` 존재: PASS (Plan 02에서 확인됨)

## Decisions Made

1. **PaymentLoadUseCase 직접 DI**: 플랜 명세에서 `paymentLoadUseCase DI`를 명시했고, `PaymentLoadUseCase`가 `@Service` 클래스이므로 별도 포트 인터페이스 없이 직접 주입. 헥사고날 아키텍처 상 `Presentation → Application` 의존 방향은 준수됨.

2. **PaymentControllerMvcTest에 STATUS 단위 테스트 추가**: Docker API 버전(1.32) 불일치로 TestContainers 기반 통합 테스트(`PaymentControllerTest`) 실행 불가. 이전 플랜(01-02)의 패턴을 따라 `@WebMvcTest` 단위 테스트를 `PaymentControllerMvcTest`에 추가하여 STATUS-01~03 자동 검증. `PaymentControllerTest`의 통합 테스트 3개는 Docker 가용 환경에서 동작하는 end-to-end 커버리지 목적으로 유지.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Test] PaymentControllerMvcTest에 STATUS 단위 테스트 3개 추가**
- **Found during:** Task 2 — 통합 테스트 실행 시 Docker API 버전 불일치 오류 발생
- **Issue:** `PaymentControllerTest`가 TestContainers 기반으로 Docker API 1.44+ 요구, 환경은 1.32만 지원
- **Fix:** Plan 02의 패턴 동일 적용 — `PaymentControllerMvcTest`(`@WebMvcTest`)에 STATUS-01~03에 해당하는 단위 테스트 3개 추가. `@MockBean PaymentLoadUseCase` 추가로 컨텍스트 로딩 해소.
- **Files modified:** PaymentControllerMvcTest.java
- **Commit:** d3b18c4

## Phase 1 완료 상태

이 플랜(01-03)으로 Phase 1의 모든 요구사항이 완료됨:

| 요구사항 | 플랜 | 상태 |
|---------|------|------|
| PORT-01 | 01-01 | 완료 |
| PORT-02 | 01-02 | 완료 |
| PORT-03 | 01-02 | 완료 |
| PORT-04 | 01-02 | 완료 |
| STATUS-01 | 01-03 | 완료 |
| STATUS-02 | 01-03 | 완료 |
| STATUS-03 | 01-03 | 완료 |

## Self-Check: PASSED

- `src/main/java/com/hyoguoo/paymentplatform/payment/presentation/dto/response/PaymentStatusResponse.java`: FOUND
- `src/main/java/com/hyoguoo/paymentplatform/payment/presentation/dto/response/PaymentStatusApiResponse.java`: FOUND
- `src/test/java/com/hyoguoo/paymentplatform/mixin/PaymentStatusApiResponseMixin.java`: FOUND
- Commit 178a0b0: FOUND
- Commit 068db77: FOUND
- Commit d3b18c4: FOUND
