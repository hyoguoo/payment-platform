# discuss-domain-2

**Topic**: PAYMENT-DOUBLE-FAULT-RECOVERY
**Round**: 2
**Persona**: Domain Expert

## Reasoning
Round 1 D4(major)는 `PaymentStatus` enum 전 값 매핑표 요구였다. Round 2 §4-2 매핑표는 실제 enum 9개 값(READY/IN_PROGRESS/WAITING_FOR_DEPOSIT/DONE/CANCELED/PARTIAL_CANCELED/ABORTED/EXPIRED/UNKNOWN)을 전수 커버하고 `default -> throw`로 exhaustive 처리, 특히 CANCELED/ABORTED/EXPIRED에서 **재confirm 금지 + 실패 보상 + 재고 복구**의 money-risk 경로를 명시했다. D1/D2/D3/D5 minor 응답과 C1(재시도 budget), C2(LogFmt 키) in-scope 편입까지 반영되어 도메인 관점 blocker는 해소.

## Domain risk checklist
- State transition coverage: PASS — §5-1 상태도에 execute READY-only, done approvedAt non-null, DONE 자기루프 금지 명시
- Idempotency: PASS — CAS recoverTimedOutInFlight + claimToInFlight 이중. 재고 복구 멱등성은 §11-4로 plan 이월(명시 과제)
- Money-losing path: PASS — ALREADY_PROCESSED + PG CANCELED/ABORTED/EXPIRED 시 재confirm 금지 경로 §4-2 + §6-7 신설
- PG failure modes: PASS — getStatusByOrderId 실패 시 RETRYABLE 폴백 (§6-2), DONE+approvedAt=null skip+alert (§6-3)
- Race window: PASS — F4 CAS, bulk vs per-row 트레이드오프도 명시
- Domain invariant: PASS — PaymentEvent.done null 거부가 2차 방패, 단독 안전성 확보
- Enum 전 값 매핑(D4): PASS — 9개 값 모두 분기, UNKNOWN/기타 default throw
- Recoverability: PASS — retry budget 소진 시 FAILED + 수동 복구 큐(C1)

## 도메인 관점 추가 검토

1. **§4-2 매핑표 DONE+approvedAt=null skip 정책의 영구 수렁 우려 (minor)**
   - 해당 분기는 RETRYABLE로 무한 incrementRetry되며, budget 소진 시 `PaymentEvent.fail`로 귀결된다. 이는 Toss가 `DONE`을 돌려주는데 실제로는 승인된 결제를 우리가 FAILED로 확정짓고 재고 복구 → **money-leak 가능성(실 승인 있었으나 로컬 FAILED)**. 현 문서는 alert 로그만 언급. plan에서 "budget 소진 시 FAILED가 아닌 MANUAL_REVIEW 격리 상태로 남기는 옵션"을 검토하도록 미결 질문 추가 권장. 단 이는 실사 데이터상 극히 드문 PG 이상 케이스이므로 revise blocker 아님.

2. **§11-4 재고 복구 멱등성이 "plan 검증 항목"으로만 남음 (minor)**
   - CANCELED 분기에서 `StockService.restoreForOrders` 2회 호출 차단 근거는 "PaymentEvent.status=FAILED 재진입 시 no-op"에 의존. 이 가정이 실제로 코드에 존재하는지 본 라운드에서 교차검증되지 않았다. 단, §6-9가 위험을 명시적으로 plan 과제로 이관했으므로 discuss 단계에서는 수용.

3. **§4-2 IN_PROGRESS/WAITING_FOR_DEPOSIT 무한 루프 (minor)**
   - PG가 영속적으로 IN_PROGRESS를 돌려주면 budget 소진까지 재조회. WAITING_FOR_DEPOSIT(가상계좌)은 본질적으로 수 시간~수일 대기이므로 기본 backoff로는 소진이 사실상 확정. §11-5 PARTIAL_CANCELED와 함께 WAITING_FOR_DEPOSIT도 plan 재검토 과제로 추가 권장.

## Findings
- [minor] DONE+approvedAt=null 분기의 budget 소진 → FAILED 확정 시 money-leak 잠재. plan에서 MANUAL_REVIEW 격리 옵션 검토 권장.
- [minor] WAITING_FOR_DEPOSIT이 RETRYABLE로 분류되어 기본 backoff 기준 budget 소진 확정. plan에서 별도 처리 분기 검토 권장.
- [minor] §11-4 StockService.restoreForOrders 멱등성 plan 단계 실제 코드 확인 필수.

## JSON
```json
{
  "persona": "domain-expert",
  "stage": "discuss",
  "round": 2,
  "topic": "PAYMENT-DOUBLE-FAULT-RECOVERY",
  "decision": "pass",
  "findings": [
    {"id": "D-R2-1", "severity": "minor", "area": "money-risk", "note": "DONE+approvedAt=null RETRYABLE budget 소진시 FAILED 확정 → 실 승인건 money-leak 가능. MANUAL_REVIEW 격리 옵션 plan 검토."},
    {"id": "D-R2-2", "severity": "minor", "area": "retry-policy", "note": "WAITING_FOR_DEPOSIT이 일반 RETRYABLE로 분류. 가상계좌 수명주기 상 budget 소진 필연. plan 재분류."},
    {"id": "D-R2-3", "severity": "minor", "area": "idempotency", "note": "StockService.restoreForOrders 멱등성이 가정으로만 존재. plan 실코드 확인."}
  ],
  "resolved_previous": [
    {"id": "D4", "resolution": "§4-2 PaymentStatus 9값 전수 매핑표 + exhaustive default throw로 해소"},
    {"id": "D1", "resolution": "§4-1 DONE→DONE 자기루프 차단 명시"},
    {"id": "D2", "resolution": "§4-2 및 §5-1에 복구 시 PaymentEvent 상태 불변 원칙 명시"},
    {"id": "D3", "resolution": "§4-2 잔존 read-then-save 범위 명시 + plan 전수 감사 이관"},
    {"id": "D5", "resolution": "§4-3 isAlreadyProcessed() 플래그로 gateway enum 경계 분리"}
  ]
}
```
