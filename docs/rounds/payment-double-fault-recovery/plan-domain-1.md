# plan-domain-1

**Topic**: PAYMENT-DOUBLE-FAULT-RECOVERY
**Round**: 1
**Persona**: Domain Expert

## Reasoning
PLAN은 F1~F7 critical 체인을 도메인 불변식·CAS·exhaustive 매핑·통합 테스트로 잘 덮었고, 대부분의 discuss 도메인 리스크가 태스크에 매핑돼 있다. 다만 (1) `DONE+approvedAt=null` 경로가 RETRYABLE 버킷에 머무른 채 retry budget 소진 시 `executePaymentFailureCompensationWithOutbox`로 흘러 **실제 승인된 결제가 FAILED + 재고 복구**되는 명백한 money-leak가 plan에서 가드 없이 남았고, (2) Task 12의 `StockService.restoreForOrders` 멱등성 보강 위치(payment 측 caller-guard vs product 측 provider-guard)가 결정되지 않아 CANCELED/ABORTED/EXPIRED 보상 경로의 재고 이중 복구 위험이 plan 종료 시점까지 미정이다. 두 항목 모두 도메인 리스크 관점의 major.

## Domain risk checklist
- [x] discuss에서 식별된 domain risk가 각각 대응 태스크를 가짐 — F1~F7, D1~D5, D-R2-1~3 모두 매핑 테이블(line 293~311) 존재
- [~] 중복 방지 체크가 필요한 경로에 계획됨 — outbox claim/recover CAS는 Task 7/9/10으로 보장. `restoreForOrders` 멱등성은 Task 12에 있으나 **수정 위치 미확정**(PLAN line 287). 보상 호출의 멱등 가드 자체가 plan 단계에서 결정되지 않은 것은 결함.
- [x] 재시도 안전성 검증 태스크 존재 — Task 7/8/10/11

## 도메인 관점 추가 검토
1. **DONE+approvedAt=null의 budget-exhaustion money-leak (Critical 잔존)**
   - `OutboxProcessingService.process` line 75~88(현 코드)을 직접 확인: `RETRYABLE_FAILURE`에서 `policy.isExhausted` → `executePaymentFailureCompensationWithOutbox` → 재고 복구 + `markPaymentAsFail`. 이는 PG에서 실제로 승인된 건(돈은 빠져나간 상태)을 FAILED로 확정하고 재고만 되돌리는 경로다.
   - PLAN Task 8의 `process_alreadyProcessed_pgStatusDone_nullApprovedAt_incrementsRetryAndAlerts` 케이스는 이 레코드를 RETRYABLE로 두고 alert만 남긴다. 매핑 테이블(line 309) D-R2-1 처리도 "MANUAL_REVIEW 격리는 non-goal"로 명시. 결과적으로 budget 소진 시 동일 레코드가 일반 RETRYABLE 경로로 흘러들어 **money-leak가 plan에 의해 의도적으로 열려 있다**.
   - 최소한의 도메인 가드: budget 소진 분기에서 "직전 PG status가 DONE이었다(혹은 PG status 미확인)면 FAILED 보상 금지" 또는 outbox에 별도 terminal status(`MANUAL_REVIEW`/`SUSPENDED`)로 격리. 이 가드는 plan scope 안에서 결정해야 할 사항이며 non-goal 배제가 정당화되지 않는다(돈이 새는 경로이므로).
   - severity: critical → 판정 fail.

2. **`restoreForOrders` 멱등성 가드 위치 미결 (Major)**
   - PLAN Task 12 산출물(line 286~287)이 "PaymentTransactionCoordinator 또는 product 컨텍스트 어댑터"로 양다리. Architect 주석(line 263~269)도 동일 지적.
   - 도메인 관점에서는 위치보다 **불변식 자체**가 더 중요: "동일 orderId에 대한 stock restore는 정확히 1회만 실행"이 어디에서 보장되는지 plan이 결정해야 execute 단계에서 이중 복구가 발생하지 않는다. 현재 plan은 테스트(`restoreForOrders_alreadyFailedPayment_isNoOp`)만 명시하고 가드 구현 위치/방법(payment 측 status 가드 vs product 측 idempotency 테이블)은 미정. CANCELED 분기(Task 8)의 보상 호출 경로가 새로 추가되므로, 위치 미결인 채 execute에 들어가면 product 측 재고가 음수까지 갈 수 있다.
   - severity: major.

3. **`PaymentEvent.fail` 이중 호출 / 잘못된 source state (Minor)**
   - PaymentEvent.java line 105~115 확인: `fail`은 READY/IN_PROGRESS/RETRYING만 허용. PLAN Task 8의 CANCELED/ABORTED/EXPIRED 분기는 `executePaymentFailureCompensationWithOutbox`를 호출하는데, 복구 시 `PaymentEvent.status`는 (D2 원칙대로) IN_PROGRESS/RETRYING이라 통과한다. OK. 다만 Task 5에서 `done`을 IN_PROGRESS/RETRYING-only로 좁히는 것과 대칭으로, `fail`도 `READY` 허용을 유지할지 plan에서 명시하지 않음. 도메인 가드 일관성 차원에서 minor.

4. **`getStatusByOrderId` 폴백 호출의 멱등성/타임아웃 처리 부재 (Minor)**
   - PLAN Task 8 `process_alreadyProcessed_getStatusThrows_retryableFallback` 케이스만 있고, "폴백 호출이 timeout으로 길어져 다른 워커가 동일 outbox를 재claim"하는 race window는 plan에서 분석되지 않음. 현재 `claimToInFlight` CAS가 1차 방어이므로 즉각적인 정합성 사고로는 이어지지 않으나, in-flight timeout 대비 getStatus timeout 상한 관계가 plan에 없으면 execute에서 임의 값으로 결정된다. minor.

## Findings
- F-D-1 (critical): DONE+approvedAt=null 레코드가 RETRYABLE budget 소진 시 `executePaymentFailureCompensationWithOutbox`로 흘러 실제 승인된 결제가 FAILED 확정 + 재고 복구되는 money-leak가 PLAN에 의해 의도적으로 열려 있다(Task 8 + 매핑 테이블 D-R2-1).
- F-D-2 (major): Task 12의 `restoreForOrders` 멱등성 가드 위치(caller-guard vs provider-guard)가 plan 종료 시점까지 미결. CANCELED 분기 신설과 결합되면 재고 이중 복구 가능.
- F-D-3 (minor): `PaymentEvent.fail` 허용 source state 일관성이 Task 5/8 사이에서 명시되지 않음.
- F-D-4 (minor): `getStatusByOrderId` 폴백의 timeout 상한 vs in-flight timeout 관계가 plan에 부재.

## JSON
```json
{
  "stage": "plan",
  "persona": "domain-expert",
  "round": 1,
  "task_id": null,
  "decision": "fail",
  "reason_summary": "DONE+approvedAt=null 레코드가 retry budget 소진 경로에서 보상 트랜잭션으로 흘러 실제 승인된 결제가 FAILED+재고복구되는 money-leak가 plan에 의도적으로 열려 있고(critical), restoreForOrders 멱등성 가드 위치도 미결(major)이다.",
  "checklist": {
    "source": "_shared/checklists/plan-ready.md#gate-checklist",
    "items": [
      {
        "section": "domain risk",
        "item": "discuss에서 식별된 domain risk가 각각 대응 태스크를 가짐",
        "status": "yes",
        "evidence": "docs/PAYMENT-DOUBLE-FAULT-RECOVERY-PLAN.md line 293-311 매핑 테이블"
      },
      {
        "section": "domain risk",
        "item": "중복 방지 체크가 필요한 경로에 계획됨",
        "status": "no",
        "evidence": "Task 12 산출물 위치 미결(line 286-287); CANCELED 보상 경로 재고 이중 복구 가드 미확정"
      },
      {
        "section": "domain risk",
        "item": "재시도 안전성 검증 태스크 존재",
        "status": "yes",
        "evidence": "Task 7/8/10/11"
      },
      {
        "section": "domain risk (추가)",
        "item": "money-leak 경로 차단 — 승인된 결제(DONE+null approvedAt)가 보상 트랜잭션으로 흐르지 않음",
        "status": "no",
        "evidence": "Task 8이 해당 케이스를 RETRYABLE로 두고 alert만 남김; budget 소진 시 OutboxProcessingService 기존 분기(line 82-87)가 increaseStockForOrders + markPaymentAsFail 실행"
      }
    ],
    "total": 4,
    "passed": 2,
    "failed": 2,
    "not_applicable": 0
  },
  "scores": {
    "traceability": 0.92,
    "decomposition": 0.78,
    "ordering": 0.85,
    "specificity": 0.72,
    "risk_coverage": 0.55,
    "mean": 0.764
  },
  "findings": [
    {
      "severity": "critical",
      "checklist_item": "money-leak 경로 차단",
      "location": "docs/PAYMENT-DOUBLE-FAULT-RECOVERY-PLAN.md Task 8 + 매핑 테이블 D-R2-1 (line 309)",
      "problem": "DONE+approvedAt=null 응답을 RETRYABLE 버킷에 두고 alert만 남기므로, retry budget 소진 시 OutboxProcessingService의 RETRYABLE_FAILURE → executePaymentFailureCompensationWithOutbox 분기로 흘러들어 실제 승인된(=고객 카드 결제 완료) 건이 PaymentEvent.FAILED 확정 + 재고 복구 처리된다. 돈은 PG에 그대로 남고 시스템은 실패로 인식해 재고만 풀리는 명백한 money-leak.",
      "evidence": "src/main/java/com/hyoguoo/paymentplatform/payment/scheduler/OutboxProcessingService.java line 75-88 (RETRYABLE_FAILURE + isExhausted → executePaymentFailureCompensationWithOutbox); PLAN Task 8 케이스 process_alreadyProcessed_pgStatusDone_nullApprovedAt_incrementsRetryAndAlerts; PLAN line 309 'MANUAL_REVIEW 격리는 non-goal'",
      "suggestion": "DONE+approvedAt=null 분기를 일반 RETRYABLE에서 분리해 outbox terminal status(예: SUSPENDED/MANUAL_REVIEW)로 격리하거나, 최소한 budget 소진 시 'PG status 마지막이 DONE이었다면 보상 금지' 가드를 PaymentTransactionCoordinator 또는 OutboxProcessingService 진입부에 추가하는 태스크를 plan에 명시하라. non-goal 배제는 돈이 새는 경로에서 정당화되지 않는다."
    },
    {
      "severity": "major",
      "checklist_item": "중복 방지 체크가 필요한 경로에 계획됨",
      "location": "docs/PAYMENT-DOUBLE-FAULT-RECOVERY-PLAN.md Task 12 (line 270-287)",
      "problem": "restoreForOrders 멱등성 가드의 수정 위치가 'PaymentTransactionCoordinator 또는 product 어댑터'로 양다리이며, plan 단계에서 결정되지 않았다. CANCELED/ABORTED/EXPIRED 분기가 Task 8로 신설되면 동일 orderId에 대해 보상 트랜잭션이 경로별로 1회 이상 실행될 수 있어 재고가 이중 복구될 위험이 plan 종료 시점까지 남는다.",
      "evidence": "PLAN line 286-287 산출물 양자택일; Architect 주석 line 263-269 동일 지적; 테스트(StockServiceIdempotencyTest)는 provider-guard를 시사하나 산출물 경로는 caller-guard를 가리킴",
      "suggestion": "Task 12를 '단일 컨텍스트 단일 파일' 범위로 재정의: caller-guard(payment 측에서 PaymentEvent.status==FAILED면 ProductPort 호출 자체 skip)와 provider-guard(product 측 orderId 멱등성 테이블) 중 하나를 plan에서 결정. 도메인 관점에서는 caller-guard가 cross-context 영향이 작아 권장."
    },
    {
      "severity": "minor",
      "checklist_item": "task quality / specificity",
      "location": "docs/PAYMENT-DOUBLE-FAULT-RECOVERY-PLAN.md Task 5 + Task 8",
      "problem": "Task 5가 done()을 IN_PROGRESS/RETRYING-only로 좁히는 것과 대칭으로 PaymentEvent.fail의 허용 source state(현재 READY/IN_PROGRESS/RETRYING) 정책을 plan이 명시하지 않아, 복구 보상 경로의 fail 호출 일관성이 execute 단계에서 임의 결정될 수 있다.",
      "evidence": "src/main/java/com/hyoguoo/paymentplatform/payment/domain/PaymentEvent.java line 105-115; PLAN Task 5 fail 관련 명시 없음",
      "suggestion": "Task 5 또는 별도 메모로 fail 허용 source state를 PLAN에 못박고, 복구 보상 경로(IN_PROGRESS/RETRYING) 진입 검증 테스트를 Task 8에 추가."
    },
    {
      "severity": "minor",
      "checklist_item": "task quality / specificity",
      "location": "docs/PAYMENT-DOUBLE-FAULT-RECOVERY-PLAN.md Task 8",
      "problem": "getStatusByOrderId 폴백 호출의 timeout 상한과 in-flight timeout(recoverTimedOutInFlight cutoff) 관계가 plan에 부재. 폴백이 길어지면 동일 outbox가 다른 워커에 의해 재claim될 수 있다(현재 CAS로 1차 방어되긴 함).",
      "evidence": "PLAN Task 8 process_alreadyProcessed_getStatusThrows_retryableFallback 케이스만 존재; timeout 명세 없음",
      "suggestion": "Task 8 또는 §4-3에 'getStatus 호출 timeout < in-flight cutoff' 제약을 명시하고, 통합 테스트에서 timeout 케이스 1건 추가."
    }
  ],
  "previous_round_ref": null,
  "delta": null,
  "unstuck_suggestion": null
}
```
