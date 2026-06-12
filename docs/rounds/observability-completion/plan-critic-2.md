# plan-critic-2

**Topic**: OBSERVABILITY-COMPLETION
**Round**: 2
**Persona**: Critic

## Reasoning

Round 1 의 major 2건이 코드 실측 기준으로 실제 닫혔다. F-1: T4 변경 파일에 `pg-service/src/main/resources/application.yml` 이 추가됐고 `toss.api.call.duration` 이 pg-service 등록(`TossApiMetrics.java:36`)임을 확인 — exemplar 동작 범위를 payment+pg 2서비스로 명시해 AC4↔T4 추적이 복구됐다. F-2: (b)안대로 T0 를 순수 측정으로 한정(wiring 선적용 문구 제거)하고 T1 을 tdd=false 설정 태스크로 재분류해 RED 성립 불가 모순이 소멸 — 1줄 wiring 은 산출물(변경 파일·완료조건+`PaymentEosIntegrationTest --rerun-tasks`)로 명세돼 tdd=false 요건을 충족한다. minor 5건(F-3 D7 라벨키 단언, F-4 T8→T1 의존, F-5 등록명 주석, 가드 비발동 READY/IN_PROGRESS/RETRYING 전수, record() throw-free 노트)도 전부 반영됐고 도메인 사실(`canApplyConfirmResult` false=6종/true=3종)과 일치한다. critical·major 0, 잔여 minor 1건(T0 가 T1 wiring 전 시점이라 `kafka_producer_txn_*` 노출 행이 비의미·항상 fallback 으로 읽힘 — §3-7-C fallback 기본값과 T8→T1 의존으로 완화)뿐이므로 **pass**.

## Checklist judgement

### traceability
- PLAN 이 topic.md 결정 참조: **yes** (D1~D16 추적표 line 342-360, discuss 리스크 교차표 line 326-336)
- 모든 태스크가 결정에 매핑(orphan 없음): **yes** — F-1 해소로 AC4↔T4 실효 복구(T4 에 pg yml 추가, exemplar 범위 payment+pg 명시 line 122-129). D7↔T5 도 라벨키 단언 테스트(line 160)로 정합

### task quality
- 객관적 완료 기준: **yes** (각 태스크 AC/test green/파일 존재 명시). 단 T0 산출표의 `kafka_producer_txn_*` 노출 행은 T1 wiring 전 측정이라 비의미 (F-6 minor)
- 태스크 크기 ≤ 2h: **yes**
- 소스 파일/패턴 언급: **yes** (pg yml·payment yml·KafkaConsumer/ProducerConfig·DedupeCleanupWorker x2·PaymentEventStatus 6종 실측 일치)

### TDD specification
- tdd=true 태스크 스펙 명시: **yes** (T5/T6/T7 클래스+메서드 표)
- tdd=false 태스크 산출물 명시: **yes** — T1 이 tdd=false 로 재분류, 변경 파일 2개+완료조건 명시
- TDD 분류 합리: **yes** — F-2 해소. 1줄 설정 wiring(T1)은 RED 단계 부적합 → tdd=false 가 타당. domain_risk 는 유지

### dependency ordering
- layer 의존 순서 준수: **yes** (의존 그래프 line 305-320, T8→T1 추가로 F-4 해소)
- Fake 선행: **n/a**
- orphan port 없음: **n/a**

### architecture fit
- ARCHITECTURE layer 규칙 충돌 없음: **yes** (core/common/metrics 는 PaymentQuarantineMetrics 선례, wiring 은 infrastructure/config 한정)
- 모듈 호출 port 경유: **n/a** (관측 계층 전용)
- CONVENTIONS 패턴 준수 계획: **yes** (record() throw-free 노트 line 147 추가)

### artifact
- docs/OBSERVABILITY-COMPLETION-PLAN.md 존재: **yes**

## Findings

### F-6 (minor) — T0 가 T1 wiring 전 시점이라 kafka producer 메트릭 노출 행이 비의미
- checklist_item: task quality / 모든 태스크가 객관적 완료 기준을 가짐 (T0)
- location: docs/OBSERVABILITY-COMPLETION-PLAN.md line 44 (T0 산출표 `kafka_producer_txn_*` 노출 여부 행), 49 (T0 의존: 없음), 71 (T1 의존: T0)
- problem: F-2 (b)안으로 T0 가 순수 측정·wiring 선적용 없음이 됐다. 그러나 `kafka_producer_txn_*` 메트릭은 T1 의 `MicrometerProducerListener` 부착 후에만 존재하므로, T0(wiring 전, 의존 없음) 시점 스냅샷은 항상 "미노출" 로 읽혀 T8 의 패널 expr 결정(노출 시 직접 메트릭 / 미노출 시 fallback)을 fallback 쪽으로 오도한다. §3-7-C 설계(SSOT line 352)는 "리스너 부착 후에도 확인되지 않으면" fallback 이라 명시하므로, 정작 의미 있는 측정 시점은 T1 이후다.
- evidence: KafkaProducerConfig.stockCommittedProducerFactory 에 현재 MicrometerProducerListener 미부착(실측). T1 이 이 1줄을 추가하며 T1.의존=T0. T0.의존=없음 → T0 가 T1 보다 먼저 실행되므로 노출 행은 구조적으로 "미노출".
- suggestion: T0 산출표의 `kafka_producer_txn_*` 노출 여부 행을 "T1 wiring 적용 후 재측정" 으로 명시하거나, 해당 행 측정을 T1 완료 조건으로 이동. (a) exemplar 타이머 측정은 wiring 무관하므로 T0 잔류 가능. minor — §3-7-C fallback 이 안전 기본값이고 T8 이 T1 에 의존하므로 실행 시 자연 재측정됨.

## JSON
```json
{
  "stage": "plan",
  "persona": "critic",
  "round": 2,
  "task_id": null,

  "decision": "pass",
  "reason_summary": "Round 1 major 2건(F-1 pg yml 누락→T4 추가·범위 명시, F-2 T0 wiring 선적용/T1 RED 모순→T0 순수측정+T1 tdd=false 재분류) 코드 실측 기준 실제 해소. minor 5건도 반영, 도메인 사실(canApplyConfirmResult false=6종) 일치. 잔여 minor 1건(T0 가 T1 wiring 전이라 kafka producer 노출 행 비의미)뿐.",

  "checklist": {
    "source": "_shared/checklists/plan-ready.md",
    "items": [
      { "section": "traceability", "item": "PLAN이 topic.md 결정 사항을 참조함", "status": "yes", "evidence": "PLAN line 342-360 D1~D16 추적표, line 326-336 discuss 리스크 교차표" },
      { "section": "traceability", "item": "모든 태스크가 설계 결정에 매핑 (orphan 없음)", "status": "yes", "evidence": "F-1 해소 — T4 에 pg-service yml 추가(line 122-124), exemplar 범위 payment+pg 명시(line 129). D7↔T5 라벨키 단언 테스트(line 160)로 정합" },
      { "section": "task quality", "item": "모든 태스크가 객관적 완료 기준을 가짐", "status": "yes", "evidence": "각 태스크 AC/test green/파일 존재 명시. T0 kafka 노출 행만 T1 전 측정이라 비의미 — F-6 minor" },
      { "section": "task quality", "item": "태스크 크기 ≤ 2시간", "status": "yes", "evidence": "T0~T10 전부 1커밋 단위 분해 가능" },
      { "section": "task quality", "item": "각 태스크에 관련 소스 파일/패턴 언급", "status": "yes", "evidence": "pg yml/payment yml/KafkaConsumer·ProducerConfig/DedupeCleanupWorker x2/PaymentEventStatus 6종 실측 일치" },
      { "section": "TDD specification", "item": "tdd=true 태스크는 테스트 클래스+메서드 스펙 명시", "status": "yes", "evidence": "T5/T6/T7 스펙 표 명시" },
      { "section": "TDD specification", "item": "tdd=false 태스크는 산출물 명시", "status": "yes", "evidence": "T1 tdd=false 재분류 — 변경 파일 2개+완료조건(PaymentEosIntegrationTest --rerun-tasks) 명시 (F-2 해소)" },
      { "section": "TDD specification", "item": "TDD 분류가 합리적", "status": "yes", "evidence": "F-2 해소 — 1줄 설정 wiring 은 RED 부적합 → T1 tdd=false 타당. T0 순수 측정으로 wiring 선적용 모순 제거. domain_risk 유지" },
      { "section": "dependency ordering", "item": "layer 의존 순서 준수", "status": "yes", "evidence": "의존 그래프 line 305-320 역전 없음. F-4 해소 — T8 의존에 T1 추가(line 253)" },
      { "section": "dependency ordering", "item": "Fake 선행", "status": "n/a", "evidence": "Fake 도입 태스크 없음" },
      { "section": "architecture fit", "item": "ARCHITECTURE layer 규칙 충돌 없음", "status": "yes", "evidence": "core/common/metrics 는 PaymentQuarantineMetrics 선례, wiring infrastructure/config 한정. record() throw-free 노트(line 147)" },
      { "section": "artifact", "item": "docs/<TOPIC>-PLAN.md 존재", "status": "yes", "evidence": "docs/OBSERVABILITY-COMPLETION-PLAN.md 존재" }
    ],
    "total": 12,
    "passed": 11,
    "failed": 0,
    "not_applicable": 1
  },

  "scores": {
    "traceability": 0.92,
    "decomposition": 0.90,
    "ordering": 0.88,
    "specificity": 0.88,
    "risk_coverage": 0.86,
    "mean": 0.888
  },

  "findings": [
    {
      "severity": "minor",
      "checklist_item": "task quality / 모든 태스크가 객관적 완료 기준을 가짐 (T0)",
      "location": "docs/OBSERVABILITY-COMPLETION-PLAN.md line 44, 49, 71",
      "problem": "F-2 (b)안으로 T0 가 순수 측정(wiring 선적용 없음, 의존 없음)이 됐으나, kafka_producer_txn_* 메트릭은 T1 의 MicrometerProducerListener 부착 후에만 존재한다. T0 시점 스냅샷은 항상 '미노출' 로 읽혀 T8 패널 expr 결정을 fallback 으로 오도. 의미 있는 측정 시점은 T1 이후(§3-7-C 는 '리스너 부착 후에도 미확인 시' fallback).",
      "evidence": "KafkaProducerConfig.stockCommittedProducerFactory 에 MicrometerProducerListener 현재 미부착(실측). T1 이 추가, T1.의존=T0, T0.의존=없음 → T0 가 먼저 실행되어 노출 행 구조적 '미노출'.",
      "suggestion": "T0 산출표 kafka_producer_txn_* 노출 여부 행을 'T1 wiring 적용 후 재측정' 으로 명시하거나 T1 완료 조건으로 이동. exemplar 타이머 측정은 wiring 무관이라 T0 잔류 가능. §3-7-C fallback 안전 기본값 + T8→T1 의존으로 실행 시 자연 재측정되므로 minor."
    }
  ],

  "previous_round_ref": "plan-critic-1.md",
  "delta": {
    "newly_passed": [
      "모든 태스크가 설계 결정에 매핑 (orphan 없음) — F-1 해소",
      "TDD 분류가 합리적 — F-2 해소 (T1 tdd=false 재분류)",
      "tdd=false 태스크는 산출물 명시 — T1 변경 파일+완료조건",
      "layer 의존 순서 준수 — F-4 해소 (T8→T1)"
    ],
    "newly_failed": [],
    "still_failing": []
  },

  "unstuck_suggestion": null
}
```
