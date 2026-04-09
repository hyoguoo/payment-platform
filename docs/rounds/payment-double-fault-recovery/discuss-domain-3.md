# discuss-domain-3

**Topic**: PAYMENT-DOUBLE-FAULT-RECOVERY
**Round**: 3
**Persona**: Domain Expert

## Reasoning
대안 C 전환은 F5/F7 critical 체인의 진입 경로 자체를 사실상 닫는다. 코드 교차 검증 결과 `PaymentGatewayPort.getStatusByOrderId`(PaymentGatewayPort.java:19)는 이미 존재하고, `PaymentStatus` enum(PaymentStatus.java:9-19)이 매핑표가 요구하는 값(WAITING_FOR_DEPOSIT/PARTIAL_CANCELED/ABORTED/EXPIRED/UNKNOWN 포함)을 모두 갖고 있어 exhaustive switch가 컴파일 레벨로 강제 가능하다. 6분류 매핑·"결제 없음 → 보수적 디폴트"·D2(PaymentEvent 상태 불변)·D4(취소류 보상)·F-D-1(DONE+approvedAt=null)·F-D-2(재고 멱등) 모두 도메인 안전성 측면에서 회귀 없이 유지된다. 다만 "멱등키 TTL 의존성 제거" 주장(§0 근거4)은 정확히는 "TTL 만료 후에도 getStatus가 진실의 원천이 되어 신규 승인이 발생하지 않는다"는 논리이지, getStatus API 자체가 TTL이 없다는 별개 사실 진술로 읽히면 오해 소지가 있다. discuss 게이트 통과를 막는 이슈는 아니다.

## Domain risk checklist
- [x] 멱등성 전략 — `payment_outbox.order_id` UNIQUE(1차) + `PaymentEvent.execute` READY-only(2차) + Toss Idempotency-Key(3차). 추가로 대안 C에서 getStatus 선행이 "쓰기 전 사실 확인" 4층 방어가 됨. (§4-1, §6-5)
- [x] 장애 시나리오 — §6에 13개 식별. 이중 장애 원형 시나리오(§6-7) 명시적으로 닫힘.
- [x] 재시도 정책 — 기존 `RetryPolicyProperties` 공유, budget 분리 안 함(C1). 소진 시 `RETRY_BUDGET_EXHAUSTED` + 운영 큐.
- [x] PII/민감정보 — 신규 도입 없음.
- [x] 상태 전이 정합성 — §5-1에서 execute READY-only, done(approvedAt non-null), DONE→DONE 자기루프 차단 (D1 해소).
- [x] race window — F4 CAS, claimToInFlight CAS, getStatus는 idempotent read.
- [x] money 경로 — F1+F3+F5 도메인 가드 + getStatus 선행으로 2층 방어. CANCELED/ABORTED/EXPIRED 재confirm 차단(D4 해소).

## 도메인 관점 추가 검토 — 판정 포인트별

1. **F5/F7 critical 체인 차단 여부** — F7은 §4-2 매핑표 `DONE+approvedAt non-null` 행이 confirm 재호출을 생략하므로 **완전 차단**. F5의 `ALREADY_PROCESSED_PAYMENT.isSuccess()=true` 매핑(TossPaymentErrorCode.java:70-72) 발동 경로는 "PG에 결제가 없는 경우에만 confirm 호출 → 그 confirm 결과가 ALREADY_PROCESSED" 라는 극히 좁은 race(첫 조회 직후 다른 워커가 PG에 도달)로 축소된다. §4-3에서 `isAlreadyProcessed()` 별도 플래그(옵션 ii) 추천도 유지되어 매핑 정정 자체는 빠지지 않는다. **OK**.

2. **getStatus 6분류에 money-leak 잔존?** — 매핑표(§4-2) 7개 행 + 결제 없음 + 예외 = 9분류. 모든 RETRYABLE 분기(IN_PROGRESS/WAITING_FOR_DEPOSIT/READY/PARTIAL_CANCELED/DONE+null/UNKNOWN/예외)가 **`PaymentEvent` 상태 변경 없이 outbox만 재시도**라 money 이동이 발생할 분기가 없다. NON_RETRYABLE 보상(CANCELED/ABORTED/EXPIRED)은 재고 복구만 수행하므로 money 차감은 일어나지 않는다. SUCCESS 분기(DONE+approvedAt non-null)는 `executePaymentSuccessCompletionWithOutbox`로 직접 진입하여 confirm 재호출 자체가 없다. **money-leak 경로 없음**.

3. **"결제 없음" 응답 해석 실패 시 디폴트** — §6-13 + §11-7이 "응답 포맷 미확정 → skip + incrementRetryOrFail(RETRYABLE) + alert, **confirm을 쏘지 않는다**" 원칙을 명시. 이는 보수적이고 안전하다. 다만 이 원칙이 `OutboxProcessingService.process` 진입점에서 명문화되지 않으면 execute 단계에서 "404를 일반 예외로 묶어버리고 confirm으로 폴백" 같은 잘못된 해석이 들어올 수 있다. **plan/execute 이관 명시는 충분**하므로 discuss 단계에서는 minor.

4. **F-D-1 (DONE+approvedAt=null)** — §4-2 매핑표의 `DONE/null` 행이 `incrementRetry + alert(SUSPENDED)` 처리. 도메인 가드 `done(approvedAt non-null)`이 2차 방패(§4-1). 두 층 모두 살아 있음. PG가 비정상 응답을 영구 유지할 경우 `RETRY_BUDGET_EXHAUSTED` 경로로 운영 큐 이관됨. **OK**.

5. **F-D-2 (CANCELED/ABORTED/EXPIRED 재고 이중 복구 방지)** — §6-11 + §11-4가 `StockService.restoreForOrders` 멱등성을 plan 검증 항목으로 명시. 매핑표 NON_RETRYABLE 행은 `executePaymentFailureCompletionWithOutbox`를 1회만 호출하나, 복구 사이클이 여러 번 돌면서 같은 outbox가 다시 IN_FLIGHT→PENDING으로 떨어지는 경우 재진입 시 `PaymentEvent.status==FAILED`일 수 있어 멱등 가드가 필수다. plan에서 실제 `restoreForOrders` 코드 확인이 빠지면 회귀 위험. **plan 단계에서 강제 검증 필요(major까지는 아님, minor)**.

6. **멱등키 TTL 제거 주장의 정확성** — §0-4와 §3 대안 C 장점에서 "PG 멱등키 TTL 의존성 제거"라는 표현이 사용되는데, 이는 "재confirm을 안 하므로 TTL이 무관해진다"는 의미이지 "Toss getStatus API가 TTL 없이 동작한다"는 뜻이 아니다. Toss getPayment API가 영구 보관되는지(예: 90일/1년 retention)는 본 문서가 실증하지 않는다. 만약 Toss가 일정 기간 후 historical 결제를 404로 응답한다면, **N일 이후 복구 레코드는 §6-13의 보수적 디폴트(skip + retry)에 의해 영원히 retry budget을 소진**하다 운영 큐로 빠진다. money-leak은 발생하지 않으므로 안전 측 실패이지만, "모든 시간 범위에서 자동 복구"라는 §0-4 표현은 과장이다. **문구 보정 minor**.

7. **`process()` 진입점 순서: claimToInFlight → getStatus → ... 사이의 race** — 이론적으로 워커 A가 claim 후 getStatus 호출 직전에 죽고 다른 워커 B가 in_flight_at timeout 후 다시 claim 받아 getStatus를 호출하는 시나리오는 idempotent하므로 안전. 단, 워커 A가 getStatus는 받았으나 `executePaymentSuccessCompletionWithOutbox` TX 직전에 죽는 경우 → 다음 사이클이 다시 getStatus → DONE+approvedAt → 동일 `done(approvedAt)` 호출 → `done`이 이미 DONE인 PaymentEvent를 거부(D1 가드)로 예외 → outbox는 incrementRetryOrFail로 빠짐. 즉 "이미 완료된 PaymentEvent + IN_FLIGHT outbox"가 영구 retry로 갇힐 수 있다. §4-2 매핑표가 "`PaymentEvent.status==DONE`인 경우 outbox만 toDone으로 동기화" 분기를 명시하지 않으면 운영 알람이 지속 발생한다. **major 후보**: outbox-payment 상태 sync 분기 누락.

8. **`OutboxProcessingService` getStatus call이 트랜잭션 밖이라는 원칙(§8)** — 명시되어 OK. 네트워크 호출이 TX를 잡지 않음.

## Findings

| # | Severity | Area | Evidence | Issue |
|---|----------|------|----------|-------|
| D6 | major | state-sync | docs/topics/PAYMENT-DOUBLE-FAULT-RECOVERY.md §4-2; PaymentEvent.java:92-103 | getStatus=DONE+approvedAt non-null인데 PaymentEvent가 이미 DONE인 경우 처리 분기가 매핑표에 없음. D1 가드(`done` DONE→DONE 거부)와 충돌하여 영구 retry 루프 가능. "PaymentEvent 이미 DONE → outbox만 toDone 동기화" 분기를 §4-2에 추가 필요 |
| D7 | minor | wording | docs/topics/PAYMENT-DOUBLE-FAULT-RECOVERY.md §0-4, §3 대안 C 장점 | "멱등키 TTL 의존성 제거"는 "재confirm을 안 해서 무관해진다"는 의미. Toss getPayment retention이 무한이라는 주장으로 오해될 수 있음. 문구를 "재confirm을 하지 않으므로 TTL과 무관" 정도로 보정 |
| D8 | minor | execute-handover | docs/topics/PAYMENT-DOUBLE-FAULT-RECOVERY.md §11-4 | `StockService.restoreForOrders` 멱등성은 plan 검증 항목으로 위임됐으나, 본 라운드에서 "FAILED 재진입 no-op" 회귀 테스트 케이스를 §7에 명시 추가 권장 |
| D9 | minor | mapping-table-default | docs/topics/PAYMENT-DOUBLE-FAULT-RECOVERY.md §4-2 결제 없음 행 | "결제 없음" 디폴트가 `confirm` 호출인데, §11-7에서 "응답 포맷 미확정 시 skip+retry"로 다시 뒤집힘. 두 곳의 충돌 가능성 — 매핑표에 "포맷 확정 후" 단서를 인라인으로 추가 권장 |

## 판정
- Critical: 0, Major: 1, Minor: 3
- Decision: **revise**

## JSON
```json
{
  "stage": "discuss",
  "topic": "PAYMENT-DOUBLE-FAULT-RECOVERY",
  "round": 3,
  "persona": "domain-expert",
  "decision": "revise",
  "summary": "대안 C 전환은 F5/F7 critical 체인을 도메인 안전성 관점에서 사실상 닫고 6분류 매핑에 money-leak 경로가 없음을 확인했다. 다만 PaymentEvent가 이미 DONE인데 getStatus=DONE이 돌아오는 분기가 매핑표에 없어 D1 가드와 충돌하며 영구 retry 루프를 만들 수 있다.",
  "findings": [
    {"id":"D6","severity":"major","area":"state-sync","evidence":"docs/topics/PAYMENT-DOUBLE-FAULT-RECOVERY.md §4-2; src/main/java/com/hyoguoo/paymentplatform/payment/domain/PaymentEvent.java:92-103","issue":"getStatus=DONE+approvedAt non-null이고 PaymentEvent.status==DONE인 경우의 분기가 매핑표에 없음. D1 가드(done DONE→DONE 거부)와 충돌해 markPaymentAsDone가 매번 예외 → outbox 영구 retry 루프 가능","recommendation":"§4-2 SUCCESS 행에 'PaymentEvent.status가 이미 DONE이면 markPaymentAsDone 생략하고 outbox.toDone만 수행' 서브분기 추가"},
    {"id":"D7","severity":"minor","area":"wording","evidence":"docs/topics/PAYMENT-DOUBLE-FAULT-RECOVERY.md §0-4, §3 대안 C 장점","issue":"'멱등키 TTL 의존성 제거' 표현이 'getStatus API 자체가 TTL 없음'으로 오해될 여지","recommendation":"'재confirm을 수행하지 않으므로 PG 멱등키 TTL과 무관' 정도로 문구 보정. getStatus retention 자체는 별도 가정"},
    {"id":"D8","severity":"minor","area":"verification","evidence":"docs/topics/PAYMENT-DOUBLE-FAULT-RECOVERY.md §7 단위 테스트 / §11-4","issue":"StockService.restoreForOrders 멱등성 plan 위임이지만 회귀 테스트 케이스 명시가 없음","recommendation":"§7에 'FAILED 재진입 시 restoreForOrders no-op 회귀 테스트' 항목 추가"},
    {"id":"D9","severity":"minor","area":"mapping-table","evidence":"docs/topics/PAYMENT-DOUBLE-FAULT-RECOVERY.md §4-2 결제 없음 행; §11-7","issue":"매핑표 1행은 '결제 없음 → confirm 호출'인데 §11-7은 '응답 포맷 미확정 → skip+retry, confirm 안 함'. 표면상 충돌","recommendation":"매핑표 1행에 '(응답 포맷 확정 이후 적용; 그 전까지는 §6-13 보수적 디폴트)' 단서 인라인"}
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
    "§4-2 SUCCESS 행에 PaymentEvent 이미 DONE 서브분기 추가 (D6)",
    "§0-4/§3 멱등키 TTL 표현 보정 (D7)",
    "§7에 restoreForOrders 멱등 회귀 테스트 추가 (D8)",
    "§4-2 결제 없음 행에 §6-13/§11-7 단서 인라인 (D9)"
  ]
}
```
