---
name: workflow-verify
description: >
  payment-platform 워크플로우의 verify 단계를 실행한다.
  review 완료 후 사용자가 명시적으로 "verify 시작", "테스트 확인", "검증", "최종 확인",
  "작업 마무리", "아카이브", "정리하고 마무리" 등을 요청할 때만 이 스킬을 사용한다.
  review 완료 후 자동으로 실행하지 않는다.
  전체 테스트 → context 갱신 → 아카이브 → STATE 종결 → PR 생성 흐름이다.
---

# Verify 단계 오케스트레이터

`verify-round` 프로토콜을 실행하는 얇은 오케스트레이터.
`.claude/skills/_shared/protocols/verify-round.md`의 Flow를 그대로 수행한다.

---

## 책임 경계

verify 단계는 "판정·실행"과 "결정론적 파일 조작"이 섞여 있어 두 책임을 명확히 분리한다.

| 책임 | 수행 주체 | 대상 |
|---|---|---|
| 테스트 실행 | **Verifier 서브에이전트** | `./gradlew test` |
| 체크리스트 판정 | **Critic 서브에이전트** | `verify-ready.md` Gate 섹션 |
| PR 생성/갱신 | **PR Manager 서브에이전트** | `vc-round.md` |
| context 문서 갱신 | **오케스트레이터** (context-update 스킬) | `docs/context/*.md` |
| `git mv` 아카이브 | **오케스트레이터** | `docs/archive/<topic>/` |
| STATE.md 종결 편집 | **오케스트레이터** | `docs/STATE.md` |
| 최종 `docs:` 커밋 | **오케스트레이터** | 위 세 가지 묶음 |

**메인은 판정·테스트·PR 본문 작성을 절대 대행하지 않는다.** 파일 이동·커밋은 창의성이 없는 결정론적 작업이라 페르소나화 이득이 없어 오케스트레이터가 직접 수행한다.

Gate는 `verify-ready.md`의 **Gate checklist 섹션만** 판정한다.

## 실행 순서

### Step 1 — Verifier dispatch
`Agent(subagent_type="verifier", prompt="./gradlew test")` — JSON 결과만 받는다.
`./gradlew test` 전체 실행.

**실패 처리**:
| 유형 | 처리 |
|---|---|
| 이번 작업 관련 버그 | 즉시 수정 (Rule 1) |
| 기존부터 실패 | `git stash` → 재실행 → 기존 문제면 무시 |
| 아키텍처 변경 필요 | 중단 + 사용자 보고 (Rule 2) |

### Step 2 — Context 문서 갱신
`context-update` 스킬 실행. `git diff main...HEAD --stat`을 시작점으로 범위 최소화.

### Step 3 — 아카이브
```bash
mkdir -p docs/archive/<topic-kebab>
git mv docs/<TOPIC>-PLAN.md docs/archive/<topic-kebab>/<TOPIC>-PLAN.md
git mv docs/topics/<TOPIC>.md docs/archive/<topic-kebab>/<TOPIC>-CONTEXT.md
```
`docs/archive/README.md` 테이블 행 추가.

### Step 3.5 — 완료 브리핑 생성
`docs/archive/<topic-kebab>/COMPLETION-BRIEFING.md` 작성. 다음 구조를 따른다:
```
## 작업 요약
<1~2문장: 무엇을 왜 했는지>

## 핵심 결정
<CONTEXT.md의 D1~D12 중 가장 중요한 3~5개만 발췌. 번호 + 한 줄 요약>

## 상태 머신
<Mermaid stateDiagram-v2 — 변경 전후가 아닌 최종 상태만>

## 복구/처리 플로우
<Mermaid flowchart — 핵심 분기 경로만, 세부 생략>

## 수치
| 항목 | 값 |
|------|---|
| 태스크 | N개 |
| 테스트 | N개 통과 |
| 커밋 | N개 |
| 코드 리뷰 findings | critical N / major N / minor N |
```
**원칙**: CONTEXT.md와 PLAN.md를 다시 읽지 않고도 이 파일만으로 "무엇을, 왜, 어떻게" 파악 가능해야 한다.

### Step 4 — STATE.md 종결
stage → `done`. `.continue-here.md` 존재 시 삭제.

### Step 5 — Critic dispatch
`Agent(subagent_type="critic", prompt="verify-ready.md Gate checklist만 판정. 출력: docs/rounds/<topic>/verify-critic-1.md")`
`decision != pass` → 실패 항목 수정 후 Step 1 재시도.

### Step 6 — 최종 커밋
`commit-round.md` 준수:
```bash
git add docs/STATE.md docs/context/ docs/archive/
git commit -m "docs: <주제> 작업 완료 및 문서 아카이브"
```

### Step 7 — PR Manager dispatch
`Agent(subagent_type="pr-manager", prompt="vc-round.md Step 3/4 수행. 이슈 #N, 브랜치 #N.")`
PR Manager가 push + PR 생성/갱신을 수행한다. 메인 스레드에서 gh 명령 직접 실행 금지.

---

## 완료 알림
```
## 작업 완료
**주제**: <주제>
**완료된 태스크**: N개
**테스트**: 전체 통과
**아카이브**: docs/archive/<topic-kebab>/
**PR**: <PR URL>
```
