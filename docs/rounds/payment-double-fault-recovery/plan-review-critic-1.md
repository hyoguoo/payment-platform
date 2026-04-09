# plan-review-critic-1

**Topic**: PAYMENT-DOUBLE-FAULT-RECOVERY
**Round**: 1 (plan-review 독립 게이트)
**Persona**: Critic

## Reasoning
Plan round 2 산출물(docs/PAYMENT-DOUBLE-FAULT-RECOVERY-PLAN.md)을 plan-ready Gate checklist 전 항목으로 독립 재판정했다. 14개 태스크 모두 topic.md 결정 ID(§4-1~§8)에 매핑되고, 교차 참조 테이블(line 360-390)이 F1~F7 / C1~C2 / D1~D5 / F-C-1~6 / F-D-1~4 / D-R2-1~3 전부 orphan 없이 닫는다. layer 의존 순서(port 1 → errorcode 2 → domain 3/4/4.5/5/8a → app 6/7/12 → infra 9 → scheduler 8b → IT 10/11)는 ARCHITECTURE.md hexagonal 규칙과 일치하며, Task 3는 String 기반으로 domain→gateway 역의존이 제거되었고 Task 12는 payment 단일 컨텍스트로 cross-context 모호성이 해소되었다. Task 8a SUSPENDED 격리로 money-leak 경로가 구조적으로 차단되고, Task 5의 fail/done/execute 파라미터화 테스트가 상태 전이 불변식을 고정한다. 모든 tdd=true 태스크가 테스트 클래스+메서드 스펙을, tdd=false 태스크가 산출물 경로를 명시한다. 잔존 지적은 Task 4.5 수정 위치 3-후보(line 114-119)와 Task 12 outbox 이미 FAILED 상태 멱등 케이스 부재 2건뿐이며, 둘 다 execute에서 ripgrep/테스트 1건으로 해소 가능한 minor. critical/major finding 없음.

## Checklist judgement
- traceability / topic 참조: yes — line 3 topic 링크, 교차 참조 테이블 line 360-390
- traceability / orphan 없음: yes — F-C-2 해소용 Task 4.5, D-R2-* 포함 전부 매핑
- task quality / 객관적 완료 기준: yes — 모든 tdd=true 태스크가 테스트 메서드 pass 기준, tdd=false는 파일/시그니처
- task quality / ≤ 2시간: yes — Task 8 → 8a/8b 분할(line 206-274)
- task quality / 관련 소스 파일 언급: yes (minor 1건 — Task 4.5 3-후보)
- TDD / 테스트 클래스+메서드 스펙: yes — Task 3,4,4.5,5,6,7,8a,8b,10,11,12 모두 파일+메서드 명시
- TDD / 산출물 경로 (tdd=false): yes — Task 1,2,9 모두 파일 경로
- TDD / 분류 합리성: yes — domain 불변식/상태전이/edge case 모두 tdd=true
- dependency ordering / layer 순서: yes
- dependency ordering / Fake 선행: n/a — 본 plan에 Fake 구현 태스크 없음 (기존 Fake 재사용)
- dependency ordering / orphan port 없음: yes — Task 1 port → Task 7 소비 → Task 9 구현
- architecture fit / layer 규칙: yes — Task 3 String 기반으로 gateway import 제거(line 72-77)
- architecture fit / port 통한 모듈 호출: yes — Task 12 payment 단일 파일
- architecture fit / Lombok·예외·로깅 컨벤션: yes — PaymentStatusException + LogFmt 키 명시
- artifact: yes — docs/PAYMENT-DOUBLE-FAULT-RECOVERY-PLAN.md 존재(407 라인)

## Findings
1. **minor** — Task 4.5 (line 114-119) 수정 위치가 3개 후보로 명시되고 실제 선택은 execute에 위임. 3개 파일 모두 `PaymentConfirmResultStatus.of(...)`를 호출할 가능성이 plan 시점에 확정되지 않음. 테스트 스펙이 매핑 동작 단위라 회귀 방어는 보장되나, execute 진입 시 ripgrep 1회로 단일 위치를 특정해야 한다.
2. **minor** — Task 12 (line 335-356) Guard 테스트 3건은 `PaymentEvent.status=FAILED` + outbox 생존 케이스를 다루지만, outbox가 이미 terminal(FAILED)인 재진입 경로에서 `PaymentOutbox.toFailed` 도메인 가드가 예외를 던질 가능성에 대한 테스트/명세가 없다. 재claim이 드물어 현 범위에 수용 가능.

## JSON
```json
{
  "stage": "plan-review",
  "persona": "critic",
  "round": 1,
  "task_id": null,
  "decision": "pass",
  "reason_summary": "Plan round 2 산출물이 plan-ready Gate checklist 전 항목을 충족. 14개 태스크 전부 topic 결정에 매핑되고 layer 순서/아키텍처/TDD 스펙/리스크 대응 모두 yes. critical·major 없음, minor 2건(Task 4.5 수정 위치 3-후보, Task 12 outbox terminal 멱등 케이스 부재)만 잔존.",
  "checklist": {
    "source": "_shared/checklists/plan-ready.md#gate-checklist",
    "items": [
      {"section": "traceability", "item": "PLAN.md가 docs/topics/<TOPIC>.md의 결정 사항을 참조", "status": "yes", "evidence": "PLAN.md line 3 topic 링크 + 각 Task의 §4-1~§8 결정 ID"},
      {"section": "traceability", "item": "모든 태스크가 설계 결정에 매핑됨 (orphan 없음)", "status": "yes", "evidence": "line 360-390 교차 참조 테이블 F1~F7/C1~C2/D1~D5/F-C-1~6/F-D-1~4/D-R2-1~3 전부 매핑"},
      {"section": "task quality", "item": "객관적 완료 기준", "status": "yes", "evidence": "tdd=true 태스크 모두 테스트 메서드 pass 기준, tdd=false는 파일/시그니처 명시"},
      {"section": "task quality", "item": "태스크 크기 ≤ 2시간", "status": "yes", "evidence": "Task 8 → 8a(line 206-247)/8b(line 250-274) 분할"},
      {"section": "task quality", "item": "관련 소스 파일/패턴 언급", "status": "yes", "evidence": "Task 12 line 354-356 단일 파일, Task 4.5 line 114-119 후보 3파일(minor 1건)"},
      {"section": "TDD specification", "item": "tdd=true 테스트 클래스+메서드 스펙", "status": "yes", "evidence": "Task 3,4,4.5,5,6,7,8a,8b,10,11,12 모두 파일+메서드 명시"},
      {"section": "TDD specification", "item": "tdd=false 산출물 명시", "status": "yes", "evidence": "Task 1(port), 2(errorcode), 9(Jpa/Impl) 파일 경로 명시"},
      {"section": "TDD specification", "item": "TDD 분류 합리성", "status": "yes", "evidence": "domain 불변식/상태전이/race window 전부 tdd=true"},
      {"section": "dependency ordering", "item": "layer 의존 순서", "status": "yes", "evidence": "port(1)→error(2)→domain(3,4,4.5,5,8a)→app(6,7,12)→infra(9)→scheduler(8b)→IT(10,11)"},
      {"section": "dependency ordering", "item": "Fake가 소비자보다 선행", "status": "n/a", "evidence": "본 plan에 신규 Fake 태스크 없음 — 기존 Fake 재사용"},
      {"section": "dependency ordering", "item": "orphan port 없음", "status": "yes", "evidence": "Task 1 port 추가 → Task 7 소비 → Task 9 구현"},
      {"section": "architecture fit", "item": "ARCHITECTURE.md layer 규칙 준수", "status": "yes", "evidence": "PLAN.md line 72-77 Task 3 String 기반 테스트로 domain→gateway import 제거"},
      {"section": "architecture fit", "item": "모듈 간 호출이 port/InternalReceiver를 통함", "status": "yes", "evidence": "line 337 Task 12 payment 단일 파일, product 컨텍스트 무수정"},
      {"section": "architecture fit", "item": "Lombok/예외/LogFmt 컨벤션", "status": "yes", "evidence": "Task 5/8b PaymentStatusException + DONE_REQUIRES_APPROVED_AT 에러코드, LogFmt 키 명시"},
      {"section": "artifact", "item": "docs/<TOPIC>-PLAN.md 존재", "status": "yes", "evidence": "docs/PAYMENT-DOUBLE-FAULT-RECOVERY-PLAN.md 407라인 존재"}
    ],
    "total": 15,
    "passed": 14,
    "failed": 0,
    "not_applicable": 1
  },
  "scores": {
    "traceability": 0.93,
    "decomposition": 0.9,
    "ordering": 0.92,
    "specificity": 0.86,
    "risk_coverage": 0.93,
    "mean": 0.908
  },
  "findings": [
    {
      "severity": "minor",
      "checklist_item": "각 태스크에 관련 소스 파일/패턴이 언급됨",
      "location": "docs/PAYMENT-DOUBLE-FAULT-RECOVERY-PLAN.md line 114-119 (Task 4.5)",
      "problem": "수정 위치가 3개 후보로 제시되고 실제 단일 위치는 execute 단계에서 특정하도록 위임됨. 3개 파일 모두에 PaymentConfirmResultStatus.of(...) 호출이 존재할 경우 복수 수정 필요성이 plan 시점에 결정되지 않음.",
      "evidence": "line 114 '다음 세 파일 중 매핑이 실제 이루어지는 위치 확인 후 해당 파일 수정'; line 119 'execute 단계에서 위 세 파일 중 실제 호출이 이루어지는 파일을 특정'",
      "suggestion": "execute 진입 직후 ripgrep으로 PaymentConfirmResultStatus.of 호출 위치를 확정하고, 복수 위치이면 Task 4.5를 그 수만큼 재분할하는 가이드 한 줄을 Task 4.5 말미에 추가."
    },
    {
      "severity": "minor",
      "checklist_item": "task quality / 객관적 완료 기준",
      "location": "docs/PAYMENT-DOUBLE-FAULT-RECOVERY-PLAN.md Task 12 line 335-356",
      "problem": "Guard 테스트 3건이 PaymentEvent.status=FAILED + outbox 생존 케이스를 다루지만, outbox가 이미 terminal(FAILED) 상태로 재진입되는 경로에서 PaymentOutbox.toFailed 도메인 가드 예외 가능성에 대한 테스트/명세가 없다.",
      "evidence": "line 343 'PaymentOutbox.toFailed만 수행'; line 349-352 테스트 3건 모두 outbox 상태 불변 케이스 부재",
      "suggestion": "executePaymentFailureCompensation_alreadyFailedEvent_alreadyClosedOutbox_isNoOp 1건 추가 또는 Task 12 설계 메모에 'outbox가 이미 terminal이면 no-op' 불변식 한 줄 명시."
    }
  ],
  "previous_round_ref": null,
  "delta": null,
  "unstuck_suggestion": null
}
```
