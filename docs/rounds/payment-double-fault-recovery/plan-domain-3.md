# plan-domain-3

**Topic**: PAYMENT-DOUBLE-FAULT-RECOVERY
**Round**: 3
**Persona**: Domain Expert

## Reasoning
대안 C(getStatus 선행)는 F1~F7 돈샘 경로를 구조적으로 닫는다. 상태 기반 4분류 헬퍼 + D6 state-sync only 분기 + F-D-1 SUSPENDED 격리 + Task 12 guard-at-caller가 모두 태스크에 1:1 매핑되어 있고, PaymentStatus enum 전 값(DONE/CANCELED/ABORTED/EXPIRED/IN_PROGRESS/WAITING_FOR_DEPOSIT/READY/PARTIAL_CANCELED)을 실제 소스와 교차 확인했다. 남은 리스크는 Task 8 테스트 스펙 한 줄의 내부 모순(minor)뿐이며 판정은 pass.

## Domain risk checklist
- 멱등성 검증 태스크: Task 7/9/10 (CAS), Task 12 (재고 복구 guard), Task 8(`process_canceledSingleCycle_restoreForOrdersCalledOnce_d8`) — OK
- 상태 전이 불변식 테스트: Task 5 (done/execute/fail 전수) — OK
- DONE+approvedAt=null money-leak 차단: Task 4 SUSPENDED + Task 8 budget-exhausted 테스트 — OK
- D6 state-sync only(워커 사망 after done before outbox.toDone): Task 8 + Task 11 — OK
- CANCELED/ABORTED/EXPIRED 분기: Task 3 헬퍼 + Task 8 parameterized + Task 12 guard — OK
- PARTIAL_CANCELED/WAITING_FOR_DEPOSIT/READY pending 분기: Task 8 parameterized + alert — OK
- "결제 없음" 보수적 디폴트(confirm 금지): Task 8 `process_notFoundDefault_skipRetryAlert_noConfirm` — OK
- 재고 복원 guard-at-caller 중복 방지: Task 12 — OK

## 도메인 관점 추가 검토
1. `src/main/java/com/hyoguoo/paymentplatform/payment/domain/dto/enums/PaymentStatus.java:12-17` — PLAN이 분류 헬퍼에 나열한 enum 값이 모두 실제 존재함을 확인.
2. `src/main/java/com/hyoguoo/paymentplatform/payment/application/port/PaymentGatewayPort.java` — `getStatusByOrderId` 메서드가 실제 포트에 존재. 대안 C 진입점 전제 성립.
3. `docs/PAYMENT-DOUBLE-FAULT-RECOVERY-PLAN.md:234` — Task 8 테스트 `process_pgSuccess_nullApprovedAt_domainExceptionPropagates`의 트리거 설명이 "결제 없음 분기의 SUCCESS 응답"인데, L213의 플로우는 "결제 없음" 분기에서 confirm 미호출로 못 박는다. 즉 이 테스트 서술대로 트리거되는 경로가 존재하지 않는다. Task 5의 `done_nullApprovedAt` 도메인 테스트가 F1 safety net을 이미 커버하므로 이 테스트는 삭제하거나, "PG 없음 분기가 아닌 1차 장애 첫 confirm 경로에서의 approvedAt=null 방어"로 문구만 수정하면 된다. **minor — pass 유지**.

## Findings
- minor: Task 8 테스트 스펙 1건이 플로우(conservative default = confirm 금지)와 문구 충돌.

## JSON
```json
{
  "stage": "plan",
  "persona": "domain-expert",
  "round": 3,
  "task_id": null,
  "decision": "pass",
  "reason_summary": "대안 C getStatus 선행 플로우가 F1~F7 및 D6/D8/F-D-1/F-D-2를 태스크에 1:1 매핑으로 닫는다. 플로우와 테스트 스펙 1건의 문구 불일치는 minor.",
  "checklist": {
    "source": "_shared/checklists/plan-ready.md",
    "items": [
      {"section": "domain risk", "item": "discuss에서 식별된 domain risk가 각각 대응 태스크를 가짐", "status": "yes", "evidence": "PLAN.md 334-363 리스크 교차 참조 테이블 F1~F7/D1~D9/F-D-1~3/D-R2-1~3 모두 매핑"},
      {"section": "domain risk", "item": "중복 방지 체크가 필요한 경로에 계획됨", "status": "yes", "evidence": "Task 12 guard-at-caller(PaymentEvent.status==FAILED skip), Task 7/9/10 CAS 중복 복구 방지"},
      {"section": "domain risk", "item": "재시도 안전성 검증 태스크 존재", "status": "yes", "evidence": "Task 10 PaymentOutboxUseCaseConcurrentRecoverIT 동시성 테스트, Task 11 end-to-end"},
      {"section": "task quality", "item": "Task 8 테스트 스펙 일관성", "status": "no", "evidence": "PLAN.md:234 process_pgSuccess_nullApprovedAt_domainExceptionPropagates의 '결제 없음 분기의 SUCCESS 응답' 설명이 L213 conservative default(confirm 금지)와 충돌"}
    ],
    "total": 4,
    "passed": 3,
    "failed": 1,
    "not_applicable": 0
  },
  "scores": {
    "traceability": 0.95,
    "decomposition": 0.88,
    "ordering": 0.92,
    "specificity": 0.88,
    "risk_coverage": 0.94,
    "mean": 0.914
  },
  "findings": [
    {
      "severity": "minor",
      "checklist_item": "Task 8 테스트 스펙 일관성",
      "location": "docs/PAYMENT-DOUBLE-FAULT-RECOVERY-PLAN.md:234",
      "problem": "'결제 없음 분기의 SUCCESS 응답 + approvedAt null'로 트리거를 설명하지만, 같은 Task 8 플로우(L213)는 결제 없음 분기에서 confirm을 절대 호출하지 않는 보수적 디폴트로 확정했기 때문에 이 트리거 경로는 플로우상 존재하지 않는다.",
      "evidence": "PLAN.md L213 '\"결제 없음\"(보수적 디폴트): skip + incrementRetryOrFail + alert [§11-7 실험 전 confirm 금지]' vs L234 'confirm 경로(결제 없음 분기의 SUCCESS 응답)'",
      "suggestion": "해당 테스트를 삭제(Task 5의 done_nullApprovedAt가 F1 safety net을 이미 커버)하거나, 서술을 '1차 장애 첫 confirm 경로에서 PG가 SUCCESS+approvedAt=null을 반환하는 회귀 방어'로 수정."
    }
  ],
  "previous_round_ref": "plan-domain-2.md",
  "delta": {
    "newly_passed": [
      "getStatus 선행으로 F7 완전 차단",
      "D6 state-sync only 분기가 Task 8/11에 매핑",
      "D8 CANCELED 멱등 회귀 테스트 추가",
      "D9 '결제 없음' 보수적 디폴트 확정 및 테스트 매핑"
    ],
    "newly_failed": [],
    "still_failing": []
  },
  "unstuck_suggestion": null
}
```
