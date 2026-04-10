# discuss-domain-1

**Topic**: PAYMENT-DOUBLE-FAULT-RECOVERY
**Round**: 1
**Persona**: Domain Expert

## Reasoning
산출물이 주장하는 핵심 사실(`PaymentStatus.UNKNOWN` 존재, `PaymentGatewayPort.getStatusByOrderId` 미사용, `PaymentEvent.done(approvedAt, ...)` 시그니처, `executePaymentFailureCompensationWithOutbox`의 무방어 재고 복구)은 실제 소스에서 모두 확인된다. 설계 방향(getStatus 선행, UNKNOWN 제거, QUARANTINED 도입, approvedAt 가드)은 도메인 리스크를 정확히 겨누고 있다. 다만 두 가지 돈 관련 경로가 Decision 레벨로 명시되지 않았다: (1) 재고 복구 멱등 가드가 §5 row 12 각주로만 존재하고 D-entry/플로우로 승격되지 않음, (2) D7의 CONFIRM_EXHAUSTED 자동 COMPLETE_FAILURE 경로가 동일 틱에서 재확인 getStatus 없이 자동 실패 확정 + 재고 복구로 이어져 "PG는 실제 체결, 로컬은 FAILED" 이중장애 창을 남긴다.

## Domain risk checklist
- 상태 전이 올바름: 전반적으로 건전. RecoveryDecision 단일화 타당. gap: RETRYING→QUARANTINED 전이 허용 여부 plan에서 소스 검증 필요.
- 멱등성/정합성: getStatus 선행·Idempotency-Key 유지 OK. gap: `executePaymentFailureCompensationWithOutbox` 재고 복구 무가드 (`PaymentTransactionCoordinator.java:61-71`).
- PG 실패 모드: 분류 재사용 타당. gap: D7이 confirm retryable 소진을 "판단 가능한 실패"로 오분류.
- Race window: claimToInFlight 유지(D9) 합리적.
- 재고 이중 복구: 상단 gap.
- PII: 해당 없음.
- 금전 정확성: approvedAt 가드(D10) 타당.

## Findings

- **[major] 재고 복구 멱등 가드가 Decision으로 승격되지 않음** — `executePaymentFailureCompensationWithOutbox` 재고 이중 복구 방지 가드가 §4/§7에 없다. Decision으로 명시 필요.
- **[major] D7 CONFIRM_EXHAUSTED 자동 실패 경로가 D1 철학과 모순** — 한도 소진 직전 getStatus 재확인 필수화, 판단 불가면 QUARANTINE 환원.
- **[minor] QUARANTINED 관측 최소 메트릭 in-scope 승격 검토**.
- **[minor] RETRYING 진입 source 상태 매트릭스 plan 검증**.

## JSON
```json
{
  "persona": "domain-expert",
  "stage": "discuss",
  "topic": "PAYMENT-DOUBLE-FAULT-RECOVERY",
  "round": 1,
  "decision": "fail",
  "summary": "설계 방향은 도메인 리스크를 정확히 겨누고 있으나, (1) 재고 복구 멱등 가드가 Decision으로 승격되지 않았고 (2) D7의 CONFIRM_EXHAUSTED 자동 실패 경로가 D1(getStatus 선행) 철학과 모순되어 '돈은 나갔는데 로컬은 실패' 이중장애 창을 남긴다.",
  "findings": [
    {"severity": "major", "area": "재고 복구 멱등성", "evidence": "PaymentTransactionCoordinator.java:61-71 무가드; §5 row 12만 언급, §4/§7 누락", "recommendation": "executePaymentFailureCompensationWithOutbox 재고 복구 가드를 Decision 승격. (outbox.status==IN_FLIGHT && event 비종결) 조건 명시."},
    {"severity": "major", "area": "PG 실패 모드 / 금전 정확성", "evidence": "§7 CF→RC→FC[CONFIRM_EXHAUSTED] 경로가 getStatus 재확인 없이 COMPLETE_FAILURE+재고 복구로 직진; D1 위배", "recommendation": "confirm 한도 소진 지점 getStatus 1회 재확인 필수화. NOT_FOUND→FAILED, DONE→SUCCESS, 판단 불가→QUARANTINE."},
    {"severity": "minor", "area": "운영 관측", "evidence": "§8.3 후속, in-scope 관측 0", "recommendation": "최소 Micrometer 카운터 1개 in-scope 승격."},
    {"severity": "minor", "area": "상태 머신 가드", "evidence": "복구 재진입 시 READY 상태 가능성 §7 미표시", "recommendation": "plan 단계에서 markPaymentAsRetrying source 상태 매트릭스 검증."}
  ]
}
```
