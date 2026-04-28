# plan-review-gate

**Topic**: MSA-TRANSITION
**Round**: gate (경량 정합성 검증)
**Persona**: Plan Reviewer

## Reasoning

discuss Round 5 pass + plan Round 2 pass 상태에서 `docs/topics/MSA-TRANSITION.md`(설계 SSOT)와 `docs/MSA-TRANSITION-PLAN.md`(64 태스크) 양 문서 간 정합성을 A~F 섹션 기준으로 경량 검증했다. ADR-01~31 전수·이행 원칙 8개·돈 경로 태스크·의존 순서가 모두 정합하며, 2건의 minor 불일치(보상 토픽 이름 혼용, 브리핑 요약 숫자 오기재)만 발견되었다. major 이상 지적이 없으므로 pass로 판정한다.

## Checklist judgement

| 섹션 | 항목 | 판정 | 근거 |
|---|---|---|---|
| traceability | PLAN.md가 topic.md 결정 사항 참조 | yes | PLAN 요약 브리핑 §3 ADR→Task 매핑표, 추적 테이블, ADR→태스크 커버리지 테이블 전수 확인 |
| traceability | 모든 태스크가 설계 결정 중 하나 이상에 매핑됨 (orphan 없음) | yes | PLAN line 1414~1453 ADR→태스크 커버리지 테이블에 전 태스크 매핑 확인 |
| task quality | 모든 태스크가 객관적 완료 기준 보유 | yes | tdd=true 태스크는 테스트 클래스+메서드 명시, tdd=false는 산출물 파일 경로 명시 |
| task quality | 태스크 크기 ≤ 2시간 | yes | T1-11·T2a-05 각 3분해로 2h 초과 태스크 해소 (Round 2 변경 로그 M-1) |
| task quality | 관련 소스 파일/패턴 언급 | yes | 전 태스크 산출물 섹션에 파일 경로 명시 |
| TDD specification | tdd=true 태스크에 테스트 클래스+메서드 명시 | yes | T0-02, T1-04~T1-17 전수 확인 |
| TDD specification | tdd=false 태스크에 산출물 명시 | yes | T0-01, T0-03 등 전수 확인 |
| TDD specification | TDD 분류 합리성 | yes | 상태 전이·멱등성·edge case 태스크는 전부 tdd=true |
| dependency ordering | layer 의존 순서 준수 | yes | T1-01(port) → T1-04(domain) → T1-05(application) → T1-11a(infrastructure) 순서 정합 |
| dependency ordering | Fake가 소비자 앞 | yes | T1-03→T1-04이후, T2a-03→T2a-05이후, T3-03→T3-04이후 |
| dependency ordering | orphan port 없음 | yes | 전 port에 구현/Fake 대응 태스크 존재 |
| architecture fit | ARCHITECTURE.md layer 규칙 충돌 없음 | yes | §2-6 hexagonal 배치 원칙에 따라 port/domain/application/infrastructure/controller 계층 준수 |
| architecture fit | 모듈 간 호출이 port/InternalReceiver 경유 | yes | KafkaTemplate 직접 호출 금지, port 인터페이스 경유 명시 |
| architecture fit | CONVENTIONS.md Lombok/예외/로깅 패턴 | yes | 태스크 본문에 Lombok·LogFmt 관련 ADR-18·ADR-19 참조 |
| artifact | docs/MSA-TRANSITION-PLAN.md 존재 | yes | 파일 존재 확인 |
| domain risk | discuss 식별 domain risk 대응 태스크 존재 | yes | 추적 테이블 line 1383~1410 전 리스크 대응 태스크 매핑 |
| domain risk | 중복 방지 체크 계획됨 | yes | T1-09 LVAL, T2b-05 2자 대조, T2a-06 inbox dedupe |
| domain risk | 재시도 안전성 검증 태스크 존재 | yes | T2b-01 재시도 정책, T2b-02 DLQ consumer, T4-01 Toxiproxy 8종 |

## Findings

### F-C1 (minor)
- **severity**: minor
- **checklist_item**: 용어·번호 일치 (C 섹션)
- **location**: `docs/MSA-TRANSITION-PLAN.md` line 901(T2b-02), line 1186~1196(T3-04b), line 1203(T3-05)
- **problem**: 보상 이벤트 토픽 이름이 세 곳에서 혼용됨. T2b-02 본문/테스트에서 `stock.events.compensation`, T3-04b 산출물 테스트에서 `stock.events.restore`, T3-05 제목·Phase 3 목적 설명에서 `stock.restore`로 표기.
- **evidence**: PLAN line 901: `stock.events.compensation row INSERT`. PLAN line 1193: `stock.events.restore row 1건 INSERT`. PLAN line 1203: `` `stock.restore` consumer ``. topic.md line 355: `stock.events.compensation`, line 298: `stock.restore 이벤트 발행`으로 SSOT도 혼용.
- **suggestion**: T2b-02(pg-service DLQ consumer가 INSERT하는 보상 row)와 T3-04b(payment-service FAILED 전이 시 INSERT하는 보상 row)가 같은 토픽인지 다른 토픽인지 명확히 구분. 단일 토픽이라면 `stock.events.compensation` 또는 `stock.events.restore` 중 하나로 전 문서에 통일. T2d-02 토픽 네이밍 규약 확정 태스크에서 이 보상 토픽을 명시적으로 포함시키는 것이 안전.

### F-F1 (minor)
- **severity**: minor
- **checklist_item**: 요약 브리핑 표현 정합 (F 섹션)
- **location**: `docs/MSA-TRANSITION-PLAN.md` line 119 vs line 1461
- **problem**: 요약 브리핑 합계 줄에 "domain_risk=true 38건, 의존 엣지 52개"로 기재되어 있으나, 반환 지표 섹션에는 "domain_risk=true 태스크 개수 43건, 의존 엣지 총합 57개"로 기재되어 불일치.
- **evidence**: PLAN line 119: `64 태스크 (domain_risk=true 38건, 의존 엣지 52개)`. PLAN line 1460~1461: `43` 건, `57` 엣지.
- **suggestion**: 반환 지표 섹션의 43건·57엣지가 개별 태스크 domain_risk 필드와 depends 수를 집계한 값이므로 더 신뢰할 수 있다. 요약 브리핑 line 119를 `domain_risk=true 43건, 의존 엣지 57개`로 수정.

## A~F 섹션 요약

| 섹션 | 검증 대상 | 지적 건수 | 비고 |
|---|---|---|---|
| A | 설계 → 태스크 누락 (orphan 결정) | 0 | ADR-01~31, §2~§9 전수 매핑 확인 |
| B | 태스크 → 설계 orphan | 0 | ADR→태스크 커버리지 테이블에 전 태스크 매핑 |
| C | 용어·번호 일치 | 1 (minor) | 보상 토픽 이름 3종 혼용(stock.events.compensation/stock.events.restore/stock.restore) |
| D | 의존 순서 정합성 | 0 | Phase 0→1→2a→2b→2c→2d→3→4→5, Fake 선행, layer 순서 정합 |
| E | 돈 경로 태스크 존재 | 0 | T1-09, T2b-01~T2b-05, T3-04b, T1-05, T1-06 전수 확인 |
| F | 요약 브리핑 표현 정합 | 1 (minor) | domain_risk 건수(38 vs 43), 의존 엣지 수(52 vs 57) 브리핑/반환지표 불일치 |

## JSON

```json
{
  "stage": "plan-review",
  "persona": "plan-reviewer",
  "round": 5,
  "task_id": null,

  "decision": "pass",
  "reason_summary": "ADR-01~31 전수·이행 원칙·돈 경로 태스크·의존 순서가 모두 정합. 보상 이벤트 토픽 이름 혼용(stock.events.compensation/restore)과 브리핑 요약 숫자 오기재(38건→43건, 52→57엣지) 2건의 minor만 발견. major 이상 지적 없으므로 pass.",

  "checklist": {
    "source": "_shared/checklists/plan-ready.md",
    "items": [
      {
        "section": "traceability",
        "item": "PLAN.md가 docs/topics/MSA-TRANSITION.md의 결정 사항을 참조함",
        "status": "yes",
        "evidence": "PLAN 요약 브리핑 §3 ADR→Task 매핑표 + 추적 테이블 + ADR→태스크 커버리지 테이블 전수 확인"
      },
      {
        "section": "traceability",
        "item": "모든 태스크가 설계 결정 중 하나 이상에 매핑됨 (orphan 태스크 없음)",
        "status": "yes",
        "evidence": "PLAN line 1414~1453 ADR→태스크 커버리지 테이블에 전 태스크 매핑 확인"
      },
      {
        "section": "task quality",
        "item": "모든 태스크가 객관적 완료 기준을 가짐",
        "status": "yes",
        "evidence": "tdd=true 태스크 테스트 클래스+메서드 명시, tdd=false 태스크 산출물 파일 경로 명시"
      },
      {
        "section": "task quality",
        "item": "태스크 크기 ≤ 2시간",
        "status": "yes",
        "evidence": "Round 2 변경 로그 M-1: T1-11·T2a-05 각 3분해로 2h 초과 태스크 해소"
      },
      {
        "section": "dependency ordering",
        "item": "layer 의존 순서 준수 (port → domain → application → infrastructure → controller)",
        "status": "yes",
        "evidence": "T1-01(port) → T1-04(domain) → T1-05(application) → T1-11a(infrastructure) 순서 정합"
      },
      {
        "section": "dependency ordering",
        "item": "Fake 구현이 그것을 소비하는 태스크보다 먼저 옴",
        "status": "yes",
        "evidence": "T1-03→T1-04이후, T2a-03→T2a-05이후, T3-03→T3-04이후 depends 순서 확인"
      },
      {
        "section": "artifact",
        "item": "docs/MSA-TRANSITION-PLAN.md 존재",
        "status": "yes",
        "evidence": "파일 확인 완료"
      },
      {
        "section": "domain risk",
        "item": "discuss에서 식별된 domain risk가 각각 대응 태스크를 가짐",
        "status": "yes",
        "evidence": "PLAN line 1383~1410 추적 테이블 전 리스크 대응 태스크 매핑 확인"
      }
    ],
    "total": 18,
    "passed": 18,
    "failed": 0,
    "not_applicable": 0
  },

  "scores": {
    "traceability": 0.97,
    "decomposition": 0.95,
    "ordering": 0.97,
    "specificity": 0.95,
    "risk_coverage": 0.96,
    "mean": 0.96
  },

  "findings": [
    {
      "severity": "minor",
      "checklist_item": "용어·번호 일치 (C 섹션 — 토픽 네이밍)",
      "location": "docs/MSA-TRANSITION-PLAN.md line 901 (T2b-02), line 1193 (T3-04b), line 1203 (T3-05)",
      "problem": "보상 이벤트 토픽 이름이 stock.events.compensation / stock.events.restore / stock.restore 세 가지로 혼용됨. T2b-02(pg-service DLQ consumer)와 T3-04b(payment-service FAILED 전이)가 각각 다른 이름으로 보상 outbox row를 INSERT하며, T3-05 consumer가 구독하는 토픽과의 관계가 명시되지 않음.",
      "evidence": "PLAN line 901: 'stock.events.compensation row INSERT'. PLAN line 1193: 'stock.events.restore row 1건 INSERT'. PLAN line 1203: 'stock.restore consumer'. topic.md line 355: 'stock.events.compensation row INSERT', line 298: 'stock.restore 이벤트 발행'.",
      "suggestion": "T2d-02 토픽 네이밍 규약 확정 태스크에서 보상 토픽을 명시적으로 포함. T2b-02(QUARANTINED 보상)와 T3-04b(FAILED 보상)가 같은 토픽인지 다른 토픽인지 결정하고, 전 문서에 단일 표기로 통일."
    },
    {
      "severity": "minor",
      "checklist_item": "요약 브리핑 표현 정합 (F 섹션 — 숫자 일치)",
      "location": "docs/MSA-TRANSITION-PLAN.md line 119 vs line 1460~1461",
      "problem": "요약 브리핑 합계 줄의 'domain_risk=true 38건, 의존 엣지 52개'가 반환 지표 섹션의 '43건, 57엣지'와 불일치.",
      "evidence": "PLAN line 119: '64 태스크 (domain_risk=true 38건, 의존 엣지 52개)'. PLAN line 1460: 43건 명단 열거. PLAN line 1461: '명시된 depends 관계 총합 = 57'.",
      "suggestion": "요약 브리핑 line 119를 'domain_risk=true 43건, 의존 엣지 57개'로 수정. 반환 지표 섹션의 집계가 개별 태스크 필드를 직접 세었으므로 더 신뢰할 수 있음."
    }
  ],

  "previous_round_ref": "plan-review-4.md",
  "delta": {
    "newly_passed": [],
    "newly_failed": [],
    "still_failing": []
  },

  "unstuck_suggestion": null
}
```
