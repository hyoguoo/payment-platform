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
- **Critic** 페르소나(`_shared/personas/critic.md`)를 **1회 호출**
- 체크리스트: `_shared/checklists/plan-ready.md`
- 출력: `docs/rounds/<topic>/plan-review-critic-1.md` (qa-round 스키마)

토론 없음 = Architect/Domain Expert 재호출 없음. 단일 Critic 판정만.

## 3. 결과에 따른 STATE.md 처리

| Critic decision | STATE.md | 다음 행동 |
|---|---|---|
| `fail` (critical) | stage → `discuss` | PLAN.md 상단에 `<!-- [REVIEW FAILED - CRITICAL] -->` 추가, 재논의 안내 |
| `revise` (major) | 변경 없음 | major findings 수정 요청 → 통과 시 아래로 |
| `pass` | stage → `execute`, 활성 태스크 → Task 1 | `workflow-execute` 진행 |
