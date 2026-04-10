---
name: workflow
description: >
  payment-platform의 discuss → plan → plan-review → execute → review → verify 워크플로우 오케스트레이터.
  docs/<TOPIC>-PLAN.md 파일이 존재하거나, 사용자가 "plan 작성", "execute 시작",
  "워크플로우로", "다음 단계", "세션 재개", "이어서 진행", "어디까지 했지" 등을 말할 때
  반드시 이 스킬을 사용한다. 단순 질문, 빠른 수정, 일회성 코드 변경에는 사용하지 않는다.
---

# Workflow 오케스트레이터

## 핵심 원칙 — 서브에이전트 + 페르소나 격리 (Non-negotiable)

이 워크플로우의 품질은 **판정 페르소나가 서브에이전트로 격리 실행**되는 것에 전적으로 의존한다.

1. **모든 판정·구현 페르소나는 `Agent` 툴로만 실행**한다. 정의는 `.claude/agents/*.md`, 호출은 `subagent_type: "<name>"`. 메인 스레드에서 페르소나를 흉내 내어 체크리스트를 판정하거나 TDD를 수행하면 self-rubber-stamp가 된다.
2. **Interviewer만 예외** — 사용자 실시간 상호작용(AskUserQuestion)이 필요하므로 메인 스레드 실행.
3. **병렬 dispatch 필수** — 같은 라운드의 Critic + Domain Expert는 **단일 메시지에서 동시 호출**. 순차 호출 시 두 번째가 첫 번째 결과로 오염된다.
4. **격리 원칙** — 페르소나는 같은 라운드의 sibling 출력 파일을 Read 하지 않는다.
5. **판정 수용** — 오케스트레이터는 서브에이전트가 저장한 JSON의 `decision` 필드만 기계적으로 읽는다. 재해석·재판정 금지.
6. **Gate vs Post-phase 분리** — `*-ready.md` 체크리스트는 두 섹션으로 나뉜다. 페르소나는 **Gate 섹션만** 판정한다. Post-phase(이슈/브랜치/아카이브/PR/STATE 종결)는 오케스트레이터 책임.

---

## 세션 시작 프로토콜

1. `docs/.continue-here.md` 존재 여부 확인 → 있으면 읽고 내용을 사용자에게 요약한 뒤 파일 삭제
2. `docs/STATE.md` 읽기 → 현재 단계와 활성 태스크 파악
3. 현재 단계에 맞는 서브 스킬로 진행

상태 파악 후 사용자에게 한 줄로 현재 위치를 알린다.
예: "Checkout 멱등성 작업 중 — execute 단계, Task 3 진행 중입니다."

---

## 단계 → 서브 스킬 라우팅

| 현재 단계 | 참조 스킬 |
|-----------|----------|
| idle | `workflow-discuss` (새 작업 시작) |
| discuss | `workflow-discuss` |
| plan | `workflow-plan` |
| plan-review | `workflow-plan-review` |
| execute | `workflow-execute` |
| review | `workflow-review` |
| verify | `workflow-verify` |

사용자가 특정 단계를 명시하면 해당 스킬로 바로 진행한다.
현재 단계가 불명확하거나 `idle`이면 discuss 스킬로 진행한다.
`idle` 상태에서 다음 작업이 명확하지 않을 때는 `docs/context/TODOS.md`를 읽고 항목을 사용자에게 제안한다.

---

## 사용자 브리핑 원칙 (Non-negotiable)

서브에이전트 라운드를 돌리기 **전**과 모든 라운드가 pass한 **후**, 메인 스레드는 사용자에게 **도메인 용어 + Mermaid 플로우차트 포함 브리핑**을 제시한다. 목적: 사용자가 방향을 교정할 수 있는 게이트를 보장한다.

- **사전 브리핑**: 현재 이해한 문제, as-is 플로우차트, 이번 단계에서 결정할 것, 열린 질문
- **완료 브리핑**: 결정된 접근, to-be 플로우차트(as-is와 대비 가능하도록), 핵심 결정 ID, 트레이드오프

각 단계 스킬(`workflow-discuss` 등)에 구체 템플릿이 있다. 메서드명/파일명 대신 도메인 용어를 쓴다.

---

## 단계 완료 후 정지 원칙

각 단계가 끝나면 **반드시 멈추고 사용자의 명시적 확인을 기다린다.** 다음 단계로 자동 진행하지 않는다.

단계 완료 시 출력 형식:
```
## [단계명] 완료

<완료 내용 한 줄 요약>

다음 단계: [다음 단계명] — 계속 진행할까요?
```

예외: 사용자가 명시적으로 "연속으로 진행해", "끝까지 해줘" 등을 요청한 경우에만 자동 진행한다.

---

## STATE.md / .continue-here.md 형식

→ `references/templates.md` 참조

---

## 커밋 타이밍 요약

| 시점 | 포함 파일 | 커밋 타입 |
|------|----------|----------|
| Discuss 완료 | topics 문서 + 라운드 문서 + STATE.md(plan으로) | `docs:` |
| Plan 완료 | PLAN.md + 라운드 문서 + STATE.md(plan-review로) | `docs:` |
| Plan Review 통과 | 라운드 문서 + STATE.md(execute/Task 1로) | `docs:` |
| TDD RED | 실패 테스트 파일만 | `test:` |
| TDD GREEN | 구현 코드 + 테스트 + PLAN.md(체크박스) + STATE.md | `feat:` |
| TDD REFACTOR | 정리된 코드 (변경 있을 때만) | `refactor:` |
| tdd=false 태스크 완료 | 구현 + PLAN.md + STATE.md | `feat:` or `chore:` |
| Review 피드백 수정 | 수정된 파일들 | `refactor:` |
| 세션 중단 | .continue-here.md + 현재 산출물 | `wip:` 등 |
| 전체 작업 완료 | context 갱신 + 아카이브 + STATE.md | `docs:` |

세부 규칙은 `_shared/protocols/commit-round.md` 참조. PR/브랜치/이슈는 `vc-round.md` 참조.
