# discuss-critic-3

**Topic**: OBSERVABILITY-COMPLETION
**Round**: 3
**Persona**: Critic

## Reasoning
Round 3 의 핵심 변경(span 비즈니스 속성 부착 = 구 D8/D9 제거, D2 를 로그 기반 추적 진입으로 재정의, D16 명분을 "컨슈머 로그 traceId 연속성"으로 재서술)을 문서 정합성 + 코드 실측으로 전수 대조했다. (a) span 제거가 §1-2/§1-3/§3-3/§4 D2·D8/AC3/§5-0/§5-2 PII/요약 브리핑 전반에 일관 반영됐고, D8 잔재는 추적성 기록(line 372 취소선, line 280·444 기각 대안 보존)뿐 — 능동 설계 표면에 남은 부착 코드 0. (b) D16 존치 근거가 §3-3 + §2 공백 4번 + D16 표에 메커니즘(setObservationEnabled 1줄 → ObservationRegistry 자체 조회 → traceparent 부모 연속 + MDC traceId 복원) + 파일·라인(KafkaConsumerConfig.kafkaListenerContainerFactory 실측: setObservationEnabled 미호출 확인) + 검증(AC3 컨슈머발 케이스·§5-0 T0)으로 plan 이관 가능 수준. (c) Loki 단일 경로 한계가 §6-1 에 "Loki 다운 시 진입 수단 없음·paymentKey 는 로그/DB 역변환" 으로 명시. 코드 실측: ConfirmedEventConsumer:50-51 orderId LogFmt 로깅, logback `%X{traceId:-N/A}`, Loki derivedFields `traceId:(...)` → tempo, exemplar-storage flag 부재·Tempo serviceMap 미설정 모두 문서 주장과 일치. Gate 16항목 yes/n/a, critical/major 없음 → pass.

## Checklist judgement

### scope
- TOPIC UPPER-KEBAB-CASE: **yes** — `# OBSERVABILITY-COMPLETION` (line 1)
- 모듈/패키지 경계 명시: **yes** — §1-2 표(line 187-194). span 제거로 표에서 core/common/trace 헬퍼·어댑터 2곳 항목 사라지고 Kafka wiring 2줄(D15/D16)만 잔존, 정합
- non-goals ≥1: **yes** — §1-3 비범위 6건(line 200-205) + D1/D2/D4/D5. span 부착이 명시적 non-goal 로 승격(line 204)
- 범위 밖 이슈 TODOS 위임 or 포함: **yes** — §6-6 위임 6건(line 449), (f) D16 회귀 가드 자동화 신규 추가

### design decisions
- hexagonal layer 배치 명시: **yes** — §1-2 표 line 192(infrastructure/config Kafka wiring 2줄·core/common/metrics·infrastructure/scheduler). 코드대조: KafkaConsumerConfig.kafkaListenerContainerFactory setObservationEnabled 미호출, KafkaProducerConfig.stockCommittedProducerFactory MicrometerProducerListener 미부착 실측 일치
- 포트 인터페이스 위치: **n/a** — 신규 포트 0. span 헬퍼 제거로 core/common/trace 신규 클래스도 소멸, 남은 건 기존 config 빈 수정 + 메트릭 카운터
- 새 상태→전이 다이어그램: **n/a** — §4 상태전이(line 383) read-only. D13 은 기존 noop 분기 관측
- 전체 결제 흐름 호환성 검토: **yes** — §4(line 387) 계약 불변·Kafka wiring 2줄이 EOS TX wiring 직교, 샘플링 1.0 은 export 만 활성

### acceptance criteria
- 성공 조건 관찰 가능 형태: **yes** — §5-3 AC1~AC7(line 427-433). AC3 이 로그 기반 진입(Loki orderId→derivedField→Tempo)으로 재정의되고 컨슈머발 케이스(D16) 포함, AC4 exemplar·AC7 gradlew green
- 실패 관찰 수단 명시: **yes** — §5-3(line 435) 패널 No data/UI 동선/exit code/테스트결과 + S1~S5

### verification plan
- 테스트 계층 결정: **yes** — §5-4(line 437-440) 단위(카운터 3종 — span never-throw 테스트는 span 제거로 자연 소멸), 통합/k6 안 함(D6)
- 벤치마크 지표(필요 시): **n/a** — 측정 무관 완결(D6), env override 보존만 §3-6/S3

### artifact
- "결정 사항" 섹션 존재: **yes** — §4 D1~D16 표(line 354-379), D8/D9 취소선 추적성 유지

### domain risk (Domain Expert 전용 — Critic 참고 판정)
- 멱등성 전략: **yes** — §5-2 멱등성(line 419)
- 장애 시나리오 ≥3: **yes** — §5-1 S1~S5(line 411-415) 5건
- 재시도 정책: **yes** — §5-2 재시도(line 420)
- PII 검토: **yes** — §5-2 PII(line 421) — span 제거로 트레이스 신규 비즈니스 값 0, 정합 강화

## Findings
(critical/major 없음 → pass.)

- **minor** / scope / location: OBSERVABILITY-COMPLETION.md 사전 브리핑 §3 line 66, §4 line 76
  - problem: 사전 브리핑의 "이번 discuss에서 결정하려는 것"·"열린 질문" 에 span 비즈니스 속성 항목이 여전히 열린 질문으로 서술돼 있어, Round 3 최종 결정(span 부착 안 함, D2)과 표면상 어긋나 보인다. 사전 브리핑은 discuss 시작 시점 스냅샷이라 역사 기록으로는 타당하나, 재개 시 혼동 여지.
  - evidence: line 66 "span 비즈니스 속성: 어떤 키를 붙일지...어디서 붙일지", line 76 "(질문) span 비즈니스 속성에...값을 넣어도 되는지" — 둘 다 요약 브리핑(line 88·141)·D2(line 361)의 "부착 안 함" 확정과 대비. 문서 내 D2/D8/§3-3 가 최종 판정이며 사전 브리핑은 갱신 대상 아님이 line 84 요약 브리핑 머리말로 구분돼 있음.
  - suggestion: 사전 브리핑 해당 항목에 "→ Round 3 D2 로 부착 안 함 확정" 1줄 각주를 달면 재개 시 정합 명확. discuss gate 판정 무영향(요약 브리핑·설계 §1~§6 가 최종 SoT, 거기엔 잔재 없음).

- **minor** / design decisions / location: OBSERVABILITY-COMPLETION.md §2 line 235-236
  - problem: Kafka config 인용 경로 패키지가 `com.hyoguoo.paymentservice.…` 가 아닌 `payment-service/.../infrastructure/config/...` 약식으로 적혀 정확한 패키지(`com.hyoguoo.paymentplatform.payment`) 가 본문에 명시되지 않음. Round 2 minor 와 동류이나 파일명·라인·동작 주장은 코드 실측과 일치.
  - evidence: 실측 — payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/config/KafkaConsumerConfig.java kafkaListenerContainerFactory 에 setObservationEnabled 미호출, KafkaProducerConfig.stockCommittedProducerFactory 에 DefaultKafkaProducerFactory 생성 후 MicrometerProducerListener addListener 0건 확인.
  - suggestion: plan T0 wiring 적용 시 정확한 패키지로 경로 고정. gate 무영향.

## JSON
```json
{
  "stage": "discuss",
  "persona": "critic",
  "round": 3,
  "task_id": null,

  "decision": "pass",
  "reason_summary": "Round 3 변경(span 부착 구 D8/D9 제거·D2 로그 기반 진입 재정의·D16 명분 재서술)을 문서 정합 + 코드 실측 전수 대조해 검증. (a) span 제거가 §1-2/§1-3/§3-3/§4/AC3/§5-0/PII/요약브리핑 전반 일관, D8 잔재는 추적성 기록뿐(능동 설계 표면 0). (b) D16 존치 근거가 §3-3+§2공백4+D16표에 메커니즘·파일라인·검증(AC3·T0)으로 plan 이관 가능. (c) Loki 단일경로 한계 §6-1 명시. 코드 실측 전부 일치. Gate 16항목 yes/n/a, critical/major 없음.",

  "checklist": {
    "source": "_shared/checklists/discuss-ready.md",
    "items": [
      {"section": "scope", "item": "TOPIC UPPER-KEBAB-CASE 확정", "status": "yes", "evidence": "line 1 # OBSERVABILITY-COMPLETION"},
      {"section": "scope", "item": "모듈/패키지 경계 명시", "status": "yes", "evidence": "§1-2 표 line 187-194; span 제거로 trace 헬퍼·어댑터 항목 소멸, Kafka wiring 2줄(D15/D16)만 잔존"},
      {"section": "scope", "item": "non-goals 최소 1개", "status": "yes", "evidence": "§1-3 비범위 6건 line 200-205, span 부착 명시적 non-goal 승격 line 204"},
      {"section": "scope", "item": "범위 밖 이슈 TODOS 위임 or 포함", "status": "yes", "evidence": "§6-6 위임 6건 line 449, (f) D16 회귀가드 자동화 신규"},
      {"section": "design decisions", "item": "hexagonal layer 배치 명시", "status": "yes", "evidence": "§1-2 표 line 192 infrastructure/config·core/common/metrics·infrastructure/scheduler; 코드대조 KafkaConsumerConfig setObservationEnabled 미호출·KafkaProducerConfig MicrometerProducerListener 미부착 실측 일치"},
      {"section": "design decisions", "item": "포트 인터페이스 위치 결정", "status": "n/a", "evidence": "신규 포트 0; span 헬퍼 제거로 core/common/trace 신규 클래스 소멸, 기존 config 빈 수정 + 메트릭 카운터만"},
      {"section": "design decisions", "item": "새 상태→전이 다이어그램", "status": "n/a", "evidence": "§4 line 383 상태머신 read-only; D13 은 canApplyConfirmResult==false noop 분기 관측"},
      {"section": "design decisions", "item": "전체 결제 흐름 호환성 검토", "status": "yes", "evidence": "§4 line 387 계약 불변·Kafka wiring 2줄 EOS TX wiring 직교·샘플링 1.0 export 만 활성"},
      {"section": "acceptance criteria", "item": "성공 조건 관찰 가능 형태", "status": "yes", "evidence": "§5-3 AC1-AC7 line 427-433; AC3 로그 기반 진입(Loki orderId→derivedField→Tempo) + 컨슈머발 케이스(D16), AC4 exemplar, AC7 gradlew green"},
      {"section": "acceptance criteria", "item": "실패 관찰 수단 명시", "status": "yes", "evidence": "§5-3 line 435 패널 No data/UI 동선/exit code/테스트결과 + S1-S5"},
      {"section": "verification plan", "item": "테스트 계층 결정", "status": "yes", "evidence": "§5-4 line 437-440 단위 카운터 3종(span never-throw 테스트는 span 제거로 소멸), 통합/k6 안 함 D6"},
      {"section": "verification plan", "item": "벤치마크 지표(필요 시)", "status": "n/a", "evidence": "측정 무관 완결 D6; env override 보존만 §3-6/S3"},
      {"section": "artifact", "item": "결정 사항 섹션 존재", "status": "yes", "evidence": "§4 D1-D16 표 line 354-379, D8/D9 취소선 추적성 유지"},
      {"section": "domain risk", "item": "멱등성 전략 결정", "status": "yes", "evidence": "§5-2 line 419 신규 신호 멱등 비참여"},
      {"section": "domain risk", "item": "장애 시나리오 최소 3개", "status": "yes", "evidence": "§5-1 S1-S5 line 411-415 5건"},
      {"section": "domain risk", "item": "재시도 정책 정의", "status": "yes", "evidence": "§5-2 line 420 신규 재시도 경로 없음 + OTLP 배치 재시도"},
      {"section": "domain risk", "item": "PII 검토", "status": "yes", "evidence": "§5-2 line 421 span 제거로 트레이스 신규 비즈니스 값 0, 로그 orderId 기존분"}
    ],
    "total": 17,
    "passed": 13,
    "failed": 0,
    "not_applicable": 4
  },

  "scores": {
    "clarity": 0.93,
    "completeness": 0.94,
    "risk": 0.91,
    "testability": 0.91,
    "fit": 0.95,
    "mean": 0.928
  },

  "findings": [
    {
      "severity": "minor",
      "checklist_item": "non-goals 최소 1개 / scope 정합",
      "location": "OBSERVABILITY-COMPLETION.md 사전 브리핑 §3 line 66, §4 line 76",
      "problem": "사전 브리핑의 '결정하려는 것'·'열린 질문' 에 span 비즈니스 속성이 여전히 미결로 서술돼 Round 3 최종 결정(D2 부착 안 함)과 표면상 어긋나 보임. 사전 브리핑은 discuss 시작 스냅샷이라 역사로는 타당하나 재개 시 혼동 여지.",
      "evidence": "line 66 'span 비즈니스 속성: 어떤 키를 붙일지...어디서 붙일지', line 76 '(질문) span 비즈니스 속성에...값을 넣어도 되는지' vs 요약 브리핑 line 88·141·D2 line 361 '부착 안 함' 확정. line 84 요약 브리핑 머리말이 사전/요약 구분.",
      "suggestion": "사전 브리핑 해당 항목에 '→ Round 3 D2 부착 안 함 확정' 각주 1줄. 요약 브리핑·설계 §1~§6 가 최종 SoT 라 gate 무영향."
    },
    {
      "severity": "minor",
      "checklist_item": "hexagonal layer 배치 명시",
      "location": "OBSERVABILITY-COMPLETION.md §2 line 235-236",
      "problem": "Kafka config 인용 경로가 약식(payment-service/.../infrastructure/config/...)이라 정확한 패키지 com.hyoguoo.paymentplatform.payment 가 본문 미명시. Round 2 minor 동류, 파일명/라인/동작 주장은 코드 실측 일치.",
      "evidence": "실측 — KafkaConsumerConfig.kafkaListenerContainerFactory setObservationEnabled 미호출, KafkaProducerConfig.stockCommittedProducerFactory DefaultKafkaProducerFactory 생성 후 MicrometerProducerListener addListener 0건 확인.",
      "suggestion": "plan T0 wiring 적용 시 정확한 패키지로 경로 고정. gate 무영향."
    }
  ],

  "previous_round_ref": "discuss-critic-2.md",
  "delta": {
    "newly_passed": [],
    "newly_failed": [],
    "still_failing": []
  },

  "unstuck_suggestion": null
}
```
