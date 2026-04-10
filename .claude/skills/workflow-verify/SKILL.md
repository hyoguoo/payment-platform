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
`docs/archive/<topic-kebab>/COMPLETION-BRIEFING.md` 작성.

**목표**: 이 파일 하나만 읽으면 CONTEXT.md, PLAN.md, 코드를 다시 볼 필요 없이 "무엇을, 왜, 어떻게, 결과가 어땠는지" 완전히 파악 가능해야 한다.

**필수 섹션**:
```
## 작업 요약
무엇이 문제였고, 왜 이 작업이 필요했으며, 어떻게 해결했는지를 서사형으로 설명한다.
bullet이 아닌 문단으로 작성. 배경 → 문제 → 접근 방식 → 결과 순서.

## 핵심 설계 결정
CONTEXT.md의 주요 결정 사항을 발췌. 각 결정마다:
- 결정 내용 (무엇을)
- 근거 (왜 이 선택을)
- 대안과 기각 이유 (왜 다른 건 안 되는지)

## 변경 범위
영역별(도메인, Application, Infrastructure 등)로 무엇이 추가/변경/제거됐는지 상세 설명.
새로 생긴 클래스, 변경된 메서드 시그니처, 제거된 코드의 의도를 포함한다.

## 다이어그램
작업 성격에 맞는 Mermaid 다이어그램을 포함한다. 예시:
- 상태 전이가 변경된 경우: stateDiagram-v2
- 처리 흐름이 변경된 경우: flowchart
- 시퀀스가 중요한 경우: sequenceDiagram
해당 없으면 생략 가능. 복수 다이어그램도 가능.

## 코드 리뷰 요약
review 단계에서 나온 findings와 처리 결과를 요약한다.
어떤 문제가 지적됐고, 어떻게 해소했는지.

## 수치
| 항목 | 값 |
|------|---|
| 태스크 | N개 |
| 테스트 | N개 통과 |
| 커밋 | N개 |
| 코드 리뷰 findings | critical N / major N / minor N |
```

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
