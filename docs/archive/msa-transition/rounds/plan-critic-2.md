# plan-critic-2

**Topic**: MSA-TRANSITION
**Round**: 2
**Persona**: Critic
**Stage**: plan
**Note**: Round 6 전면 재작성 PLAN.md(Round 2 수정판, 64 태스크) 대상. 이전 plan 사이클의 동일 파일명 내용 덮어씀.

## Reasoning

Round 1의 critical 4·major 2·minor 4 findings가 전수 해소됨. T1-11a/c·T2a-05a/c Worker가 `MessagePublisherPort.publish()` / `PgEventPublisherPort.publish()` 경유로 재기술되고 "KafkaTemplate 직접 호출 금지" 명시(C-1/C-3 해소). T1-11b에 `PaymentConfirmChannel`(LinkedBlockingQueue<Long>, capacity=1024, overflow→Polling fallback) 산출물 추가(C-2 해소). T2a-05a에 `PgOutboxRelayService.java` 산출물 추가로 T1-11a 대칭 복원(C-4 해소). T1-11·T2a-05 각 3태스크로 분해되고 의존 엣지(T1-15/T1-18/T2a-06/T2b-02 등) 일관되게 갱신(M-1 해소). T1-12 테스트명에 FCG/DLQ 진입점 명시(M-2 해소). 신규 T3-04b가 payment-service FAILED→outbox row INSERT + UUID 보장 테스트 2건으로 D-1 해소. Architect 인라인 주석 전수 제거 확인. 신규 critical/major 결함 없음. 잔존 minor 3건은 전부 구 앵커명(T1-11/T2a-05) 텍스트 참조 누락(산문·매핑 표 잔류)로 Gate 판정 영향 없음.

## Round 1 finding 해소 상태

- **C-1** (T1-11 Worker → KafkaTemplate): resolved (PLAN.md:309, 340)
- **C-2** (PaymentConfirmChannel 산출물 누락): resolved (PLAN.md:332)
- **C-3** (T2a-05 Worker → KafkaTemplate): resolved (PLAN.md:565, 596, 605)
- **C-4** (PgOutboxRelayService orphan): resolved (PLAN.md:576)
- **M-1** (2h 초과): resolved (T1-11a/b/c lines 306-350, T2a-05a/b/c lines 562-606, depends 엣지 T1-15→T1-11c:416, T1-18→T1-11c:470, T2a-06→T2a-05c:616, T2b-02→T2a-05c:672)
- **M-2** (T1-12 entry 명시): resolved (PLAN.md:364, 366)
- **m-1** (Topics 재배치): resolved (PLAN.md:302, 832-834)
- **m-2** (T2a-06 참조): resolved (PLAN.md:613)
- **m-3** (T1-17 관심사 분리): resolved (PLAN.md:447-460)
- **m-4** (T3-04 port 이름): resolved (PLAN.md:943)
- **D-1** (FailureCompensationService): resolved (PLAN.md:952-966)

## Checklist judgement

총 15 항목, passed 14, failed 0(minor만), n/a 1.

| section | item | status | evidence |
|---|---|---|---|
| traceability | PLAN.md가 topic.md 결정 사항 참조 | yes | line 3 `[MSA-TRANSITION](topics/MSA-TRANSITION.md)` |
| traceability | 모든 태스크가 ADR에 매핑 (orphan 없음) | yes | lines 1183-1220 ADR 커버리지 표 (ADR-04에 T1-11a/b/c·T2a-05a/b/c·T3-04b 정상 포함) |
| task quality | 객관적 완료 기준 | yes | 태스크별 테스트 메서드명/산출물 경로 |
| task quality | 태스크 크기 ≤ 2h | yes | T1-11·T2a-05 3분해(각 1~2 산출물 + 2~3 테스트) |
| task quality | 관련 소스 파일/패턴 언급 | yes | T1-11b PaymentConfirmChannel 산출물 추가, 모든 분해 태스크 경로 명시 |
| TDD specification | tdd=true 태스크 테스트 클래스/메서드 명시 | yes | OutboxRelayServiceTest(T1-11a), OutboxImmediateWorkerTest(T1-11c), PgOutboxRelayServiceTest(T2a-05a), PgOutboxImmediateWorkerTest(T2a-05c), FailureCompensationServiceTest(T3-04b) 전부 명시 |
| TDD specification | tdd=false 태스크 산출물 명시 | yes | T1-11b, T2a-05b 등 비 TDD 태스크 산출물 경로 명시 |
| TDD specification | TDD 분류 합리성 | yes | Publisher/Worker(business) tdd=true, Channel+Handler(인프라 고정 구조) tdd=false |
| dependency ordering | layer 의존 순서 준수 | yes | T1-11c(line 340)가 MessagePublisherPort 경유, T2a-05c(line 596) PgEventPublisherPort 경유 — scheduler → application port → infrastructure adapter 순서 유지 |
| dependency ordering | Fake가 소비자 앞에 옴 | yes | T1-03<T1-11a, T2a-03<T2a-05a |
| dependency ordering | orphan port 없음 | yes | MessagePublisherPort/PgEventPublisherPort 모두 KafkaMessagePublisher/PgEventPublisher 구현체 보유(T1-11a line 319, T2a-05a line 575) |
| architecture fit | ARCHITECTURE.md layer 규칙 충돌 없음 | yes | scheduler Worker가 port 인터페이스만 의존, "KafkaTemplate 직접 호출 금지" 명시 |
| architecture fit | 모듈 간 호출이 port/InternalReceiver 경유 | yes | T3-06 port 구현, T3-04b outbox row(기존 MessagePublisherPort 재사용) |
| architecture fit | CONVENTIONS.md 준수 | n/a | 코드 미작성 단계 |
| artifact | PLAN.md 존재 | yes | docs/MSA-TRANSITION-PLAN.md |

## Findings

### m-5 (minor) — 산문 T1-11 구 앵커명 잔류

- **location**: PLAN.md line 421
- **problem**: T1-15 `관련 파일` 산문이 `(T1-11 산출물 보완)`로 구 앵커명을 참조. `depends: [T1-11c]`는 정확하지만 설명 텍스트가 불일치.
- **evidence**: line 421 `- **관련 파일**: payment/scheduler/OutboxImmediateWorker.java(T1-11 산출물 보완)`
- **suggestion**: `(T1-11c 산출물 보완)`으로 교체.

### m-6 (minor) — T2a-03 목적 산문의 구 앵커명 잔류

- **location**: PLAN.md line 536
- **problem**: T2a-03 목적 문단에 "Fake가 소비자(T2a-05 이후) 앞에 배치"로 구 앵커명을 사용.
- **evidence**: line 536 `Fake가 소비자(T2a-05 이후) 앞에 배치.`
- **suggestion**: `(T2a-05a 이후)` 또는 `(T2a-05 family 이후)`로 교체.

### m-7 (minor) — 추적 테이블 ADR-30 행의 구 앵커명 잔류

- **location**: PLAN.md line 1157
- **problem**: "추적 테이블: discuss 리스크 → 태스크 매핑" 중 ADR-30 행이 `T2a-05`로 un-split 표기. 하단 ADR 커버리지 표(line 1215)는 `T2a-05a, T2a-05b, T2a-05c`로 일관. 두 표 간 불일치.
- **evidence**: line 1157 `| ADR-30 (...) | ... | T2a-05, T2b-01, T2b-02 | true |`
- **suggestion**: `T2a-05a, T2a-05b, T2a-05c, T2b-01, T2b-02`로 교체해 ADR 커버리지 표와 동기화.

## JSON

```json
{
  "stage": "plan",
  "persona": "critic",
  "round": 2,
  "task_id": null,
  "decision": "pass",
  "reason_summary": "Round 1 critical 4 / major 2 / minor 4 + Domain D-1 전수 해소 확인. Worker는 MessagePublisherPort·PgEventPublisherPort 경유, PaymentConfirmChannel·PgOutboxRelayService 산출물 복원, T1-11·T2a-05 3분해·의존 엣지 갱신, T1-12 entry 진입점 명시, T3-04b FailureCompensationService 신설·UUID 멱등성 테스트 포함. Architect 인라인 주석 전수 제거. 잔존 minor 3건은 구 앵커명(T1-11/T2a-05) 텍스트 참조 누락뿐이어서 Gate 판정에 영향 없음.",
  "checklist": {
    "source": "_shared/checklists/plan-ready.md",
    "items": [
      {"section": "traceability", "item": "PLAN.md가 topic.md 결정 사항 참조", "status": "yes", "evidence": "line 3"},
      {"section": "traceability", "item": "모든 태스크가 ADR에 매핑 (orphan 없음)", "status": "yes", "evidence": "lines 1183-1220 ADR 커버리지 표"},
      {"section": "task quality", "item": "객관적 완료 기준", "status": "yes", "evidence": "태스크별 테스트 메서드명/산출물 경로"},
      {"section": "task quality", "item": "태스크 크기 ≤ 2h", "status": "yes", "evidence": "T1-11·T2a-05 3분해 완료"},
      {"section": "task quality", "item": "관련 소스 파일/패턴 언급", "status": "yes", "evidence": "T1-11b PaymentConfirmChannel 산출물 추가"},
      {"section": "TDD specification", "item": "tdd=true 테스트 클래스/메서드 명시", "status": "yes", "evidence": "OutboxRelayServiceTest·PgOutboxRelayServiceTest·FailureCompensationServiceTest 명시"},
      {"section": "TDD specification", "item": "tdd=false 산출물 명시", "status": "yes", "evidence": "T1-11b·T2a-05b 등"},
      {"section": "TDD specification", "item": "TDD 분류 합리성", "status": "yes", "evidence": "Publisher/Worker tdd=true, Channel+Handler tdd=false"},
      {"section": "dependency ordering", "item": "layer 의존 순서 준수", "status": "yes", "evidence": "T1-11c line 340 MessagePublisherPort 경유, T2a-05c line 596 PgEventPublisherPort 경유"},
      {"section": "dependency ordering", "item": "Fake가 소비자 앞에 옴", "status": "yes", "evidence": "T1-03<T1-11a, T2a-03<T2a-05a"},
      {"section": "dependency ordering", "item": "orphan port 없음", "status": "yes", "evidence": "MessagePublisherPort/PgEventPublisherPort 모두 구현체 보유"},
      {"section": "architecture fit", "item": "ARCHITECTURE.md layer 규칙 충돌 없음", "status": "yes", "evidence": "Worker → port만 의존, KafkaTemplate 직접 호출 금지 명시"},
      {"section": "architecture fit", "item": "모듈 간 호출이 port/InternalReceiver 경유", "status": "yes", "evidence": "T3-06, T3-04b outbox 재사용"},
      {"section": "architecture fit", "item": "CONVENTIONS.md 준수", "status": "n/a", "evidence": "코드 미작성 단계"},
      {"section": "artifact", "item": "PLAN.md 존재", "status": "yes", "evidence": "docs/MSA-TRANSITION-PLAN.md"}
    ],
    "total": 15,
    "passed": 14,
    "failed": 0,
    "not_applicable": 1
  },
  "scores": {
    "traceability": 0.95,
    "decomposition": 0.92,
    "ordering": 0.95,
    "specificity": 0.90,
    "risk_coverage": 0.92,
    "mean": 0.928
  },
  "findings": [
    {
      "id": "m-5",
      "severity": "minor",
      "checklist_item": "관련 소스 파일/패턴 언급",
      "location": "docs/MSA-TRANSITION-PLAN.md line 421",
      "problem": "T1-15 '관련 파일' 산문이 '(T1-11 산출물 보완)'으로 구 앵커명 참조. depends는 T1-11c로 정확.",
      "evidence": "line 421",
      "suggestion": "'(T1-11c 산출물 보완)'으로 교체"
    },
    {
      "id": "m-6",
      "severity": "minor",
      "checklist_item": "관련 소스 파일/패턴 언급",
      "location": "docs/MSA-TRANSITION-PLAN.md line 536",
      "problem": "T2a-03 목적 문단이 'Fake가 소비자(T2a-05 이후) 앞에 배치'로 구 앵커명 참조.",
      "evidence": "line 536",
      "suggestion": "'(T2a-05a 이후)'로 교체"
    },
    {
      "id": "m-7",
      "severity": "minor",
      "checklist_item": "모든 태스크가 ADR에 매핑",
      "location": "docs/MSA-TRANSITION-PLAN.md line 1157",
      "problem": "추적 테이블 ADR-30 행이 'T2a-05'로 un-split 표기. ADR 커버리지 표(line 1215)는 T2a-05a/b/c로 split — 두 표 간 불일치.",
      "evidence": "line 1157 vs line 1215",
      "suggestion": "'T2a-05a, T2a-05b, T2a-05c, T2b-01, T2b-02'로 동기화"
    }
  ],
  "previous_round_ref": "plan-critic-1.md",
  "delta": {
    "newly_passed": [
      "태스크 크기 ≤ 2h",
      "관련 소스 파일/패턴 언급",
      "tdd=true 테스트 클래스/메서드 명시",
      "layer 의존 순서 준수",
      "ARCHITECTURE.md layer 규칙 충돌 없음"
    ],
    "newly_failed": [],
    "still_failing": []
  },
  "unstuck_suggestion": null
}
```
