---
name: workflow-plan-review
description: >
  payment-platform 워크플로우의 plan-review 단계를 실행한다.
  plan 완료 직후 자동으로 실행되거나, "plan-review 시작"을 말할 때 사용한다.
  PLAN.md + topics 문서를 자동 로드하여 Critic 페르소나로 검수하고 STATE.md를 갱신한다.
---

# Workflow Plan Review 오케스트레이터

`plan` 단계와 `execute` 사이의 독립 검수 게이트. `plan-round`의 Critic 결과만으로는
부족할 수 있으므로 한 번 더 전체 검수를 수행한다.

## 1. 컨텍스트 로드
- `docs/STATE.md` → TOPIC 파악
- `docs/<TOPIC>-PLAN.md` (검수 대상)
- `docs/topics/<TOPIC>.md` (설계 일치 검사)
- `docs/context/ARCHITECTURE.md`, `TESTING.md`
- `docs/rounds/<topic>/plan-*.md` (이전 판정 이력)

## 2. 검수 실행 (1 라운드, 토론 없음)
**서브에이전트로만 실행.** 메인 스레드에서 PLAN.md를 읽고 직접 판정 금지.
```
Agent(subagent_type="critic", prompt="plan-review 1회. 체크리스트: plan-ready.md의 Gate checklist 섹션만. 출력: docs/rounds/<topic>/plan-review-critic-1.md")
```
토론 없음 = 단일 Critic 판정만. `decision` 필드만 기계적으로 읽는다.

## 3. 결과에 따른 STATE.md 처리

| Critic decision | STATE.md | 다음 행동 |
|---|---|---|
| `fail` (critical) | stage → `discuss` | PLAN.md 상단에 `<!-- [REVIEW FAILED - CRITICAL] -->` 추가, 재논의 안내 |
| `revise` (major) | 변경 없음 | **Planner dispatch**로 PLAN.md 수정 후 Step 2 재실행 |
| `pass` | stage → `execute`, 활성 태스크 → Task 1 | `workflow-execute` 진행 |

### revise 경로 — Planner 재dispatch
메인 스레드에서 PLAN.md를 직접 수정하지 말 것. findings를 Planner에게 넘겨 수정:
```
Agent(subagent_type="planner",
      prompt="plan-review-critic-1 findings 반영. 대상: docs/<TOPIC>-PLAN.md.
              입력: docs/rounds/<topic>/plan-review-critic-1.md 의 major findings.
              수정 후 traceability 테이블 재확인.")
```
Planner 완료 → Step 2(Critic 재dispatch) 재실행. 2회 revise 반복 시 사용자 에스컬레이션.
