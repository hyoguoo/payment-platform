# plan-critic-1

**Topic**: PAYMENT-DOUBLE-FAULT-RECOVERY
**Round**: 1
**Persona**: Critic

## Reasoning

Plan은 discuss 결정 D1~D12 전수를 11개 태스크로 분해했고, layer 의존 순서를 올바르게 배치했다. 그러나 Architect 인라인 주석이 명시적으로 지적한 두 가지 변경 파일 누락이 해소되지 않은 채 본문에 남아 있으며, Task 4의 `RecoveryDecision.from()` 시그니처가 설명/테스트 간 불일치 상태다. 이들은 실행 단계에서 layer 위반이나 시그니처 재설계를 유발할 수 있는 major 수준 결함이다.

## Checklist judgement

### traceability (추적성)

- [x] PLAN.md가 `docs/topics/PAYMENT-DOUBLE-FAULT-RECOVERY.md`의 결정 사항을 참조함 — yes. 개요에서 topic.md 링크, 각 태스크에 결정 ID 참조, 하단 교차 참조 표에서 D1~D12 + discuss-domain-2 minor 1~3 전수 매핑.
- [x] 모든 태스크가 설계 결정 중 하나 이상에 매핑됨 (orphan 태스크 없음) — yes. 리스크 교차 참조 표에서 11개 태스크 전부 매핑 확인.

### task quality (태스크 품질)

- [x] 모든 태스크가 객관적 완료 기준을 가짐 — yes. 각 태스크에 체크박스 형태의 완료 조건 + `./gradlew test` 통과 포함.
- [x] 태스크 크기 <= 2시간 — yes. 가장 큰 Task 9도 기존 파일 재작성 + mock 테스트 확장 수준으로 2시간 이내 추정.
- [ ] 각 태스크에 관련 소스 파일/패턴이 언급됨 — **no**. Task 9의 변경 파일 목록이 `OutboxProcessingService.java` 단 1개만 기재. Architect 주석이 `PaymentCommandUseCase`(또는 별도 use case)에 `getStatusByOrderId` 위임 메서드 추가가 필요하다고 명시했으나 반영되지 않음. Task 6도 `PaymentTransactionCoordinator` 생성자 파라미터 변경(PaymentLoadUseCase 주입)이 누락.

### TDD specification (TDD 명세)

- [x] `tdd=true` 태스크는 테스트 클래스 + 테스트 메서드 스펙이 명시됨 — yes. Task 1~7, 9 모두 테스트 클래스/메서드 명시.
- [x] `tdd=false` 태스크는 산출물(파일/위치)이 명시됨 — yes. Task 8, 10, 11 모두 산출물 위치 명시.
- [x] TDD 분류가 합리적 — yes. business logic/state machine은 tdd=true, infrastructure 정비/migration/metric은 tdd=false.

### dependency ordering (의존 순서)

- [x] layer 의존 순서 준수 — yes. domain(1,2,3,7,4) -> application(5,6) -> infrastructure(8,10) -> scheduler(9) -> cross-cutting(11).
- [x] Fake 구현이 그것을 소비하는 태스크보다 먼저 옴 — n/a. 신규 Fake 없음. 기존 mock 활용.
- [x] orphan port 없음 — yes. 신규 port 추가 없음(`PaymentGatewayPort.getStatusByOrderId` 재사용).

### architecture fit (아키텍처 적합성)

- [ ] `docs/context/ARCHITECTURE.md`의 layer 규칙과 충돌 없음 — **no**. Task 9 설명에서 `OutboxProcessingService`(scheduler)가 `getStatusByOrderId`를 호출하는 경로에 대해 Architect가 "직접 PaymentGatewayPort를 scheduler에 주입하면 hexagonal 규칙 위반"이라고 명시적으로 지적했지만, PLAN 본문의 변경 파일/설명에는 use-case 위임 메서드 추가가 반영되지 않았다. 실행 시 layer 위반 또는 ad-hoc 설계 결정이 필요해진다.
- [x] 모듈 간 호출이 port / InternalReceiver를 통함 — yes.
- [x] `docs/context/CONVENTIONS.md`의 Lombok / 예외 / 로깅 패턴을 따르도록 계획됨 — yes. 예외는 기존 PaymentErrorCode 패턴 준수.

### artifact (산출물)

- [x] `docs/PAYMENT-DOUBLE-FAULT-RECOVERY-PLAN.md` 존재 — yes.

## Findings

### F1
- **severity**: major
- **checklist_item**: 각 태스크에 관련 소스 파일/패턴이 언급됨
- **location**: docs/PAYMENT-DOUBLE-FAULT-RECOVERY-PLAN.md, Task 9 (line 396)
- **problem**: Task 9의 변경 파일 목록이 `OutboxProcessingService.java` 1개만 기재. `OutboxProcessingService`(scheduler)가 `PaymentGatewayPort.getStatusByOrderId`를 호출하려면 application use-case에 위임 메서드가 필요한데, 해당 파일이 누락되었다.
- **evidence**: Architect 인라인 주석(line 372): "PaymentCommandUseCase 또는 PaymentLoadUseCase에 getStatusByOrderId 위임 메서드를 추가하는 것이 올바른 배치다. 변경 파일 목록에 [...] 추가를 명시할 것." 또한 line 394: "변경 파일 누락 — PaymentCommandUseCase.java(또는 새 use case)에 getStatusByOrderId 위임 메서드 추가가 필요하다."
- **suggestion**: Task 9 변경 파일에 `PaymentCommandUseCase.java` 또는 별도 use-case 클래스를 추가하고, `getStatusByOrderId` 위임 메서드의 시그니처를 명시할 것.

### F2
- **severity**: major
- **checklist_item**: ARCHITECTURE.md의 layer 규칙과 충돌 없음
- **location**: docs/PAYMENT-DOUBLE-FAULT-RECOVERY-PLAN.md, Task 9 (line 370-397)
- **problem**: scheduler 계층에서 application/port의 `PaymentGatewayPort`를 직접 주입해야 하는 구조가 되었으나, 이를 해소할 use-case 위임 경로가 계획에 명시되지 않아 hexagonal layer 규칙("scheduler는 application use-case만 의존")과 충돌 가능성이 열려 있다.
- **evidence**: ARCHITECTURE.md line 87: "Depends on: `application` use-case services directly" (scheduler의 의존 대상). Architect 인라인 주석(line 372): "직접 PaymentGatewayPort를 scheduler에 주입하면 scheduler -> application/port 의존이 생겨 hexagonal 규칙을 위반한다."
- **suggestion**: F1과 동일. use-case 위임 메서드를 변경 파일에 추가하여 layer 규칙 준수 경로를 확정할 것.

### F3
- **severity**: major
- **checklist_item**: 각 태스크에 관련 소스 파일/패턴이 언급됨
- **location**: docs/PAYMENT-DOUBLE-FAULT-RECOVERY-PLAN.md, Task 4 (line 152-153)
- **problem**: `RecoveryDecision.from()` 시그니처가 설명(`from(PaymentEvent, PaymentStatusResult)`)과 테스트 스펙(retryCount, maxAttempts, exception type 조건 포함) 간 불일치. Architect가 "(1) 정상 응답 경로: from(PaymentEvent, PaymentStatusResult, int retryCount, int maxAttempts), (2) 예외 경로: fromException(...)" 등 오버로드가 필요하다고 지적했지만, 본문에는 "구현 시점에 시그니처를 확정할 것"으로 유보되었다.
- **evidence**: Task 4 본문(line 153): "RecoveryDecision.from(PaymentEvent, PaymentStatusResult)" / Architect 주석(line 152): "실제 팩토리는 [...] 오버로드가 필요하다. 구현 시점에 시그니처를 확정할 것." / 테스트 스펙(line 196-213): `from_RetryableException_UnderLimit_RetryLater()` 등 예외 기반 테스트가 존재하나 `from()` 시그니처에 예외 타입 파라미터가 없음.
- **suggestion**: `RecoveryDecision`의 정적 팩토리 메서드 시그니처를 확정할 것. 정상 경로와 예외 경로를 분리하든, 통합 파라미터 객체를 쓰든, plan 단계에서 결정해야 테스트 스펙의 구체성이 보장된다.

## JSON

```json
{
  "stage": "plan",
  "persona": "critic",
  "round": 1,
  "task_id": null,

  "decision": "revise",
  "reason_summary": "Architect 인라인 주석이 명시한 변경 파일 누락(Task 9 getStatusByOrderId 위임 use-case)과 RecoveryDecision.from() 시그니처 미확정이 해소되지 않아 major finding 3건 발생. critical은 없으므로 revise 판정.",

  "checklist": {
    "source": "_shared/checklists/plan-ready.md",
    "items": [
      {
        "section": "task quality",
        "item": "각 태스크에 관련 소스 파일/패턴이 언급됨",
        "status": "no",
        "evidence": "Task 9 변경 파일이 OutboxProcessingService.java 1개만 기재. getStatusByOrderId 위임 use-case 파일 누락. Task 6도 생성자 변경 미명시."
      },
      {
        "section": "architecture fit",
        "item": "ARCHITECTURE.md의 layer 규칙과 충돌 없음",
        "status": "no",
        "evidence": "Task 9에서 scheduler -> PaymentGatewayPort 직접 의존 가능성이 열려 있음. use-case 위임 경로 미명시."
      }
    ],
    "total": 16,
    "passed": 14,
    "failed": 2,
    "not_applicable": 0
  },

  "scores": {
    "traceability": 0.95,
    "decomposition": 0.80,
    "ordering": 0.90,
    "specificity": 0.65,
    "risk_coverage": 0.88,
    "mean": 0.84
  },

  "findings": [
    {
      "severity": "major",
      "checklist_item": "각 태스크에 관련 소스 파일/패턴이 언급됨",
      "location": "docs/PAYMENT-DOUBLE-FAULT-RECOVERY-PLAN.md, Task 9 (line 396)",
      "problem": "Task 9 변경 파일이 OutboxProcessingService.java 1개만 기재. getStatusByOrderId 호출을 위한 application use-case 위임 메서드 파일이 누락.",
      "evidence": "Architect 인라인 주석(line 372, 394)이 PaymentCommandUseCase 또는 별도 use-case에 위임 메서드 추가를 명시적으로 요구.",
      "suggestion": "Task 9 변경 파일에 PaymentCommandUseCase.java(또는 별도 use-case)를 추가하고 getStatusByOrderId 위임 메서드 시그니처를 명시."
    },
    {
      "severity": "major",
      "checklist_item": "ARCHITECTURE.md의 layer 규칙과 충돌 없음",
      "location": "docs/PAYMENT-DOUBLE-FAULT-RECOVERY-PLAN.md, Task 9 (line 370-397)",
      "problem": "scheduler -> PaymentGatewayPort 직접 의존 가능성이 열려 있어 hexagonal layer 규칙과 충돌 위험.",
      "evidence": "ARCHITECTURE.md: scheduler depends on application use-case services directly. Architect 주석(line 372): 직접 port 주입은 hexagonal 규칙 위반.",
      "suggestion": "use-case 위임 경로를 변경 파일에 명시하여 layer 규칙 준수를 계획 단계에서 확정."
    },
    {
      "severity": "major",
      "checklist_item": "각 태스크에 관련 소스 파일/패턴이 언급됨",
      "location": "docs/PAYMENT-DOUBLE-FAULT-RECOVERY-PLAN.md, Task 4 (line 152-153)",
      "problem": "RecoveryDecision.from() 시그니처가 설명(2-param)과 테스트 스펙(retryCount/maxAttempts/exception type 필요) 간 불일치. '구현 시점 확정'으로 유보됨.",
      "evidence": "본문: from(PaymentEvent, PaymentStatusResult). Architect 주석: 오버로드 또는 4+ param 필요. 테스트: from_RetryableException_UnderLimit 등 예외 경로 존재.",
      "suggestion": "정적 팩토리 시그니처를 plan에서 확정. 정상 경로/예외 경로 분리 또는 통합 파라미터 객체 중 선택하고, 테스트 스펙에 입력 파라미터를 구체적으로 명시."
    }
  ],

  "previous_round_ref": null,
  "delta": null,

  "unstuck_suggestion": null
}
```
