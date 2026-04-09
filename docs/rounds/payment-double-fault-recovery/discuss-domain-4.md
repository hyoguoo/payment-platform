# discuss-domain-4

**Topic**: PAYMENT-DOUBLE-FAULT-RECOVERY
**Round**: 4
**Persona**: Domain Expert

## Reasoning
Round 3 domain findings 4건(D6 major, D7~D9 minor)이 §4-2/§0-4/§7에 모두 반영되었다. 특히 D6은 매핑표에 `PG DONE + approvedAt non-null + 로컬 PaymentEvent.status==DONE` 서브분기가 별도 행으로 추가되어(§4-2 line 156) `markPaymentAsDone` 생략 + `PaymentOutbox.toDone`만 수행하도록 명시되었고, D1 가드와의 충돌로 인한 영구 retry 루프 경로가 닫혔다. D7은 §0-4 근거4에 "getStatus API 자체가 TTL 없이 영구 동작한다는 뜻이 아님"과 retention은 별도 가정임이 명시되었고, D8은 §7 `OutboxProcessingServiceTest`에 "CANCELED 분기 멱등 회귀 테스트"가 `ABORTED/EXPIRED` 포함해 추가되었고, D9는 §4-2 결제 없음 행 `[^noop]` 각주로 §11-7 보수적 디폴트 단서가 인라인되었다. 도메인 리스크 체크리스트 6개 항목 모두 yes. 신규 도메인 회귀 없음.

## Domain risk checklist
- [x] 멱등성 전략 — `payment_outbox.order_id` UNIQUE + `PaymentEvent.execute` READY-only + Toss Idempotency-Key + getStatus 선행 4층 방어 유지 (§4-1, §6-5)
- [x] 장애 시나리오 — §6에 14개 식별, 이중 장애(§6-7)·F-D-1(§4-2 DONE/null 행)·F-D-2(§6-11) 모두 닫힘
- [x] 재시도 정책 — `RetryPolicyProperties` 공유, 소진 시 `RETRY_BUDGET_EXHAUSTED` 운영 큐
- [x] PII — 신규 도입 없음
- [x] 상태 전이 — execute READY-only, done(approvedAt non-null), DONE→DONE 차단, **state-sync only 분기**로 outbox-payment 엇나감 복구 경로 확보(D6)
- [x] race window — F4 CAS, claimToInFlight CAS, state-sync 분기는 `PaymentOutbox.toDone` 단일 TX
- [x] money 경로 — CANCELED/ABORTED/EXPIRED 재confirm 차단 + 멱등 재고 복구(회귀 테스트까지 명시)

## 도메인 관점 추가 검토

1. **D6 분기의 정합성** — §4-2 line 156의 "state-sync only" 행은 `application에서 paymentEvent.getStatus() == DONE 선검사 후 분기`로 명시되어 service layer에서 분기 책임이 명확하다. `approvedAt` 일치 검증도 "가능하면 수행, 불일치 시 alert+SUSPENDED"로 더 강한 도메인 안전성이 추가됐다. outbox만 terminal 전환하므로 D1 가드와 충돌하지 않고, 영구 retry 루프 원천 차단. **OK**.

2. **D7 문구 보정** — line 24가 "confirm 멱등키 TTL과 무관"과 "getStatus retention은 별도 가정"을 구분해 서술하고, retention 만료 시 §6-14 보수적 디폴트(skip+retry, money-leak 없는 안전 측 실패)로 이관됨을 명시. §11-7 실험 과제에 retention 확인도 포함. 오해 소지 해소. **OK**.

3. **D8 회귀 테스트** — §7 line 344의 D8 항목은 "동일 orderId로 CANCELED 반복 반환 → 2번째 사이클 restoreForOrders no-op"을 재고 총량 스냅샷 비교로 검증하도록 구체화. ABORTED/EXPIRED 확장도 명시. **OK**.

4. **D9 매핑표/§11-7 충돌** — line 169 `[^noop]` 각주가 "실험 완료 전/후" 단계 차이로 충돌을 해소하고, 실험 전에는 confirm 금지 보수적 디폴트가 적용됨을 재확인. **OK**.

5. **신규 도메인 리스크 탐색** — state-sync only 분기에서 `approvedAt 불일치 시 alert+SUSPENDED`의 SUSPENDED 상태 정의는 `PaymentEvent` enum이 아닌 outbox 운영 상태로 해석되는데, 혼동 소지가 있으나 plan에서 "outbox alert 레벨"로 구체화될 범위. discuss 게이트 차단 사유 아님. **n/a**.

## Findings
없음 (D6~D9 전부 반영 확인, 신규 도메인 리스크 없음).

## 판정
- Critical: 0, Major: 0, Minor: 0
- Decision: **pass**

## JSON
```json
{
  "stage": "discuss",
  "topic": "PAYMENT-DOUBLE-FAULT-RECOVERY",
  "round": 4,
  "persona": "domain-expert",
  "task_id": null,
  "decision": "pass",
  "reason_summary": "Round 3 D6 major / D7~D9 minor가 §4-2 state-sync only 분기 추가, §0-4 TTL 표현 보정, §7 CANCELED 멱등 회귀 테스트, §4-2 결제 없음 각주로 모두 반영되었고 신규 도메인 리스크 없음.",
  "checklist": {
    "source": "_shared/checklists/discuss-ready.md",
    "items": [
      {"section":"design_decisions","item":"state-sync only 분기 추가","status":"yes","evidence":"docs/topics/PAYMENT-DOUBLE-FAULT-RECOVERY.md §4-2 line 156"},
      {"section":"design_decisions","item":"멱등키 TTL 표현 보정","status":"yes","evidence":"docs/topics/PAYMENT-DOUBLE-FAULT-RECOVERY.md §0-4 근거4 line 24"},
      {"section":"verification_plan","item":"CANCELED 멱등 회귀 테스트","status":"yes","evidence":"docs/topics/PAYMENT-DOUBLE-FAULT-RECOVERY.md §7 line 344"},
      {"section":"design_decisions","item":"결제 없음 행 §11-7 단서 인라인","status":"yes","evidence":"docs/topics/PAYMENT-DOUBLE-FAULT-RECOVERY.md §4-2 line 169 [^noop]"},
      {"section":"domain_risk","item":"멱등성 전략","status":"yes","evidence":"§4-1, §6-5"},
      {"section":"domain_risk","item":"장애 시나리오 3개 이상","status":"yes","evidence":"§6 14개"},
      {"section":"domain_risk","item":"재시도 정책","status":"yes","evidence":"§4-2 하단"},
      {"section":"domain_risk","item":"PII 경로","status":"n/a","evidence":"신규 도입 없음"}
    ],
    "total": 8,
    "passed": 7,
    "failed": 0,
    "not_applicable": 1
  },
  "scores": {
    "clarity": 0.92,
    "completeness": 0.93,
    "risk": 0.95,
    "testability": 0.93,
    "fit": 0.93,
    "mean": 0.932
  },
  "findings": [],
  "previous_round_ref": "discuss-domain-3.md",
  "delta": {
    "newly_passed": [
      "§4-2 state-sync only 서브분기 (D6)",
      "§0-4 멱등키 TTL 문구 보정 (D7)",
      "§7 CANCELED/ABORTED/EXPIRED 멱등 회귀 테스트 (D8)",
      "§4-2 결제 없음 행 §11-7 단서 인라인 (D9)"
    ],
    "newly_failed": [],
    "still_failing": []
  },
  "unstuck_suggestion": null
}
```
