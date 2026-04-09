# discuss-domain-1

**Topic**: PAYMENT-DOUBLE-FAULT-RECOVERY
**Round**: 1
**Persona**: Domain Expert

## Reasoning
코드 교차검증 결과 F1~F7 기술(記述)은 실제 소스와 모두 일치한다. 특히 F5(TossPaymentErrorCode.java:70-72 `isSuccess()=ALREADY_PROCESSED_PAYMENT`) → F3(OutboxProcessingService.java:63-64 null 가드 없음) → F1(PaymentEvent.java:92-103 approvedAt=null 수용)의 critical 체인은 money-losing data corruption 경로로 확정된다. 추천안(대안 B + 부분 A)은 정상 경로 영향 zero + 도메인 불변식 2층 방어로 체인 전체를 차단하며, F4 CAS·F6 도메인 가드 승격·F7 폴백 편입까지 한 라운드에 통합한 설계가 타당하다. 다만 `getStatusByOrderId` 응답 status 분기가 `isDone()` 하나로 뭉개져 취소류 처리가 누락된 점은 복구 경로에 새 money-risk를 남긴다.

## Domain risk checklist

- [x] 멱등성 전략 — `payment_outbox.order_id` UNIQUE(1차) + `PaymentEvent.execute` READY-only(2차) + Toss Idempotency-Key(외부). 3층 명시 (§4-1, §6-5).
- [x] 장애 시나리오 3+ — §6에 6개 식별. 충분.
- [x] 재시도 정책 — 기존 `RetryPolicyProperties` 재사용 + §11-6 복구 retry budget 재검토 명시.
- [x] PII/민감정보 — 신규 도입 없음. n/a.
- [x] 상태 전이 정합성 — §5-1 mermaid로 execute READY-only, done approvedAt non-null 명시. 단 `done()` 자기루프(DONE→DONE) 허용 잔존 — finding D1.
- [x] race window — CAS로 커버(§6-1,4). 단 recover 외 read-then-save 잔존(D3).
- [x] money 경로 — F1+F3+F5 2층 방어로 차단. 단 D4(PG status 분기) 취소류 누락.

## 도메인 관점 추가 검토

1. **`PaymentEvent.done()` 자기루프 허용**(PaymentEvent.java:95) — 추천안은 approvedAt non-null만 강제하고 DONE→DONE 재진입은 건드리지 않음. outbox CAS가 1차 방어하므로 실경로는 차단되나, "도메인 가드 단독 안전성" 원칙에서 구멍. Minor(D1).

2. **복구 시 PaymentEvent 상태 불변**(PaymentOutboxUseCase.java:57-66 확인) — `recoverTimedOutInFlightRecords`는 outbox만 건드리고 PaymentEvent는 IN_PROGRESS/RETRYING 유지. 재 confirm 후 `done(approvedAt non-null)` 가드 통과 OK. topic에 비명시. Minor(D2).

3. **`loadPaymentEvent` 실패 → `incrementRetryOrFail`**(OutboxProcessingService.java:99) — IN_FLIGHT 상태 outbox에 read-then-save가 남는다. F4 CAS 전환이 이 경로를 커버하지 않음. Minor(D3).

4. **`getStatusByOrderId` 응답 status 분기 누락**(topic §4-2 else) — `isDone()` 하나로 DONE만 처리. CANCELED/ABORTED/IN_PROGRESS 응답 시 else → RETRYABLE 재진입 → **취소된 결제를 계속 재confirm**하는 money-risk. `PaymentStatusResult` 상태별 명시적 매핑 필요. **Major(D4)**.

5. **`isSuccess()/isFailure()` 연동 파급**(TossPaymentErrorCode.java:70-84) — `isSuccess()=false`로 돌리면 `isFailure()=!false && !retryable=true` 전환, 타 사용처 영향. topic §11-4에 plan 이월 표기는 있으나 gateway/domain enum 분리 전략 명시 권고. Minor(D5).

## Findings

| # | Severity | Area | Evidence | Issue |
|---|----------|------|----------|-------|
| D1 | minor | domain-invariant | PaymentEvent.java:95 | `done()` DONE→DONE 자기루프 + approvedAt 재할당 허용 |
| D2 | minor | documentation | topic §4-2,§5-1 | 복구 시 PaymentEvent 상태 불변 비명시 |
| D3 | minor | race-cas | OutboxProcessingService.java:99 | recover 외 read-then-save 잔존(IN_FLIGHT incrementRetryOrFail) |
| D4 | **major** | pg-failure-mode | topic §4-2 else; PaymentGatewayPort.java:19 | getStatusByOrderId status 분기가 isDone() 하나로 뭉개짐. CANCELED/ABORTED 재시도 money-risk |
| D5 | minor | enum-coupling | TossPaymentErrorCode.java:70-84 | isSuccess/isFailure 연동 파급, gateway/domain enum 수정 경계 불명확 |

## 판정
- Critical: 0, Major: 1, Minor: 4
- Decision: **revise**

## JSON
```json
{
  "stage": "discuss",
  "topic": "PAYMENT-DOUBLE-FAULT-RECOVERY",
  "round": 1,
  "persona": "domain-expert",
  "decision": "revise",
  "summary": "F1~F7 critical 체인과 2층 방어 추천안은 소스와 정합하나, getStatusByOrderId 응답의 취소/진행중 상태 분기 누락이 복구 경로에 새 money-risk를 남긴다.",
  "findings": [
    {"id":"D1","severity":"minor","area":"domain-invariant","evidence":"src/main/java/com/hyoguoo/paymentplatform/payment/domain/PaymentEvent.java:95","issue":"done() DONE→DONE 자기루프 및 approvedAt 재할당 허용","recommendation":"plan에서 done()의 DONE 허용 제거 또는 approvedAt 재할당 금지 가드 검토"},
    {"id":"D2","severity":"minor","area":"documentation","evidence":"docs/topics/PAYMENT-DOUBLE-FAULT-RECOVERY.md §4-2,§5-1","issue":"복구 시 PaymentEvent 상태 변경 없이 outbox만 PENDING 전환되는 사실 비명시","recommendation":"topic/plan에 복구 범위 명시"},
    {"id":"D3","severity":"minor","area":"race-cas","evidence":"src/main/java/com/hyoguoo/paymentplatform/payment/scheduler/OutboxProcessingService.java:99","issue":"recover 외 read-then-save 잔존(IN_FLIGHT incrementRetryOrFail)","recommendation":"plan에서 잔존 read-then-save 전수 점검"},
    {"id":"D4","severity":"major","area":"pg-failure-mode","evidence":"docs/topics/PAYMENT-DOUBLE-FAULT-RECOVERY.md §4-2 ALREADY_PROCESSED else; src/main/java/com/hyoguoo/paymentplatform/payment/application/port/PaymentGatewayPort.java:19","issue":"getStatusByOrderId 응답 status가 isDone() 하나로 뭉개져 CANCELED/ABORTED/IN_PROGRESS 처리 누락. 취소된 결제를 RETRYABLE로 재confirm하는 money-risk","recommendation":"PaymentStatusResult 상태별 매핑(DONE=완료, CANCELED/ABORTED=NON_RETRYABLE 보상, IN_PROGRESS=RETRYABLE, 그 외=경고+NON_RETRYABLE)을 topic §4-2에 명시"},
    {"id":"D5","severity":"minor","area":"enum-coupling","evidence":"src/main/java/com/hyoguoo/paymentplatform/paymentgateway/exception/common/TossPaymentErrorCode.java:70-84","issue":"isSuccess/isFailure 연동으로 gateway/domain enum 수정 경계 불명확","recommendation":"topic에 gateway enum 수정과 domain ALREADY_PROCESSED 결과 상태 신규의 분리 전략 기재"}
  ],
  "checklist": {
    "scope": "pass",
    "design_decisions": "pass",
    "acceptance_criteria": "pass",
    "verification_plan": "pass",
    "artifact": "pass",
    "domain_risk": "revise"
  },
  "next_actions": [
    "topic §4-2 else 분기에 PaymentStatusResult 상태별 처리 명시(D4)",
    "topic §11/§4-2에 gateway/domain enum 수정 경계 명시(D5)",
    "plan 단계에서 D1~D3 잔존 항목 재검토"
  ]
}
```
