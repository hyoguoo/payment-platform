# discuss-domain-2

**Topic**: PAYMENT-DOUBLE-FAULT-RECOVERY
**Round**: 2
**Persona**: Domain Expert

## Summary
Round 1의 두 major가 모두 Decision 레벨로 해소됨.
- **D7 재작성**: 한도 소진 시점 `getStatusByOrderId` 1회 최종 재확인 필수화. DONE→COMPLETE_SUCCESS 승격, PG terminal/PG_NOT_FOUND→COMPLETE_FAILURE(D12 가드), 판단 불가→QUARANTINE. §7 `FCG` 노드로 반영.
- **D12 신규**: `executePaymentFailureCompensationWithOutbox`의 `increaseStockForOrders`가 `outbox.status==IN_FLIGHT && paymentEvent.status ∈ {READY, IN_PROGRESS, RETRYING}`일 때만 수행. `PaymentTransactionCoordinator.java:61-71` 무가드 현행과 가드 지점 일치. §5 row 6/12/14 및 §7 `GRD`→`TX2`/`TX2N` 일관 배선.

## Findings (plan 단계 이월)

- **[minor] D12 가드의 "event 비종결" 판정 시점** — TX 내부 재조회인지 파라미터 snapshot인지 미명시. TX 내 재조회 권고.
- **[minor] FCG 단발 호출의 retry 카운터 취급** — D2와의 관계 불명확. 카운터 비증가 단발 호출, 실패 시 QUARANTINE 즉시 환원으로 명시 필요.
- **[minor] TX2N 경로 이미 종결된 PaymentEvent에 markPaymentAsFail 호출 동작** — no-op vs 예외, plan에서 소스 검증 필요.

## JSON
```json
{
  "persona": "domain-expert",
  "stage": "discuss",
  "topic": "PAYMENT-DOUBLE-FAULT-RECOVERY",
  "round": 2,
  "decision": "pass",
  "summary": "Round 1 두 major 해소. 남은 3건 minor는 plan 이월.",
  "findings": [
    {"severity": "minor", "area": "D12 가드 판정 시점", "evidence": "§4 D12 텍스트에 재조회/snapshot 명시 없음", "recommendation": "plan에서 TX 내 재조회로 명시."},
    {"severity": "minor", "area": "FCG retry 카운터", "evidence": "§4 D2/D7 관계 불명", "recommendation": "plan에서 카운터 비증가 단발 호출 명시."},
    {"severity": "minor", "area": "markPaymentAsFail 종결 재호출", "evidence": "TX2N 경로 동작 미검증", "recommendation": "plan에서 소스 확인 후 no-op/예외 결정."}
  ]
}
```
