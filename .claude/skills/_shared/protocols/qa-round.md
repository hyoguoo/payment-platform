# qa-round 프로토콜

Critic / Domain Expert 페르소나가 판정 결과를 내놓을 때 따라야 하는 **공통 출력
인터페이스**. 모든 라운드 판정 문서는 이 스키마를 준수한다.

메인 오케스트레이터는 이 JSON의 `decision` 필드를 읽어 라운드 진행/종료를
기계적으로 판정한다.

## 출력 파일 구조

각 라운드 판정 문서(예: `docs/rounds/<topic>/discuss-critic-1.md`)는 다음 형태:

````markdown
# <stage>-<persona>-<round>

<1~3문장 종합 reasoning — 인간이 읽기 위한 요약>

```json
{ ... qa-round 스키마 ... }
```
````

Markdown 본문은 인간 가독성, JSON 블록은 오케스트레이터 파싱 대상.

## JSON 스키마

```json
{
  "stage": "discuss | plan | code | verify",
  "persona": "critic | domain-expert",
  "round": 1,
  "task_id": null,

  "decision": "pass | revise | fail",
  "reason_summary": "1~2 문장 요약",

  "checklist": {
    "source": "_shared/checklists/discuss-ready.md",
    "items": [
      {
        "section": "scope",
        "item": "TOPIC이 UPPER-KEBAB-CASE로 확정됨",
        "status": "yes | no | n/a",
        "evidence": "docs/topics/ASYNC-PAYMENT.md line 1"
      }
    ],
    "total": 18,
    "passed": 15,
    "failed": 2,
    "not_applicable": 1
  },

  "scores": {
    "d1_name": 0.82,
    "d2_name": 0.74,
    "d3_name": 0.90,
    "d4_name": 0.65,
    "d5_name": 0.80,
    "mean": 0.782
  },

  "findings": [
    {
      "severity": "critical | major | minor",
      "checklist_item": "장애 시나리오 최소 3개 식별됨",
      "location": "docs/topics/ASYNC-PAYMENT.md#failure-modes",
      "problem": "장애 시나리오가 2개만 기술됨. 내부 네트워크 단절 또는 메시지 유실 케이스 누락.",
      "evidence": "문서의 '장애 시나리오' 섹션에 항목 2개만 존재",
      "suggestion": "메시지 유실 시 재처리 경로 추가 기술"
    }
  ],

  "previous_round_ref": "discuss-critic-0.md",
  "delta": {
    "newly_passed": ["멱등성 전략 결정됨"],
    "newly_failed": [],
    "still_failing": ["장애 시나리오 최소 3개 식별됨"]
  },

  "unstuck_suggestion": null
}
```

## 필드 규칙

### decision (판정)
- **pass**: 체크리스트 필수 항목이 전부 yes. 라운드 종료 조건 만족.
- **revise**: 일부 실패. 다음 라운드에서 수정하면 pass 가능.
- **fail**: 구조적 결함. 이전 단계로 복귀 필요 (예: code 라운드 fail → execute 재시작 또는 plan 재검토).

판정 규칙:
- 체크리스트 실패 항목 중 `critical` 심각도가 1개 이상 → **fail**
- 체크리스트 실패 항목 중 `major` 심각도만 있음 → **revise**
- 체크리스트 실패 항목이 `minor`만 있거나 `n/a`만 있음 → **pass** (참고 사항으로 기록)

메인 오케스트레이터는 **이 규칙을 기계적으로 적용**해서 판정 필드만 읽는다.

### checklist
- `source`: 어느 체크리스트 파일을 기준으로 판정했는지 경로
- `items`: 실패 항목 + 주요 참고 항목만 기록 (전부 yes인 경우는 생략 가능)
- `total/passed/failed/not_applicable`: 정수 집계

### scores (5차원 보조 점수, 판정 기준 아님)
단계별로 차원이 다르다:

| stage | 차원 |
|---|---|
| discuss | clarity / completeness / risk / testability / fit |
| plan | traceability / decomposition / ordering / specificity / risk-coverage |
| code | correctness / conventions / discipline / test-coverage / domain |
| verify | build-health / doc-sync / archival / pr-quality / state-finality |

각 차원 0.0~1.0, 소수점 둘째 자리까지. `mean`은 자동 계산.
**이 점수는 판정 기준이 아니라 라운드 간 추세 추적용**이다
(예: 0.62 → 0.74 → 0.85로 개선되는지 확인).

### findings
- `severity`: `critical` / `major` / `minor` — decision 판정의 근거
- `location`: 파일 경로 + 라인/섹션 앵커 (있는 만큼)
- `problem`: 무엇이 문제인지
- `evidence`: 왜 그렇게 판단했는지 (근거 없는 의견 금지)
- `suggestion`: 구체적 수정 방향

findings 배열이 비어 있으면 decision은 반드시 `pass`.

### delta (라운드 비교)
Round 1 이후에만 채움. 이전 라운드 대비:
- `newly_passed`: 이번 라운드에 새로 통과한 체크리스트 항목
- `newly_failed`: 이번 라운드에 새로 실패한 항목 (회귀)
- `still_failing`: 지난번에도 실패했고 이번에도 실패한 항목

`still_failing`이 2라운드 이상 유지되면 **교착 신호**. 오케스트레이터가
unstuck-round 주입을 트리거한다.

### unstuck_suggestion
Round 2+ 에서 `still_failing`이 있을 때 Critic이 제안하는 관점 전환:
`"contrarian" | "simplifier" | "researcher" | "hacker" | "architect" | null`

오케스트레이터는 이 필드를 참고해서 unstuck-round를 주입할지 결정.
Round 1에는 항상 `null`.

## 호출 측 준수 사항

Critic / Domain Expert 페르소나는 이 스키마를 따라야 한다. 누락 필드는
오케스트레이터가 파싱 실패로 간주하고 페르소나를 재호출한다 (최대 2회).
3회 실패하면 사용자에게 에스컬레이션.

## 판정 규칙 우선순위

1. 체크리스트 판정(`checklist.items`의 status)이 우선
2. findings의 severity가 판정 규칙에 적용됨
3. scores는 **참고 정보만**이며 판정에 영향 없음
4. 오케스트레이터는 reasoning 텍스트를 읽지 않음 — JSON만 파싱

이 순서는 LLM 변동성을 최소화하기 위한 설계이다.
scores가 높아도 critical finding이 있으면 fail이고, scores가 낮아도 체크리스트
전부 yes면 pass이다. **판정은 결정론적으로 체크리스트에서 나온다**.
