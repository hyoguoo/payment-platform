# plan-critic-1

**Topic**: TIME-MODEL-FOLLOWUP
**Round**: 1
**Persona**: Critic

## Reasoning

PLAN.md의 18 태스크는 topic.md D1~D7 결정과 추적 테이블로 양방향 매핑되며 orphan 태스크·미매핑 결정이 없다. layer 의존 순서(port P1 → infra P2 → fake P3 → use case P4 → consumer P5)는 의존 역전 없이 정합하고, P3 Fake 선행이 P4 단위 테스트 컴파일을 보장하는 근거가 명시됐다. 코드 사실(Flyway V4, existsValid/SQL_DELETE_EXPIRED_BY_UUID 라인, V1 DDL nullable·인덱스 키 컬럼, eureka infra.yml 소속, Dockerfile `/app/app.jar` 경로)을 직접 재확인했고 PLAN 기재와 일치한다. critical 결함 없음. minor 2건만 존재하여 pass.

## Checklist judgement

### traceability
- PLAN.md가 topic.md 결정 참조: **yes** — 헤더 line 3, 추적 테이블 line 369-377이 D1~D7 인용.
- orphan 태스크 없음: **yes** — P1~P18 전부 결정 ID 매핑(추적 테이블 + 각 태스크 "결정 추적" 필드). 역방향: D1~D7 전건 태스크 보유(D1→P1-P5, D2→P1,P2,P3,P6,P7, D3→P8,P9, D4→P11-P18, D5→P10, D6→P2, D7→전체). 설계에 없는 끼어든 태스크 없음.

### task quality
- 객관적 완료 기준: **yes** — 각 태스크 AC가 파일/라인/시그니처/테스트 GREEN 등 검증 가능 단위. 예: P14 "V1→V4 순차 적용 후 ddl-auto: validate 통과", P12 "clockDateTimeProvider 반환 Instant 테스트 GREEN".
- 태스크 크기 ≤ 2h: **yes** — 단일 파일/단일 관심사 분해.
- 관련 소스 파일/패턴 언급: **yes** — 전 태스크 "변경 파일" + 라인 번호 명시.

### TDD specification
- tdd=true 테스트 스펙 명시: **yes** — P1/P2/P4/P5/P11/P12/P13/P15/P16 전부 클래스+메서드 스펙 보유.
- tdd=false 산출물 명시: **yes** — P3/P6/P7/P8/P9/P10/P14/P17/P18 산출물/파일 명시.
- TDD 분류 합리성: **yes** — 시그니처 계약·타입 전환·경계 로직은 tdd=true, 설정/주석/잔재정리는 tdd=false. 합리적. (minor: P5 resolveExpiresAt now 공유 불변은 테스트 스펙 미커버 — 아래 finding F2)

### dependency ordering
- layer 의존 순서: **yes** — port(P1)→infra(P2)→fake(P3)→application(P4)→consumer(P5). 역전 없음.
- Fake가 소비자보다 선행: **yes** — P3 Fake가 P4(use case 단위 테스트)·P6(contract)보다 앞. line 73 순서 근거 명시.
- orphan port 없음: **yes** — P1 포트 변경은 P2 구현·P3 Fake가 동반.

### architecture fit
- ARCHITECTURE layer 규칙 충돌 없음: **yes** — Clock 권한을 consumer 진입점에 유지(D2 헥사고날 원칙), use case에 Clock 신설 금지(P4 AC line 89).
- 모듈 간 호출 port 경유: **yes** — consumer→useCase→port 사슬 유지.
- CONVENTIONS 패턴: **yes** — TDD 커밋 흐름(D7), Lombok/예외 패턴 명시 위반 없음.

### artifact
- docs/TIME-MODEL-FOLLOWUP-PLAN.md 존재: **yes**.

## Findings

### F1 (minor)
- **checklist_item**: traceability (역추적 — 설계 문서 내 참조 정합)
- **location**: docs/topics/TIME-MODEL-FOLLOWUP.md line 274
- **problem**: §3 변경 범위가 PaymentEventEntity 매핑 메서드를 `toResult()`로 지칭하나, 실제 메서드명은 `toDomain()`이다(PaymentEventEntity.java:101). PLAN P15(line 302)는 올바르게 `toDomain()`을 쓰므로 PLAN 자체는 정확하나, 설계 문서의 메서드명 오기가 execute 단계 혼선 소지.
- **evidence**: PaymentEventEntity.java:101 `public PaymentEvent toDomain(...)`; :119-120 `getCreatedAt().toInstant(java.time.ZoneOffset.UTC)`. topic.md:274는 `toResult()` 표기.
- **suggestion**: topic.md §3 line 274 `toResult()` → `toDomain()` 정정. (PLAN은 무수정.)

### F2 (minor)
- **checklist_item**: TDD specification (tdd=true 태스크 테스트 스펙 충분성)
- **location**: docs/TIME-MODEL-FOLLOWUP-PLAN.md P5 lines 110-116
- **problem**: P5 AC는 `now` 산출을 resolveExpiresAt보다 앞에 배치해 (1) commit now 인자와 (2) resolveExpiresAt fallback base가 동일 Instant를 공유하는 불변을 요구하나, 테스트 스펙은 `commit`의 now 인자 일치만 verify하고 fallback base가 동일 now를 공유하는지(occurredAt null 경로)는 단정하지 않는다. 단일-진입점 동일-시각 불변(D1)의 fallback 분기가 회귀 가드 밖.
- **evidence**: StockCommitConsumer.java:84-92 resolveExpiresAt가 occurredAt null일 때만 clock.instant() 사용(:89). P5 테스트 스펙(line 116)은 commit now eq만 단정.
- **suggestion**: P5 테스트에 occurredAt=null 메시지로 expiresAt fallback base가 commit now와 동일 Clock.fixed 기준임을 단정하는 케이스 추가(혹은 AC에서 "테스트 비대상, 구조 보장만" 명시).

## JSON
```json
{
  "stage": "plan",
  "persona": "critic",
  "round": 1,
  "task_id": null,
  "decision": "pass",
  "reason_summary": "18 태스크가 D1~D7과 양방향 매핑되고 layer 의존 순서·Fake 선행이 정합하며 코드 사실(V4/라인/V1 DDL/eureka infra.yml/Dockerfile 경로) 전건 재확인 일치. critical 없음, minor 2건만 존재해 pass.",
  "checklist": {
    "source": "_shared/checklists/plan-ready.md",
    "items": [
      { "section": "traceability", "item": "PLAN.md가 topics/<TOPIC>.md 결정 참조", "status": "yes", "evidence": "PLAN.md:3 헤더 + 추적 테이블 :369-377" },
      { "section": "traceability", "item": "모든 태스크가 설계 결정에 매핑 (orphan 없음)", "status": "yes", "evidence": "추적 테이블 D1~D7 전건 태스크 보유 + 각 태스크 결정추적 필드, 역방향 끼어든 태스크 없음" },
      { "section": "task quality", "item": "객관적 완료 기준", "status": "yes", "evidence": "P14:280 validate 통과, P12:236 테스트 GREEN 등 검증가능 AC" },
      { "section": "task quality", "item": "태스크 크기 <= 2h", "status": "yes", "evidence": "단일 파일/단일 관심사 분해" },
      { "section": "task quality", "item": "관련 소스 파일/패턴 언급", "status": "yes", "evidence": "전 태스크 변경 파일+라인 명시" },
      { "section": "TDD specification", "item": "tdd=true 테스트 스펙 명시", "status": "yes", "evidence": "P1/P2/P4/P5/P11/P12/P13/P15/P16 클래스+메서드 스펙" },
      { "section": "TDD specification", "item": "tdd=false 산출물 명시", "status": "yes", "evidence": "P3/P6/P7/P8/P9/P10/P14/P17/P18 파일 명시" },
      { "section": "TDD specification", "item": "TDD 분류 합리성", "status": "yes", "evidence": "시그니처/타입/경계=tdd, 설정/주석=non-tdd" },
      { "section": "dependency ordering", "item": "layer 의존 순서", "status": "yes", "evidence": "port(P1)->infra(P2)->fake(P3)->app(P4)->consumer(P5) 역전 없음" },
      { "section": "dependency ordering", "item": "Fake가 소비자보다 선행", "status": "yes", "evidence": "P3:73 Fake가 P4/P6 앞, 순서 근거 명시" },
      { "section": "dependency ordering", "item": "orphan port 없음", "status": "yes", "evidence": "P1 포트변경에 P2 구현+P3 Fake 동반" },
      { "section": "architecture fit", "item": "ARCHITECTURE layer 규칙 충돌 없음", "status": "yes", "evidence": "Clock 권한 consumer 유지, use case Clock 신설 금지(P4:89)" },
      { "section": "architecture fit", "item": "모듈 간 호출 port 경유", "status": "yes", "evidence": "consumer->useCase->port 사슬 유지" },
      { "section": "architecture fit", "item": "CONVENTIONS 패턴", "status": "yes", "evidence": "TDD 커밋 흐름 D7, 위반 없음" },
      { "section": "artifact", "item": "docs/<TOPIC>-PLAN.md 존재", "status": "yes", "evidence": "docs/TIME-MODEL-FOLLOWUP-PLAN.md" }
    ],
    "total": 15,
    "passed": 15,
    "failed": 0,
    "not_applicable": 0
  },
  "scores": {
    "traceability": 0.95,
    "decomposition": 0.92,
    "ordering": 0.93,
    "specificity": 0.88,
    "risk_coverage": 0.85,
    "mean": 0.906
  },
  "findings": [
    {
      "severity": "minor",
      "checklist_item": "traceability",
      "location": "docs/topics/TIME-MODEL-FOLLOWUP.md:274",
      "problem": "§3가 PaymentEventEntity 매핑 메서드를 toResult()로 지칭하나 실제는 toDomain(). PLAN P15는 정확하므로 PLAN 자체 결함 아님, 설계 문서 오기.",
      "evidence": "PaymentEventEntity.java:101 toDomain(...); :119-120 getCreatedAt().toInstant(ZoneOffset.UTC). topic.md:274 toResult() 표기.",
      "suggestion": "topic.md §3 line 274 toResult() -> toDomain() 정정."
    },
    {
      "severity": "minor",
      "checklist_item": "TDD specification",
      "location": "docs/TIME-MODEL-FOLLOWUP-PLAN.md:110-116",
      "problem": "P5 AC가 요구하는 now 단일-진입점 공유 불변 중 resolveExpiresAt fallback base(occurredAt null 경로)가 commit now와 동일 Instant인지 테스트 스펙이 단정하지 않음. 회귀 가드 외.",
      "evidence": "StockCommitConsumer.java:89 occurredAt null시만 clock.instant(); P5 테스트 스펙(line 116)은 commit now eq만 단정.",
      "suggestion": "P5에 occurredAt=null 메시지로 fallback base가 commit now와 동일 Clock.fixed 기준임을 단정하는 케이스 추가, 또는 AC에 구조보장-only 명시."
    }
  ],
  "previous_round_ref": null,
  "delta": null,
  "unstuck_suggestion": null
}
```
