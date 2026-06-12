# plan-critic-1

**Topic**: OBSERVABILITY-COMPLETION
**Round**: 1
**Persona**: Critic

## Reasoning

PLAN 의 결정→태스크 추적(D1~D16 전수 매핑, AC1~AC7 분산 커버)·layer 의존 순서·T0 선행 체인은 대체로 정합하며 경로/메트릭/전제 주장은 코드 실측으로 전부 확인됐다. 다만 두 건의 major 결함이 있다: (1) T4 의 percentiles-histogram 변경 대상에 pg-service yml 이 빠져 `toss.api.call.duration`(pg 소속, 실측 확인) 벤더 latency exemplar 가 무동작 → AC4 가 부분 불만족, AC4↔T4 추적이 끊긴다. (2) T0 가 wiring 2줄을 선적용하는데 T1 이 동일 2줄에 tdd=true 를 부여하면서 revert 절차가 없어 RED(실패 테스트 선행)가 성립 불가 → TDD 명세 규율 붕괴. critical 은 없고 major 2건이므로 **revise**.

## Checklist judgement

### traceability
- PLAN 이 topic.md 결정 참조: **yes** (D1~D16 추적표 line 357-377, AC1~AC7 완료 조건 분산)
- 모든 태스크가 결정에 매핑(orphan 없음): **yes** (T0~T10 전부 D/AC 또는 §5-0 근거)
- 단, **AC4↔T4 매핑 결함**: T4 가 AC4 를 충족 못 함(pg yml 누락) → traceability 실효성 깨짐 (F-1, major)
- **D7↔T5 매핑 부정확**: 추적표(line 367)는 D7→T5 "라벨 검증 테스트"라 하나 T5 테스트 스펙(line 166-178)에 orderId/userId 라벨 금지 단언 없음 (F-3, minor)

### task quality
- 객관적 완료 기준: **yes** (각 태스크 AC/test green/파일 존재 명시)
- 태스크 크기 ≤ 2h: **yes** (전부 1커밋 단위 분해 가능)
- 소스 파일/패턴 언급: **yes** (변경 파일 경로 실재 확인 — KafkaConsumer/ProducerConfig, DedupeCleanupWorker 양 서비스, payment-dashboard.json, 5서비스 yml sampling 키 line 실측 일치)

### TDD specification
- tdd=true 태스크 테스트 클래스+메서드 스펙: **yes** (T1/T5/T6/T7 스펙 명시)
- tdd=false 태스크 산출물 명시: **yes**
- TDD 분류 합리: **partial/no** — T1 이 tdd=true 이나 T0 선적용분 revert 절차 부재로 RED 성립 불가 (F-2, major)

### dependency ordering
- layer 순서(코드→설정→대시보드→검증): **yes** (의존 그래프 line 320-334, 역전 없음)
- Fake 선행: **n/a** (Fake 도입 태스크 없음)
- orphan port 없음: **n/a**
- **T8 의존 누락**: tx coordinator 패널(AC1 데이터)이 T1 wiring 전제인데 T8 deps 에 T1 없음 (F-4, minor — T10 최종 가드로 완화)

### architecture fit
- ARCHITECTURE layer 규칙 충돌 없음: **yes** (core/common/metrics 배치는 PaymentQuarantineMetrics 선례 일치, infrastructure/config wiring 한정)
- 모듈 호출 port/InternalReceiver 경유: **n/a** (관측 계층 전용, 도메인 비참여)
- CONVENTIONS 패턴 준수 계획: **yes** (기존 메트릭 패턴 답습 명시)

### artifact
- docs/OBSERVABILITY-COMPLETION-PLAN.md 존재: **yes**

## Findings

### F-1 (major) — T4 pg-service yml 누락으로 AC4 벤더 latency exemplar 무동작
- checklist_item: traceability / 모든 태스크가 결정에 매핑 (AC4↔T4)
- location: docs/OBSERVABILITY-COMPLETION-PLAN.md line 137-146 (T4 변경 파일), 251 (§3-1 행4 벤더 latency exemplar)
- problem: T4 변경 파일이 payment-service application.yml 만 포함. exemplar 대상 3종 중 `toss.api.call.duration` 은 pg-service 소속이라, pg yml 에 percentiles-histogram 이 추가되지 않으면 벤더 latency 패널 exemplar 점이 찍히지 않아 AC4(exemplar 점 클릭→Tempo 점프)가 벤더 패널에서 불만족.
- evidence: pg-service/.../TossApiMetrics.java:36 Timer `toss.api.call.duration` 는 pg-service 등록(publishPercentiles 만, histogram bucket 없음). pg-service application.yml 에 percentiles-histogram/distribution 블록 0건(grep 확인). Architect 인라인 주석(line 146)과 일치.
- suggestion: T4 변경 파일에 `pg-service/src/main/resources/application.yml` 추가(설계 §1-2 "pg-service yml 만" 허용 범위 내). 더불어 시스템 대시보드 HTTP exemplar(T9) 가 6서비스 대상이면 http.server.requests histogram 이 payment 외 미설정이므로 exemplar 동작 범위(payment 한정 vs 전 서비스)를 T4/T9 에 명시.

### F-2 (major) — T0 wiring 선적용 + T1 tdd=true 의 RED 성립 불가 (TDD 절차 모순)
- checklist_item: TDD specification / TDD 분류가 합리적
- location: docs/OBSERVABILITY-COMPLETION-PLAN.md line 30-31 (T0 작업 2·3 wiring 선적용), 57-86 (T1 tdd=true 동일 2줄 정식 커밋)
- problem: T0 가 D15·D16 프로덕션 코드 2줄을 실측 위해 선적용한다. T1 은 동일 2줄을 tdd=true 로 "정식 커밋"하나, T0 적용분을 워킹트리에서 되돌리는 절차가 명시되지 않아 T1 의 RED(실패 테스트 선행)가 성립하지 않는다 — 테스트가 처음부터 green 으로 통과.
- evidence: KafkaConsumerConfig.kafkaListenerContainerFactory 에 현재 setObservationEnabled 호출 없음(:53-66 실측), stockCommittedProducerFactory 에 MicrometerProducerListener 없음(:60-71 실측) — 즉 T0 선적용 시 코드가 바뀌고, T1 테스트는 그 변경 위에서 작성되므로 RED 불가. Architect 인라인 주석(line 52)이 승급 대상으로 명시.
- suggestion: T0 완료 조건에 "wiring 2줄은 실측 후 워킹트리에서 되돌리고(unstage/revert) T1 에서 test-first 로 재적용" 절차 추가. 또는 T0 를 순수 측정(기존 코드 기준 메트릭명 추정 불가분만)으로 한정하고 wiring 적용을 T1 으로 일원화.

### F-3 (minor) — D7 라벨 금지 불변식이 T5 테스트 스펙에 미반영
- checklist_item: traceability / 결정 매핑 (D7↔T5)
- location: docs/OBSERVABILITY-COMPLETION-PLAN.md line 367 (D7→"T5 라벨 검증 테스트"), 161-178 (T5 테스트 스펙)
- problem: 추적표는 D7(orderId/userId 메트릭 라벨 금지 불변식)을 T5 의 "라벨 검증 테스트"로 매핑하나, T5 테스트 스펙 5개 메서드는 status 라벨 카운터 증가만 검증하고 orderId/userId 부재(라벨 금지)를 단언하지 않는다.
- evidence: T5 테스트 메서드명(record_terminalStatus_counterIncremented 등) 전부 status 카운터 검증. 라벨 키 화이트리스트/금지 단언 없음.
- suggestion: T5 에 "카운터 라벨이 status 단일이고 orderId/userId 미포함" 단언 메서드 추가, 또는 추적표에서 D7→T5 표현을 "status 단일 라벨 사용(라벨 금지 불변식 준수 설계)"로 정정.

### F-4 (minor) — T8 의존에 T1 누락
- checklist_item: dependency ordering / layer 의존 순서 준수
- location: docs/OBSERVABILITY-COMPLETION-PLAN.md line 265 (T8 의존), 254 (§3-1 행7 tx coordinator 패널)
- problem: T8 비즈니스 대시보드 행7 의 Kafka tx coordinator 패널은 T1(D15 wiring)이 커밋돼야 `kafka_producer_txn_*` 가 노출되어 데이터가 렌더된다. 그러나 T8 deps 는 T0/T4/T5/T6/T7 만 명시하고 T1 누락.
- evidence: T8 line 265 의존 목록에 T1 없음. JSON 작성 자체는 T0 메트릭명으로 충분하나 AC1(전 패널 데이터 렌더)은 T1 전제. Architect 인라인 주석(line 267)과 일치.
- suggestion: T8 의존에 T1 추가하거나, T8 완료 조건에서 tx coordinator 패널 데이터 렌더(AC1)를 빼고 T10 최종 가드로 위임 명시.

### F-5 (minor) — percentiles-histogram 키는 Micrometer 등록명 기준
- checklist_item: task quality / 소스 파일·패턴 언급
- location: docs/OBSERVABILITY-COMPLETION-PLAN.md line 138 (`payment_transition_duration_seconds`)
- problem: T4 대상 목록의 `payment_transition_duration_seconds` 는 Prometheus 렌더명. percentiles-histogram 설정 키는 Micrometer 등록명 기준이어야 함 — T0 산출 참조로 커버되나 기입 시 혼동 주의.
- evidence: PaymentTransitionMetrics.java:47 Timer 등록명과 Prometheus 노출명(suffix 변환) 상이. Architect 인라인 주석(line 147)과 일치.
- suggestion: T4 에 "percentiles-histogram 키는 Micrometer 등록명(T0 스냅샷의 좌측 컬럼) 기준" 1줄 주석 추가.

## JSON

```json
{
  "stage": "plan",
  "persona": "critic",
  "round": 1,
  "task_id": null,

  "decision": "revise",
  "reason_summary": "결정→태스크 추적·layer 의존·T0 선행 체인은 정합하고 경로/메트릭 주장 전부 실측 확인됨. 단 T4 의 pg-service yml 누락으로 AC4 벤더 latency exemplar 무동작(major), T0 wiring 선적용+T1 tdd=true 의 RED 성립 불가(major) 2건으로 revise.",

  "checklist": {
    "source": "_shared/checklists/plan-ready.md",
    "items": [
      { "section": "traceability", "item": "PLAN이 topic.md 결정 사항을 참조함", "status": "yes", "evidence": "PLAN line 357-377 D1~D16 추적표, line 343-354 discuss 리스크 교차표" },
      { "section": "traceability", "item": "모든 태스크가 설계 결정에 매핑 (orphan 없음)", "status": "no", "evidence": "AC4↔T4 매핑 실효 결함 — T4 가 pg yml 누락으로 AC4 충족 불가 (F-1). D7↔T5 라벨테스트 부정확 (F-3)" },
      { "section": "task quality", "item": "모든 태스크가 객관적 완료 기준을 가짐", "status": "yes", "evidence": "각 태스크 AC/test green/파일 존재 명시" },
      { "section": "task quality", "item": "태스크 크기 ≤ 2시간", "status": "yes", "evidence": "T0~T10 전부 1커밋 단위 분해 가능" },
      { "section": "task quality", "item": "각 태스크에 관련 소스 파일/패턴 언급", "status": "yes", "evidence": "변경 파일 경로 실재 확인 (KafkaConsumer/ProducerConfig, DedupeCleanupWorker x2, payment-dashboard.json, 5서비스 sampling 키 line 일치)" },
      { "section": "TDD specification", "item": "tdd=true 태스크는 테스트 클래스+메서드 스펙 명시", "status": "yes", "evidence": "T1/T5/T6/T7 스펙 표 명시" },
      { "section": "TDD specification", "item": "tdd=false 태스크는 산출물 명시", "status": "yes", "evidence": "T0/T2/T3/T4/T8/T9/T10 산출물 명시" },
      { "section": "TDD specification", "item": "TDD 분류가 합리적", "status": "no", "evidence": "T1 tdd=true 이나 T0 wiring 선적용 revert 절차 부재로 RED 불가 (F-2)" },
      { "section": "dependency ordering", "item": "layer 의존 순서 준수", "status": "yes", "evidence": "의존 그래프 line 320-334 코드→설정→대시보드→검증 역전 없음. 단 T8↔T1 의존 누락 minor (F-4)" },
      { "section": "dependency ordering", "item": "Fake 선행", "status": "n/a", "evidence": "Fake 도입 태스크 없음" },
      { "section": "architecture fit", "item": "ARCHITECTURE layer 규칙 충돌 없음", "status": "yes", "evidence": "core/common/metrics 배치 PaymentQuarantineMetrics 선례 일치, wiring infrastructure/config 한정" },
      { "section": "artifact", "item": "docs/<TOPIC>-PLAN.md 존재", "status": "yes", "evidence": "docs/OBSERVABILITY-COMPLETION-PLAN.md 존재" }
    ],
    "total": 12,
    "passed": 8,
    "failed": 3,
    "not_applicable": 1
  },

  "scores": {
    "traceability": 0.78,
    "decomposition": 0.88,
    "ordering": 0.82,
    "specificity": 0.85,
    "risk_coverage": 0.80,
    "mean": 0.826
  },

  "findings": [
    {
      "severity": "major",
      "checklist_item": "traceability / 모든 태스크가 결정에 매핑 (AC4↔T4)",
      "location": "docs/OBSERVABILITY-COMPLETION-PLAN.md line 137-146, 251",
      "problem": "T4 변경 파일이 payment-service yml 만 포함. exemplar 대상 toss.api.call.duration 은 pg-service 소속이라 pg yml 에 percentiles-histogram 미추가 시 벤더 latency 패널 exemplar 무동작 → AC4 부분 불만족.",
      "evidence": "pg-service/.../TossApiMetrics.java:36 Timer 등록 (publishPercentiles 만, histogram 없음), pg yml percentiles-histogram 0건. Architect 주석 PLAN line 146 일치.",
      "suggestion": "T4 변경 파일에 pg-service/src/main/resources/application.yml 추가. T9 HTTP exemplar 의 서비스 범위(payment 한정 vs 전 서비스)도 명시."
    },
    {
      "severity": "major",
      "checklist_item": "TDD specification / TDD 분류가 합리적",
      "location": "docs/OBSERVABILITY-COMPLETION-PLAN.md line 30-31, 57-86",
      "problem": "T0 가 D15·D16 wiring 2줄을 선적용하는데 T1(tdd=true)이 동일 2줄을 정식 커밋한다. T0 적용분 revert 절차 부재로 T1 의 RED(실패 테스트 선행)가 성립 불가 — 테스트가 처음부터 green.",
      "evidence": "KafkaConsumerConfig:53-66 setObservationEnabled 미호출, stockCommittedProducerFactory:60-71 MicrometerProducerListener 없음 — T0 선적용 시 코드 변경, T1 테스트는 green 통과. Architect 주석 PLAN line 52 승급 대상.",
      "suggestion": "T0 완료 조건에 '실측 후 wiring 2줄 워킹트리 revert, T1 에서 test-first 재적용' 명시. 또는 T0 를 측정 한정, wiring 을 T1 으로 일원화."
    },
    {
      "severity": "minor",
      "checklist_item": "traceability / 결정 매핑 (D7↔T5)",
      "location": "docs/OBSERVABILITY-COMPLETION-PLAN.md line 367, 161-178",
      "problem": "추적표는 D7(라벨 금지 불변식)을 T5 라벨 검증 테스트로 매핑하나, T5 테스트 스펙은 status 카운터 증가만 검증하고 라벨 금지 단언 없음.",
      "evidence": "T5 테스트 메서드 5개 전부 status 카운터 검증. orderId/userId 부재 단언 없음.",
      "suggestion": "T5 에 라벨 화이트리스트 단언 추가 또는 추적표 표현 정정."
    },
    {
      "severity": "minor",
      "checklist_item": "dependency ordering / layer 의존 순서",
      "location": "docs/OBSERVABILITY-COMPLETION-PLAN.md line 265, 254",
      "problem": "T8 tx coordinator 패널(AC1 데이터)이 T1 wiring 전제인데 T8 의존에 T1 누락.",
      "evidence": "T8 line 265 의존 목록 T0/T4/T5/T6/T7 만. Architect 주석 line 267 일치.",
      "suggestion": "T8 의존에 T1 추가 또는 tx coordinator 패널 데이터 렌더 판정을 T10 으로 위임 명시."
    },
    {
      "severity": "minor",
      "checklist_item": "task quality / 소스 파일·패턴 언급",
      "location": "docs/OBSERVABILITY-COMPLETION-PLAN.md line 138",
      "problem": "percentiles-histogram 키는 Micrometer 등록명 기준인데 대상 목록에 Prometheus 렌더명(payment_transition_duration_seconds) 기입 — 혼동 가능.",
      "evidence": "PaymentTransitionMetrics.java:47 등록명과 Prometheus 노출명 상이. Architect 주석 line 147 일치.",
      "suggestion": "T4 에 'percentiles-histogram 키는 Micrometer 등록명 기준' 주석 추가."
    }
  ],

  "previous_round_ref": null,
  "delta": null,

  "unstuck_suggestion": null
}
```
