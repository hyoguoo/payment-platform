# Discuss Round 0 — Interviewer 정리

**TOPIC**: PAYMENT-DOUBLE-FAULT-RECOVERY
**일자**: 2026-04-09
**방침**: CONVERSATION_LOG.md는 참고용. 실제 허점은 코드를 새로 읽어 발굴한다.

---

## Ambiguity Ledger (4트랙)

### Scope
- **확정**: 코드 직접 조사로 발견된 허점들을 우선 정리
- 사용자 선택 범위: F1, F2, F3, F4, F5 전부 포함
- 사용자 제기 추가 케이스: (a) 재고 이중 차감 가능성, (b) getStatus 사전 조회 부재로 인한 부차 리스크

### Constraints
- **미정**: 진행하면서 논의 (PaymentEvent 상태머신 변경 허용 여부, PaymentGatewayPort 시그니처 변경, Outbox 스키마 변경 등 plan 단계에서 확정)

### Outputs
- `docs/topics/PAYMENT-DOUBLE-FAULT-RECOVERY.md` (Architect 작성 예정)
- 해당 경로 수정: PaymentEvent, PaymentCommandUseCase, OutboxProcessingService, PaymentOutboxUseCase, TossPaymentGatewayStrategy 등 (plan에서 확정)

### Verification
- **확정**: 단위 테스트 + 통합 테스트
- PaymentEvent: `@ParameterizedTest` null approvedAt 경계 케이스
- OutboxProcessingService: Fake PG로 ALREADY_PROCESSED, approvedAt=null 시나리오
- PaymentOutboxUseCase: 동시 recover 재클레임 경쟁 상태 (통합)

---

## 발견 허점 (코드 직접 조사)

### F1. `PaymentEvent.done(approvedAt, ...)` null 방어 부재 — Critical
- 위치: `PaymentEvent.java:92-103`
- 내용: `done()`이 `approvedAt=null`을 검증 없이 받아 `this.approvedAt = null`로 덮어씀
- 의미: DONE 상태 불변식 깨짐 ("DONE이면 approvedAt가 반드시 있다" 라는 도메인 규칙 미강제)

### F2. `PaymentCommandUseCase.confirmPaymentWithGateway` 유령 PaymentDetails — Moderate
- 위치: `PaymentCommandUseCase.java:85-103`
- 내용: 실패/재시도 응답에서도 `PaymentDetails.status=DONE` 하드코딩 + `approvedAt=result.approvedAt()`(null 가능)로 `PaymentGatewayInfo` 조립
- 현재: SUCCESS 분기에서만 `getPaymentDetails()`를 읽어 가려져 있지만 코드 오독·향후 변경 시 지뢰

### F3. `OutboxProcessingService` SUCCESS 분기 null 가드 부재 — Critical (F1과 연결)
- 위치: `OutboxProcessingService.java:63-64`
- 내용: `gatewayInfo.getPaymentDetails().getApprovedAt()`를 그대로 `executePaymentSuccessCompletionWithOutbox`에 전달
- F5와 합쳐질 때 `approvedAt=null`이 그대로 관통

### F4. `recoverTimedOutInFlightRecords` 낙관적 CAS 부재 — Major
- 위치: `PaymentOutboxUseCase.java:57-66`
- 내용: `findTimedOutInFlight` 결과를 루프하며 `incrementRetryCount` → save. 조건부 UPDATE(예: `WHERE in_flight_at = :prev`) 없음
- 의미: 다중 워커/인스턴스 또는 스케줄러 overlap 시 동일 IN_FLIGHT 레코드에 대해 두 워커가 동시에 recover → 동일 orderId 이중 처리 가능 (현재는 단일 인스턴스 가정이 암묵적)

### F5. `ALREADY_PROCESSED_PAYMENT` → `approvedAt=null`로 DONE 확정 — Critical
- 추적 경로:
  1. `TossPaymentErrorCode.ALREADY_PROCESSED_PAYMENT.isSuccess() = true` (`TossPaymentErrorCode.java:70-72`)
  2. `PaymentConfirmResultStatus.of()`에서 `isSuccess()` 분기 → `SUCCESS` 반환 (`PaymentConfirmResultStatus.java:22-23`)
  3. `confirmPaymentWithGateway`가 이 응답을 받아 `PaymentDetails(status=DONE, approvedAt=null)`로 조립 (실제 결제 정보 없음)
  4. `OutboxProcessingService` SUCCESS 분기 → `markPaymentAsDone(paymentEvent, null)`
  5. `PaymentEvent.done(null, now)` — F1로 인해 조용히 저장
- 결과: **Double Fault 복구 시 실제 승인된 결제의 approvedAt이 null로 확정되는 버그**

### F6. 재고 이중 차감 — "우연히 막혀 있음" (지뢰) — Moderate
- 경로: `OutboxAsyncConfirmService.confirm()` → `executePaymentAndStockDecreaseWithOutbox`
- `PaymentEvent.execute()`는 READY **또는 IN_PROGRESS** 상태를 모두 허용 (`PaymentEvent.java:65-67`) → 도메인 레벨에서 재진입 방어 없음
- 중복 confirm 요청 시 `decreaseStockForOrders()`가 다시 호출될 경로가 열려 있으나, `PaymentOutboxEntity.orderId`의 `unique=true` 제약(`PaymentOutboxEntity.java:40`) 때문에 두 번째 `createPendingRecord`가 `DataIntegrityViolationException`을 일으켜 단일 `@Transactional` 전체 롤백 → 재고 이중 차감 실제로는 발생하지 않음
- 문제: DB 제약이 "최후의 방패"로 작동. 도메인 엔티티 수준 방어 부재 → 향후 리팩터링 시 쉽게 깨질 수 있음

### F7. getStatus 사전 조회 부재로 인한 본질적 리스크 — Major
- 현재 복구 경로(`recoverTimedOutInFlightRecords` → PENDING → `confirmPaymentWithGateway` 재호출)는 **첫 호출의 성공 여부를 알지 못한 채 무조건 재confirm**
- getStatusByOrderId를 "confirm 재시도 전 조회 단계"로 편입하면:
  - ALREADY_PROCESSED_PAYMENT 경로를 아예 우회 (실제 결제 정보로 completion 진행)
  - approvedAt 누락 위험 제거
- 단, 설계 대안: (A) 재시도 전 조회 단계 삽입, (B) confirm 응답의 ALREADY_PROCESSED 경로에서만 조회 폴백, (C) 조회 없이 F1~F3 방어만으로 충분하다고 정의 — plan 단계에서 트레이드오프 결정

---

## 질의응답 요약

**Q1 (Scope, Path 2)**: 범위 — Double Fault 전체 / Critical 2건 / PG 상태 조회 재설계 중 무엇?
**A1**: "허점을 앞으로 찾을 것" — CONVERSATION_LOG는 참고용, 코드 조사로 재발굴 방향 확정

**Q2 (Verify, Path 2)**: verification 방법?
**A2**: 단위 + 통합 테스트

**Q3 (Constraints, Path 2)**: 비기능 제약 선택
**A3**: "진행하면서 논의" — plan 단계로 이월

**Q4 (Scope, Path 2)**: F1~F5 중 포함 범위
**A4**: F1, F2, F3, F4 모두 포함 + 추가 요청: 재고 이중 차감 가능성 조사 + getStatus 부재 리스크

**Q5 (Next, Path 2)**: F5 및 다음 행동
**A5**: F5 포함, mapper 추가 조사

---

## 확정 가정

1. 이번 TOPIC 범위: F1, F2, F3, F4, F5, F6(도메인 레벨 방어 추가), F7(조회 단계 필요성)
2. 설계 대안 트레이드오프(조회 단계 삽입 vs 조건부 폴백 vs 방어만)는 plan 단계에서 결정
3. verification: 단위 + 통합 테스트
4. 비기능 제약은 plan 단계에서 확정

## Architect에게 넘기는 메시지

위 F1~F7을 하나의 설계 문서에서 일관되게 다룰 것. 특히 F1+F3+F5의 critical 체인과 F6의 "우연한 방어 → 의도적 방어" 리팩터링, F7의 조회 단계 삽입 여부를 핵심 쟁점으로 제시.
