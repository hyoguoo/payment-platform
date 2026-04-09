# plan-domain-2

**Topic**: PAYMENT-DOUBLE-FAULT-RECOVERY
**Round**: 2
**Persona**: Domain Expert

## Reasoning
Round 2 PLAN이 round 1의 domain findings 4건(F-D-1~4)을 정면으로 다룬다. 특히 money-leak 경로(F-D-1)는 Task 8a의 `PaymentOutbox.SUSPENDED` 신규 terminal 격리 + "PaymentEvent 상태 불변 + 보상 경로 금지" 불변식 + Task 8b의 `process_alreadyProcessed_pgStatusDone_nullApprovedAt_budgetExhausted_suspends` 테스트로 3단 방어가 구성됐다. F-D-2(재고 이중 복구)는 Task 12를 "Guard-at-caller 단일 파일"로 명확히 못박고 `PaymentEvent.status==FAILED`일 때 `ProductPort.increaseStockForOrders` 0회 검증 테스트까지 명시했다. F-D-3은 Task 5 fail source state 파라미터화 테스트로, F-D-4는 non-goal 스코프 한정으로 처리. 잔존 도메인 리스크는 minor 수준이다.

## Domain risk checklist
- [x] discuss에서 식별된 domain risk가 각각 대응 태스크를 가짐 — line 362~390 교차 참조 테이블, F1~F7/D1~D5/F-C-*/F-D-*/D-R2-* 전부 태스크 매핑
- [x] 중복 방지 체크가 필요한 경로에 계획됨 — Task 7/9/10 CAS, Task 12 Guard-at-caller(단일 파일·단일 불변식 확정), Task 8a SUSPENDED 격리로 보상 경로 원천 차단
- [x] 재시도 안전성 검증 태스크 존재 — Task 7/8b/10/11
- [x] money-leak 경로 차단 — Task 8a `toSuspended(DONE_NULL_APPROVED_AT)` + PaymentEvent 불변 + Task 8b 전용 테스트

## 도메인 관점 추가 검토
1. **F-D-1 해소 확인 — SUSPENDED 격리 일관성**
   - Task 8a line 215~218: "`DONE+approvedAt=null` budget 소진 시 `PaymentOutbox.toSuspended` + `PaymentEvent` 상태 변경하지 않음 + `executePaymentFailureCompensationWithOutbox` 절대 호출 안 함" 불변식이 명시됨. Task 8b 테스트 `process_alreadyProcessed_pgStatusDone_nullApprovedAt_budgetExhausted_suspends`가 이 불변식을 회귀로 묶는다. `OutboxProcessingService` line 75~88의 기존 RETRYABLE+isExhausted 분기와 다른 우선순위 경로(`isDoneWithNullApprovedAt`)로 먼저 갈래가 나뉘므로 money-leak 경로가 닫혔다. 도메인 관점 critical 해소.
   - 다만 Task 8b 테스트 목록에 "일반 RETRYABLE budget 소진 경로는 기존대로 FAILED 확정"(`process_retryBudgetExhausted_marksOutboxAndEventFailed`)도 유지되어 있어, 분기 우선순위(already_processed+DONE+null이 먼저, generic retryable은 그 다음)가 구현 시점에 한 곳에서 명시적으로 정렬되어야 한다. 이것은 설계가 아닌 execute 단계 주의사항으로 minor.

2. **F-D-2 해소 확인 — Guard-at-caller 불변식**
   - Task 12 line 337~344: "PaymentTransactionCoordinator 단일 파일만 수정, product 컨텍스트 비수정, `event.getStatus()==FAILED`면 `ProductPort.increaseStockForOrders` + `PaymentEvent.fail` skip, `PaymentOutbox.toFailed`만 수행". PaymentEvent.java line 105~115의 `fail` allowed source state와 정면 충돌하지 않는다(FAILED면 아예 재호출 안 함). caller-guard 선택도 cross-context 영향이 작고 도메인 관점에서 권장한 방향과 일치.
   - 한 가지 잔존 질문: Task 12 skip path가 `PaymentOutbox.toFailed(...)`를 호출할 때 이미 `PaymentOutbox.status==FAILED`인 경우(중복 진입)에 대한 멱등 테스트가 없다. PaymentOutbox.toFailed의 현재 source state 가드에 따라 예외가 날 수 있다. minor.

3. **F-D-3 해소 확인**
   - Task 5 line 154~155: `fail_allowedSourceStates_succeeds`(READY/IN_PROGRESS/RETRYING), `fail_invalidSourceStates_throwsPaymentStatusException`(DONE/FAILED/EXPIRED). 기존 PaymentEvent.java line 105~115 정책을 테스트로 고정. 해소.

4. **F-D-4 해소 확인**
   - Task 8b에 `process_alreadyProcessed_getStatusThrows_retryableFallback` 유지. timeout 상한 ↔ in-flight cutoff 관계는 line 405 non-goal 이월. "기존 HTTP client timeout 설정 확인" 범위로 한정. 도메인 관점에서 수용 가능(현재 CAS가 1차 방어이므로 즉시 money-risk 아님). 해소.

5. **새로 발견된 minor: `PaymentOutboxStatus`에 `SUSPENDED` 추가로 인한 쿼리 side-effect**
   - Task 8a가 `PaymentOutbox.toSuspended` + `SUSPENDED` 상태를 도입하는데, 기존 `findPendingBatch`/`recoverTimedOutInFlight`/`claimToInFlight` CAS 쿼리들이 `status='PENDING'`/`'IN_FLIGHT'`만 본다면 SUSPENDED 레코드는 자동으로 재처리 풀에서 제외된다(좋음). 다만 운영 수동 복구(line 217) 경로가 태스크로 명시되어 있지 않아, "SUSPENDED → PENDING 되돌리기" 절차가 execute 완료 후 orphan 상태일 수 있다. plan non-goal로 이월한다는 명시가 없다. minor.

6. **WAITING_FOR_DEPOSIT/PARTIAL_CANCELED 경로 존재 확인**
   - Task 8a `isStillPending()`이 IN_PROGRESS/WAITING_FOR_DEPOSIT/READY/PARTIAL_CANCELED를 포함하고, Task 8b `process_alreadyProcessed_pgStatusStillPending_skipAndIncrementRetry`가 `@ParameterizedTest`로 4개 모두 커버. D-R2-2(WAITING_FOR_DEPOSIT 계속 RETRYABLE) 매핑도 line 389에 명시. OK.

## Findings
- F-D-R2-1 (minor): Task 12 skip path에서 `PaymentOutbox`가 이미 `FAILED` 상태일 경우의 멱등 처리(재전이 허용 or no-op) 테스트가 없다. 동일 outbox가 재claim되는 극히 드문 경로에서 `PaymentOutbox.toFailed` 가드가 예외를 던지면 복구가 막힐 수 있다.
- F-D-R2-2 (minor): `PaymentOutbox.SUSPENDED` 격리 후 수동 복구 절차(조회 쿼리, SUSPENDED→PENDING 되돌리기)가 태스크로도, non-goal 이월로도 명시되지 않았다. execute 종료 후 운영 사각지대로 남을 수 있다.

## JSON
```json
{
  "stage": "plan",
  "persona": "domain-expert",
  "round": 2,
  "task_id": null,
  "decision": "pass",
  "reason_summary": "Round 1 domain findings F-D-1(money-leak)~F-D-4 모두 Task 8a/8b/12/5 및 non-goal 이월로 해소됐다. SUSPENDED 격리, Guard-at-caller 단일화, fail source state 파라미터화 테스트가 모두 구조적으로 잠겨 있다. 잔존 항목은 모두 minor.",
  "checklist": {
    "source": "_shared/checklists/plan-ready.md#gate-checklist",
    "items": [
      {
        "section": "domain risk",
        "item": "discuss에서 식별된 domain risk가 각각 대응 태스크를 가짐",
        "status": "yes",
        "evidence": "docs/PAYMENT-DOUBLE-FAULT-RECOVERY-PLAN.md line 362-390 교차 참조 테이블 F1~F7/D1~D5/F-C-*/F-D-*/D-R2-* 전부 매핑"
      },
      {
        "section": "domain risk",
        "item": "중복 방지 체크가 필요한 경로에 계획됨",
        "status": "yes",
        "evidence": "Task 7/9/10 CAS, Task 12 Guard-at-caller 단일 파일(line 337-344), Task 8a SUSPENDED 격리(line 215-218)"
      },
      {
        "section": "domain risk",
        "item": "재시도 안전성 검증 태스크 존재",
        "status": "yes",
        "evidence": "Task 7/8b/10/11"
      },
      {
        "section": "domain risk (추가)",
        "item": "money-leak 경로 차단 — 승인된 결제(DONE+null approvedAt)가 보상 트랜잭션으로 흐르지 않음",
        "status": "yes",
        "evidence": "Task 8a PaymentOutbox.toSuspended + PaymentEvent 불변 불변식(line 215-218); Task 8b process_alreadyProcessed_pgStatusDone_nullApprovedAt_budgetExhausted_suspends 테스트(line 265)"
      }
    ],
    "total": 4,
    "passed": 4,
    "failed": 0,
    "not_applicable": 0
  },
  "scores": {
    "traceability": 0.95,
    "decomposition": 0.9,
    "ordering": 0.88,
    "specificity": 0.9,
    "risk_coverage": 0.9,
    "mean": 0.906
  },
  "findings": [
    {
      "severity": "minor",
      "checklist_item": "task quality / specificity",
      "location": "docs/PAYMENT-DOUBLE-FAULT-RECOVERY-PLAN.md Task 12 (line 335-356)",
      "problem": "Guard-at-caller skip path가 'PaymentOutbox.toFailed만 수행'을 명시하지만, outbox가 이미 FAILED 상태로 재진입된 극소 경로에서 toFailed 도메인 가드가 예외를 던질 가능성에 대한 테스트/명세가 없다.",
      "evidence": "PLAN line 343 'PaymentOutbox.toFailed만 수행'; Task 12 테스트 3개는 outbox 상태 불변 케이스 없음",
      "suggestion": "executePaymentFailureCompensation_alreadyFailedEvent_alreadyClosedOutbox_isNoOp 케이스 1건 추가 또는 Task 12 설계 메모에 'outbox가 이미 terminal이면 no-op' 명시."
    },
    {
      "severity": "minor",
      "checklist_item": "task quality / specificity",
      "location": "docs/PAYMENT-DOUBLE-FAULT-RECOVERY-PLAN.md Task 8a (line 206-247) + plan 이월 항목(line 394-406)",
      "problem": "PaymentOutbox.SUSPENDED 레코드의 운영 수동 복구 절차(조회 쿼리, SUSPENDED→PENDING 되돌리기)가 태스크로도, non-goal 이월로도 명시되지 않았다. line 217 'status=SUSPENDED 조회 쿼리로 운영 수동 복구 대상'이라고만 언급돼 실제 조회 경로/복구 API가 미정.",
      "evidence": "PLAN line 217; 이월 테이블 line 394-406에 해당 항목 없음",
      "suggestion": "plan 이월 항목 표에 'SUSPENDED 수동 복구 콘솔/쿼리 — non-goal, 후속 토픽' 한 줄을 추가하거나, 최소한 Task 8a 설계 결정 말미에 '수동 복구 UI는 plan 범위 밖'이라고 명시."
    }
  ],
  "previous_round_ref": "docs/rounds/payment-double-fault-recovery/plan-domain-1.md",
  "delta": {
    "round_1_findings": 4,
    "resolved": [
      "F-D-1 critical money-leak — Task 8a SUSPENDED 격리 + Task 8b 전용 테스트로 해소",
      "F-D-2 major restoreForOrders 가드 위치 미결 — Task 12 Guard-at-caller 단일 파일로 확정",
      "F-D-3 minor fail source state 미명시 — Task 5 fail_allowedSourceStates/fail_invalidSourceStates 파라미터화 테스트",
      "F-D-4 minor getStatus timeout 상한 — non-goal 이월 명시(line 405)"
    ],
    "new_findings": 2,
    "severity_trend": "critical(1)+major(1)+minor(2) → minor(2)"
  },
  "unstuck_suggestion": null
}
```
