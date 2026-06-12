# plan-review-1

**Topic**: PG-CONFIRM-LISTENER-SPLIT
**Round**: 1
**Persona**: Plan Reviewer

## Reasoning

PLAN.md는 16개 태스크 전체에 걸쳐 필수 필드(완료 기준, 소스 파일, tdd 플래그, domain_risk 플래그)가 완전히 명시되어 있고, §1.1~§1.9 + §7 acceptance 결정 ID가 모두 태스크와 매핑된 인벤토리 표를 포함하고 있다. 레이어 의존 그래프에서 PCS-7(application service) 테스트가 PCS-10(PgInboxChannel)에 컴파일 의존하나 PCS-7이 PCS-10보다 선행하는 순서 역전이 1건 확인되었으나 이는 minor 수준이다.

## Checklist judgement

### traceability (추적성)

- PLAN.md가 `docs/topics/<TOPIC>.md`의 결정 사항을 참조함: **yes**
  - PLAN.md line 3: `> 토픽: [docs/topics/PG-CONFIRM-LISTENER-SPLIT.md](topics/PG-CONFIRM-LISTENER-SPLIT.md)` 명시
  - "핵심 결정 → Task 매핑" 표(PLAN.md line 73~86): §1.1~§1.9 + §7 전항목 태스크 매핑
- 모든 태스크가 설계 결정 중 하나 이상에 매핑됨 (orphan 태스크 없음): **yes**
  - 16개 태스크 각각 "목적" 필드에 §1.x 또는 §3/§4.4/§7 참조 명시

### task quality (태스크 품질)

- 모든 태스크가 객관적 완료 기준을 가짐: **yes**
  - tdd=true: 테스트 클래스 + 메서드 + 검증 내용 표 명시 (PCS-2, 4, 6, 7, 8, 9, 10, 11, 12, 13, 15)
  - tdd=false: 산출물 파일 경로 명시 (PCS-1, 3, 5, 14, 16)
- 태스크 크기 ≤ 2시간: **yes**
  - 최대 규모 PCS-9도 단일 서비스 클래스 3개 갱신 + 10개 테스트 메서드로 한 커밋 단위 분해 가능
- 각 태스크에 관련 소스 파일/패턴이 언급됨: **yes**
  - 전 태스크 "산출물" 섹션에 패키지 경로 포함 파일 목록 명시

### TDD specification (TDD 명세)

- tdd=true 태스크는 테스트 클래스 + 메서드 스펙 명시됨: **yes**
  - PCS-2, 4, 6~13, 15 모두 테스트 클래스명 + 메서드명 + 검증 내용 표 완비
- tdd=false 태스크는 산출물(파일/위치)이 명시됨: **yes**
  - PCS-1 (SQL 파일), PCS-3 (인터페이스 파일), PCS-5 (record + interface 파일), PCS-14 (yml + enum 파일), PCS-16 (위키 + 영구 문서 목록)
- TDD 분류가 합리적: **yes**
  - 상태 전이(PCS-2), SKIP LOCKED race(PCS-4), TX 경계(PCS-6,7), 멱등성(PCS-8,9), 채널 메트릭(PCS-10), AFTER_COMMIT(PCS-11), SmartLifecycle(PCS-12), 좀비 폴링(PCS-13), 통합(PCS-15) — 모두 tdd=true

### dependency ordering (의존 순서)

- layer 의존 순서 준수: **yes**
  - domain(PCS-1,2) → port(PCS-3) → infra-repo(PCS-4) → application-event+port-in(PCS-5) → application-service(PCS-6~9) → infra-channel(PCS-10) → infra-listener(PCS-11) → infra-scheduler(PCS-12,13) → config(PCS-14) → test(PCS-15) → doc(PCS-16)
- Fake 구현이 그것을 소비하는 태스크보다 먼저 옴: **yes**
  - FakePgInboxRepository 갱신이 PCS-9에 포함. PCS-2~8 단위 테스트는 Mockito mock 사용(도메인/service 단위 테스트에 Fake 불필요)하므로 순서 위반 없음
- orphan port 없음: **yes**
  - PgInboxProcessUseCase(PCS-5 선언) → PgInboxProcessor(PCS-8 구현) → PgInboxImmediateWorker/PgInboxPollingWorker(PCS-12/13 소비) 완전한 체인 존재

**minor 이슈**: PCS-7 테스트 `insertPendingAndPublish_afterCommitListenerFires`가 `PgInboxChannel.size()` 직접 참조 — PCS-10(PgInboxChannel 클래스 신규) 완료 전 해당 테스트 작성 시 컴파일 불가. 의존 그래프는 PCS-7 → ... → PCS-10 순서로 PCS-7이 선행.

### architecture fit (아키텍처 적합성)

- `ARCHITECTURE.md` layer 규칙과 충돌 없음: **yes**
  - infrastructure/listener (AFTER_COMMIT 핸들러), infrastructure/scheduler (VT 워커), application/port, domain/event 위치 모두 hexagonal 규칙 준수
  - PCS-5 주석에서 거울 위치 이슈(domain/event vs application/event) 이미 인지하고 (a)안(domain/event 통일)으로 봉인 — plan 라운드 흡수 완료
- 모듈 간 호출이 port / InternalReceiver를 통함: **yes**
  - InboxReadyEventHandler → PgInboxChannel(infra) → PgInboxImmediateWorker → PgInboxProcessUseCase(port.in) → PgInboxProcessor 경로 port 경유
- Lombok/예외/로깅 패턴 따르도록 계획됨: **n/a**
  - PLAN에서 직접 코드 스타일 지시 없으나 위반 지시도 없음. 심층 판정은 plan 라운드 범위 외

### artifact (산출물)

- `docs/PG-CONFIRM-LISTENER-SPLIT-PLAN.md` 존재: **yes**
  - 파일 실제 확인됨

### domain risk (Domain Expert 전용)

- discuss에서 식별된 domain risk가 각각 대응 태스크를 가짐: **yes**
  - listener TX 경계 → PCS-7; SKIP LOCKED atomicity → PCS-4; 보정 경로 무한 루프 → PCS-9; 멱등성/ALREADY_PROCESSED 재진입 → PCS-8; AFTER_COMMIT 미등록 → PCS-7; 좀비 폴링 회수 정합 → PCS-13
- 중복 방지 체크가 필요한 경로에 계획됨: **yes**
  - `insertPending` orderId UNIQUE 충돌 반환(PCS-3,4) + `transitPendingToInProgress` SKIP LOCKED(PCS-4)
- 재시도 안전성 검증 태스크 존재: **yes**
  - PCS-8 `processInProgressZombie_vendorReturnsAlreadyProcessed_delegatesToDuplicateApprovalHandler` + PCS-13 `poll_processingException_incrementsZombieCounter_continues`

## Findings

### F-01

- **id**: F-01
- **severity**: minor
- **checklist_item**: layer 의존 순서 준수 (Fake 구현이 그것을 소비하는 태스크보다 먼저 옴)
- **location**: `docs/PG-CONFIRM-LISTENER-SPLIT-PLAN.md` PCS-7 테스트 섹션 (line 283) + 레이어 의존 그래프 (line 528)
- **problem**: PCS-7 테스트 `insertPendingAndPublish_afterCommitListenerFires`가 `PgInboxChannel.size()` 직접 참조하나, `PgInboxChannel` 클래스는 PCS-10에서 신규 생성된다. 의존 그래프에서 PCS-7 → PCS-8 → PCS-9 → PCS-10 순서이므로 PCS-7 테스트 작성 시점에 `PgInboxChannel` 클래스가 아직 존재하지 않아 컴파일 불가.
- **evidence**: PLAN.md line 283: `PgInboxChannel.size()` 가 1 이상으로 증가하거나 handler 호출 확인; PLAN.md 의존 그래프 line 534: `PCS-9 → PCS-10 (infra: InboxJob + PgInboxChannel)`
- **suggestion**: PCS-7 해당 테스트를 `PgInboxChannel` 직접 참조 대신 `@TransactionalEventListener(AFTER_COMMIT)` Mock 핸들러 호출 여부로 대체하거나, 해당 테스트를 PCS-11(InboxReadyEventHandlerTest)로 이동. 또는 PCS-10을 PCS-7 앞으로 이동(단, domain → port → infra 역전 우려 있음). Implementer가 TDD 진행 시 mock으로 대체하는 방향이 가장 영향 범위 적음.

## JSON

```json
{
  "stage": "plan-review",
  "persona": "plan-reviewer",
  "round": 1,
  "task_id": null,

  "decision": "pass",
  "reason_summary": "Gate checklist 전 항목 yes/n/a — critical/major finding 없음. PCS-7 테스트의 PCS-10 컴파일 의존 순서 역전이 minor 1건 확인되었으나 판정 기준상 pass.",

  "checklist": {
    "source": "_shared/checklists/plan-ready.md",
    "items": [
      {
        "section": "traceability",
        "item": "PLAN.md가 docs/topics/<TOPIC>.md의 결정 사항을 참조함",
        "status": "yes",
        "evidence": "PLAN.md line 3: 토픽 파일 링크 명시. line 73~86: 핵심 결정 → Task 매핑 표(§1.1~§1.9 + §7)"
      },
      {
        "section": "traceability",
        "item": "모든 태스크가 설계 결정 중 하나 이상에 매핑됨 (orphan 태스크 없음)",
        "status": "yes",
        "evidence": "PCS-1~PCS-16 각 '목적' 필드에 §1.x 또는 §3/§4.4/§7 참조 명시"
      },
      {
        "section": "task quality",
        "item": "모든 태스크가 객관적 완료 기준을 가짐",
        "status": "yes",
        "evidence": "tdd=true 태스크: 테스트 메서드+검증 내용 표 완비. tdd=false 태스크: 산출물 파일 경로 명시"
      },
      {
        "section": "task quality",
        "item": "태스크 크기 ≤ 2시간",
        "status": "yes",
        "evidence": "최대 규모 PCS-9: 서비스 3개 갱신 + 테스트 10개, 한 커밋 단위 분해 가능"
      },
      {
        "section": "task quality",
        "item": "각 태스크에 관련 소스 파일/패턴이 언급됨",
        "status": "yes",
        "evidence": "전 태스크 '산출물' 섹션에 패키지 경로 포함 파일 목록 명시"
      },
      {
        "section": "TDD specification",
        "item": "tdd=true 태스크는 테스트 클래스 + 테스트 메서드 스펙이 명시됨",
        "status": "yes",
        "evidence": "PCS-2,4,6~13,15 모두 테스트 클래스명 + 메서드명 + 검증 내용 표 완비"
      },
      {
        "section": "TDD specification",
        "item": "tdd=false 태스크는 산출물(파일/위치)이 명시됨",
        "status": "yes",
        "evidence": "PCS-1(SQL), PCS-3(인터페이스), PCS-5(record+interface), PCS-14(yml+enum), PCS-16(위키+문서) 파일 경로 명시"
      },
      {
        "section": "TDD specification",
        "item": "TDD 분류가 합리적 (business logic / state machine / edge case는 tdd=true)",
        "status": "yes",
        "evidence": "상태 전이(PCS-2), race condition(PCS-4), TX 경계(PCS-6,7), 멱등성(PCS-8,9), 채널 메트릭(PCS-10), AFTER_COMMIT(PCS-11) 모두 tdd=true"
      },
      {
        "section": "dependency ordering",
        "item": "layer 의존 순서 준수",
        "status": "yes",
        "evidence": "도메인→포트→인프라-리포→application→인프라-채널/리스너/스케줄러→설정→통합테스트→문서 순서 준수"
      },
      {
        "section": "dependency ordering",
        "item": "Fake 구현이 그것을 소비하는 태스크보다 먼저 옴",
        "status": "yes",
        "evidence": "FakePgInboxRepository 갱신 PCS-9 포함. PCS-2~8 단위 테스트는 Mockito mock 사용으로 Fake 불필요"
      },
      {
        "section": "dependency ordering",
        "item": "orphan port 없음",
        "status": "yes",
        "evidence": "PgInboxProcessUseCase(PCS-5) → PgInboxProcessor(PCS-8) → ImmediateWorker/PollingWorker(PCS-12/13) 완전한 체인 존재"
      },
      {
        "section": "architecture fit",
        "item": "ARCHITECTURE.md의 layer 규칙과 충돌 없음",
        "status": "yes",
        "evidence": "infrastructure/listener(AFTER_COMMIT), infrastructure/scheduler(VT 워커), domain/event 위치 모두 hexagonal 규칙 준수. 거울 위치 이슈(PCS-5 주석)는 plan 라운드에서 흡수 완료"
      },
      {
        "section": "architecture fit",
        "item": "모듈 간 호출이 port / InternalReceiver를 통함",
        "status": "yes",
        "evidence": "InboxReadyEventHandler → PgInboxChannel → PgInboxImmediateWorker → PgInboxProcessUseCase(port.in) → PgInboxProcessor 경로"
      },
      {
        "section": "architecture fit",
        "item": "CONVENTIONS.md의 Lombok / 예외 / 로깅 패턴을 따르도록 계획됨",
        "status": "n/a",
        "evidence": "PLAN에서 코드 스타일 위반 지시 없음. 심층 판정은 plan 라운드 완료 범위"
      },
      {
        "section": "artifact",
        "item": "docs/<TOPIC>-PLAN.md 존재",
        "status": "yes",
        "evidence": "docs/PG-CONFIRM-LISTENER-SPLIT-PLAN.md 파일 실제 확인"
      },
      {
        "section": "domain risk",
        "item": "discuss에서 식별된 domain risk가 각각 대응 태스크를 가짐",
        "status": "yes",
        "evidence": "listener TX(PCS-7), SKIP LOCKED(PCS-4), 무한 루프(PCS-9), 멱등성(PCS-8), AFTER_COMMIT(PCS-7), 좀비 폴링(PCS-13) 모두 대응"
      },
      {
        "section": "domain risk",
        "item": "중복 방지 체크(예: existsByOrderId)가 필요한 경로에 계획됨",
        "status": "yes",
        "evidence": "PCS-3 insertPending orderId UNIQUE 충돌 반환 + PCS-4 transitPendingToInProgress SKIP LOCKED 명시"
      },
      {
        "section": "domain risk",
        "item": "재시도 안전성 검증 태스크 존재",
        "status": "yes",
        "evidence": "PCS-8 processInProgressZombie_vendorReturnsAlreadyProcessed + PCS-13 poll_processingException 테스트"
      }
    ],
    "total": 18,
    "passed": 17,
    "failed": 0,
    "not_applicable": 1
  },

  "scores": {
    "traceability": 1.00,
    "decomposition": 0.97,
    "ordering": 0.92,
    "specificity": 0.98,
    "risk_coverage": 1.00,
    "mean": 0.974
  },

  "findings": [
    {
      "severity": "minor",
      "checklist_item": "layer 의존 순서 준수",
      "location": "docs/PG-CONFIRM-LISTENER-SPLIT-PLAN.md PCS-7 테스트 섹션 (line 283) + 레이어 의존 그래프 (line 534)",
      "problem": "PCS-7 테스트 insertPendingAndPublish_afterCommitListenerFires 가 PgInboxChannel.size() 직접 참조하나, PgInboxChannel 클래스는 PCS-10에서 신규 생성됨. 의존 그래프에서 PCS-7 → ... → PCS-10 순서이므로 PCS-7 작성 시점에 컴파일 불가.",
      "evidence": "PLAN.md line 283: 'PgInboxChannel.size() 가 1 이상으로 증가하거나 handler 호출 확인'; PLAN.md 의존 그래프 line 534: 'PCS-9 → PCS-10 (infra: InboxJob + PgInboxChannel)'",
      "suggestion": "PCS-7 해당 테스트를 PgInboxChannel 직접 참조 대신 @TransactionalEventListener(AFTER_COMMIT) Mock 핸들러 호출 여부로 대체하거나, 해당 테스트를 PCS-11 InboxReadyEventHandlerTest 로 이동. Implementer TDD 진행 시 mock 대체가 가장 영향 범위 적음."
    }
  ],

  "previous_round_ref": null,
  "delta": {
    "newly_passed": [],
    "newly_failed": [],
    "still_failing": []
  },

  "unstuck_suggestion": null
}
```
