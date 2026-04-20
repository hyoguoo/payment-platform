# plan-review-3

**Topic**: MSA-TRANSITION
**Round**: 3 (Plan Reviewer)
**Persona**: Plan Reviewer
**Date**: 2026-04-19

## Reasoning

plan-review-2(pass) 이후 Phase Gate 6개(Phase-0-Gate ~ Phase-5-Gate) 신규 추가분의 문서 정합성을 검증했다. 6개 Gate 전수에서 목적/스크립트/문서/검증항목/domain_risk=true/tdd=false/크기≤2h 필드가 존재하고, Mermaid 플로우차트 의존 구조와 핵심결정 매핑 테이블·추적 테이블 모두 Gate 행이 추가됐으며, 요약 브리핑 Task 수가 46으로 갱신됐다. 다만 Phase-5-Gate 검증 항목의 archive 경로(`docs/archive/msa-transition/` 디렉토리)가 Phase-5.2 산출물 경로(`docs/archive/MSA-TRANSITION.md` 단일 파일)와 정합하지 않아 Gate 완료 기준과 태스크 산출물 간 불일치가 발생하는 major finding을 확인했다.

## Checklist judgement

### traceability
- PLAN.md가 `docs/topics/MSA-TRANSITION.md` 결정 사항을 참조함: **yes** — 핵심 결정 → Task 매핑 테이블에 "Phase 게이트 통합 검증" 행 추가(line 193), 추적 테이블에 "Phase Gate 통합 검증" 행 추가(line 1214). plan-review-2 기준선 유지.
- 모든 태스크가 설계 결정 중 하나 이상에 매핑됨: **yes** — 6개 Gate 모두 추적 테이블 "Phase Gate 통합 검증" 행에 매핑됨(line 1214). orphan 없음.

### task quality
- 모든 태스크가 객관적 완료 기준을 가짐: **no** — Phase-5-Gate 항목 5(line 1191)의 archive 경로 `docs/archive/msa-transition/`이 Phase-5.2 산출물(line 1174) `docs/archive/MSA-TRANSITION.md`와 불일치. Phase-5.2는 PLAN.md 이동을 산출물로 선언하지 않으나 Gate는 PLAN.md 이동을 확인함. 완료 기준과 실제 산출물이 어긋나 Gate 실행 시 false negative 위험.
- 태스크 크기 ≤ 2h: **yes** — 6개 Gate 전수 "≤ 2h" 표기.
- 각 태스크에 관련 소스 파일/패턴이 언급됨: **yes** — 각 Gate에 `scripts/phase-gate/phase-N-gate.sh` + `docs/phase-gate/phase-N-gate.md` 산출물 경로 명시.

### TDD specification
- `tdd=true` 태스크는 테스트 클래스 + 테스트 메서드 스펙이 명시됨: **n/a** — 6개 Gate 모두 tdd=false.
- `tdd=false` 태스크는 산출물(파일/위치)이 명시됨: **yes** — 각 Gate에 스크립트 경로 + 문서 경로가 구체적으로 명시됨.
- TDD 분류가 합리적: **yes** — Gate는 통합 검증 스크립트(shell) 성격으로 tdd=false 적합.

### dependency ordering
- layer 의존 순서 준수: **yes** — Mermaid 플로우차트(lines 132-143)에서 `Phase-N 마지막 태스크 → Phase-N-Gate → Phase-N+1 첫 태스크` 구조 확인. P04→P0G→P10, P1G→P21, P2G→P31, P3G→P41, P4G→P51, P52→P5G 전수 확인.
- Fake 구현이 그것을 소비하는 태스크보다 먼저 옴: **yes** — plan-review-2 기준선 유지.
- orphan port 없음: **yes** — plan-review-2 기준선 유지.

### architecture fit
- ARCHITECTURE.md layer 규칙과 충돌 없음: **yes** — Gate는 `scripts/` 및 `docs/` 산출물만 생성, layer 규칙 적용 대상 아님.
- 모듈 간 호출이 port / InternalReceiver를 통함: **yes** — plan-review-2 기준선 유지.
- CONVENTIONS.md Lombok/예외/로깅 패턴을 따르도록 계획됨: **yes** — plan-review-2 기준선 유지.

### artifact
- `docs/MSA-TRANSITION-PLAN.md` 존재: **yes**

### domain risk
- discuss에서 식별된 domain risk가 각각 대응 태스크를 가짐: **yes** — plan-review-2 기준선 유지.
- 중복 방지 체크가 필요한 경로에 계획됨: **yes** — plan-review-2 기준선 유지.
- 재시도 안전성 검증 태스크 존재: **yes** — plan-review-2 기준선 유지.

## Findings

### F-01 (major) — Phase-5-Gate archive 경로가 Phase-5.2 산출물과 불일치

- **id**: F-01
- **severity**: major
- **checklist_item**: task quality — 모든 태스크가 객관적 완료 기준을 가짐
- **location**:
  - Phase-5.2 산출물 (line 1174): `docs/archive/MSA-TRANSITION.md`
  - Phase-5-Gate 항목 5 (line 1191): `` `docs/archive/msa-transition/` 존재, `docs/topics/MSA-TRANSITION.md` · `docs/MSA-TRANSITION-PLAN.md` 이동 완료``
- **problem**:
  1. Phase-5.2가 이동하는 경로는 `docs/archive/MSA-TRANSITION.md`(단일 파일, 대문자 kebab)인데, Phase-5-Gate 항목 5는 `docs/archive/msa-transition/`(소문자 kebab **디렉토리**)의 존재를 확인한다. 파일 이동 대상 경로와 Gate 검증 경로가 다르다.
  2. Phase-5.2 산출물 목록에는 `docs/MSA-TRANSITION-PLAN.md` 이동이 선언되지 않았으나, Phase-5-Gate는 이 파일의 이동까지 검증 항목으로 포함한다. Gate가 확인하는 범위가 Phase-5.2 산출물을 초과한다.
- **evidence**:
  - Phase-5.2 (line 1174): `"docs/archive/MSA-TRANSITION.md" — 아카이브 이동`
  - Phase-5-Gate 항목 5 (line 1191): `"archive 이동 확인 (docs/archive/msa-transition/ 존재, docs/topics/MSA-TRANSITION.md · docs/MSA-TRANSITION-PLAN.md 이동 완료)"`
- **suggestion**: Phase-5.2 산출물에 (a) 이동 대상을 `docs/archive/msa-transition/MSA-TRANSITION.md` + `docs/archive/msa-transition/MSA-TRANSITION-PLAN.md`로 통일하거나, (b) Phase-5-Gate 항목 5를 Phase-5.2 산출물과 일치하는 경로로 수정한다. Phase-5-Gate 스크립트가 실제로 확인할 수 있는 경로를 양쪽이 일관되게 선언해야 Gate 완료 기준이 기계 판정 가능해진다.

## 회귀 검사

- **헤더 라운드 갱신** (line 5): "5 (plan-round 5 Planner 수정 — Redis 캐시 차감 + IdempotencyStore Redis 이관 + plan-review-1 minor 8건 보강 + Phase Gate 6개 추가)" — `Phase Gate 6개 추가` 문구 실재. **통과**
- **Phase-0.1a/1.4d/1.5b/1.12/3.1c 5개 Round 5 신규 태스크 유지**: 브리핑 목록(lines 19/34/36/43) 및 본문 각 섹션 전수 실재 확인. **통과**
- **plan-review-1 반영 문장 보존** (F-01~F-08): plan-review-2에서 전수 확인된 항목들(settings.gradle 6곳, warmup, PgMaskedSuccessHandler, IN_FLIGHT drain, ADR-27 등)이 Gate 추가 이후에도 해당 Phase 섹션에 그대로 존재함. **통과**
- **"예약/reservation" 미등장**: Gate 6개 섹션 포함 전문 확인. 미등장. **통과**
- **Task 총 수 46** (line 1220): "태스크 총 개수: 46" 실재. **통과**
- **Phase별 task 수 정합성** (브리핑 line 17-73): Phase 0(6)/Phase 1(18)/Phase 2(7)/Phase 3(8)/Phase 4(4)/Phase 5(3) — 합계 46. **통과**

## 추가 엄격성 검사

- **Gate 검증 항목 기계 판정 가능성**:
  - Phase-0-Gate: PING→PONG, HTTP 200, EVAL 결과값, NX 중복 차단 등 exit code/응답 코드 기반. **기계 판정 가능**
  - Phase-1-Gate: HTTP 200, SHOW TABLES 존재 확인, Kafka 발행 확인, QUARANTINED 전이 로그 확인, 메트릭 scraping 확인. "Reconciler 경보 확인"(line 756) 표현이 다소 모호하나 컨텍스트상 로그/메트릭 기반 판정을 의미하므로 **minor 수준**
  - Phase-2~4-Gate: exit code / HTTP status / `docker ps` 인스턴스 수 등 구체 판정. **기계 판정 가능**
  - Phase-5-Gate: `./gradlew test` exit code, Gate 스크립트 exit code, k6 목표 달성 여부, dead link checker exit code. **기계 판정 가능** (단 archive 경로 불일치는 F-01로 별도 관리)

- **Gate 태스크 간 일관성**: 6개 Gate 모두 같은 필드 구조(제목/목적/tdd=false/domain_risk=true/크기≤2h/산출물 2종)로 구성. 해상도도 유사(3~9개 검증 항목). **일관적**

- **Phase-4.1 vs Phase-4-Gate 역할 중복**: Phase-4.1은 8종 chaos 스크립트 파일 작성·정의, Phase-4-Gate는 작성된 스크립트를 전수 재실행하며 pass/fail 판정. 작성 책임과 실행 책임이 명확히 분리됨. **중복 없음**

- **Phase-5-Gate 자기 참조 문제**: archive 이동은 Phase-5.2가 수행(산출물 line 1174), Phase-5-Gate가 이동 완결 여부를 확인. 플로우차트에서 `P51 --> P52 --> P5G` (line 143)로 Phase-5.2 이후에 Gate 실행됨을 명시. **순서 정합** (단 archive 경로 불일치는 F-01로 처리)

- **Phase-4-Gate와 k6 선행 관계**: k6 시나리오 정의는 Phase-4.2 산출물, Phase-4-Gate는 해당 시나리오 재실행. `P42 --> P43 --> P4G` 의존 구조 확인(line 142). **중복 없음, 순서 정합**

## JSON

```json
{
  "stage": "plan-review",
  "persona": "plan-reviewer",
  "round": 3,
  "task_id": null,

  "decision": "revise",
  "reason_summary": "Phase Gate 6개 추가분 중 Phase-5-Gate 항목 5의 archive 검증 경로(docs/archive/msa-transition/ 디렉토리)가 Phase-5.2 산출물 경로(docs/archive/MSA-TRANSITION.md 단일 파일)와 불일치. Gate 완료 기준과 태스크 산출물 간 정합 오류로 major finding 1건 도출. critical finding 없음. 나머지 5개 Gate(Phase-0~4-Gate) 및 회귀 검사 전항목 통과.",

  "scores": {
    "traceability": 0.98,
    "decomposition": 0.93,
    "ordering": 0.98,
    "specificity": 0.93,
    "risk_coverage": 0.97,
    "mean": 0.958
  },

  "findings": [
    {
      "id": "F-01",
      "severity": "major",
      "checklist_item": "task quality — 모든 태스크가 객관적 완료 기준을 가짐",
      "location": "Phase-5-Gate 산출물 항목 5 (line 1191) vs Phase-5.2 산출물 (line 1174)",
      "problem": "Phase-5.2는 docs/archive/MSA-TRANSITION.md(단일 파일, 대문자 kebab)로 이동을 선언하나 Phase-5-Gate는 docs/archive/msa-transition/(소문자 kebab 디렉토리) 존재 및 docs/MSA-TRANSITION-PLAN.md 이동을 검증 항목으로 포함. 경로(파일 vs 디렉토리, 대소문자), 이동 대상 범위(topic.md 단독 vs topic.md+PLAN.md) 두 축에서 불일치 발생.",
      "evidence": "line 1174: 'docs/archive/MSA-TRANSITION.md — 아카이브 이동' / line 1191: 'archive 이동 확인 (docs/archive/msa-transition/ 존재, docs/topics/MSA-TRANSITION.md · docs/MSA-TRANSITION-PLAN.md 이동 완료)'",
      "suggestion": "Phase-5.2 산출물과 Phase-5-Gate 항목 5를 동일 경로(예: docs/archive/msa-transition/MSA-TRANSITION.md + docs/archive/msa-transition/MSA-TRANSITION-PLAN.md)로 통일. Phase-5.2에 PLAN.md 이동 산출물 추가 여부도 함께 결정."
    }
  ],

  "gate_spec_check": {
    "phase-0-gate": "pass — 목적/스크립트/문서/검증항목 9개/domain_risk=true/tdd=false/크기≤2h 전항목 실재. 기계 판정 가능(PING→PONG, HTTP 200, NX 중복 차단).",
    "phase-1-gate": "pass — 목적/스크립트/문서/검증항목 7개/domain_risk=true/tdd=false/크기≤2h 실재. 'Reconciler 경보 확인' 표현 minor 모호성 있으나 판정 영향 없음.",
    "phase-2-gate": "pass — 목적/스크립트/문서/검증항목 5개/domain_risk=true/tdd=false/크기≤2h 실재. 기계 판정 가능.",
    "phase-3-gate": "pass — 목적/스크립트/문서/검증항목 9개/domain_risk=true/tdd=false/크기≤2h 실재. 기계 판정 가능.",
    "phase-4-gate": "pass — 목적/스크립트/문서/검증항목 4개/domain_risk=true/tdd=false/크기≤2h 실재. Phase-4.1 vs Gate 역할 중복 없음.",
    "phase-5-gate": "F-01 major — archive 경로 Phase-5.2 산출물과 불일치. 나머지 필드(목적/스크립트/문서/domain_risk=true/tdd=false/크기≤2h) 실재. 순서(Phase-5.2→Phase-5-Gate) 정합."
  },

  "briefing_check": {
    "task_count_46": "pass — line 15: '46개, 6 Phase' + line 1220: '태스크 총 개수: 46'",
    "phase_breakdown": "pass — Phase 0(6)/Phase 1(18)/Phase 2(7)/Phase 3(8)/Phase 4(4)/Phase 5(3) = 46",
    "gate_rows_in_briefing": "pass — 브리핑 lines 23/43/52/62/68/73에 각 Gate 행 실재"
  },

  "flowchart_check": {
    "gate_nodes_6": "pass — P0G/P1G/P2G/P3G/P4G/P5G 6개 노드 실재 (lines 85/102/108/118/124/129)",
    "dependency_structure": "pass — P04→P0G→P10, P112→P1G→P21, P24→P2G→P31, P35→P3G→P41, P43→P4G→P51, P52→P5G (lines 132-143)"
  },

  "mapping_table_check": {
    "core_decision_row": "pass — line 193: 'Phase 게이트 통합 검증' 행 + Phase-0~5-Gate 매핑 실재",
    "tracking_table_row": "pass — line 1214: 'Phase Gate 통합 검증 (Phase Gate 신규)' 행 + domain_risk=true + 6개 Gate 매핑 실재"
  },

  "regression_check": {
    "header_phase_gate_added": "pass — line 5: 'Phase Gate 6개 추가' 문구 실재",
    "round5_tasks_preserved": "pass — Phase-0.1a/1.4d/1.5b/1.12/3.1c 5개 전수 확인",
    "plan_review_1_sentences_preserved": "pass — plan-review-2 F-01~F-08 반영 문장 보존 확인",
    "no_reservation_wording": "pass — Gate 섹션 포함 전문 미등장",
    "task_count_46": "pass — line 1220: '태스크 총 개수: 46'"
  },

  "previous_round_ref": "docs/rounds/msa-transition/plan-review-2.md"
}
```
