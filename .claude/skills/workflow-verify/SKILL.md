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

## 실행 순서

### Step 1 — Verifier (`_shared/personas/verifier.md`)
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

### Step 4 — STATE.md 종결
stage → `done`. `.continue-here.md` 존재 시 삭제.

### Step 5 — Critic (`_shared/personas/critic.md`)
`verify-ready.md` 체크리스트 1회 판정 → `docs/rounds/<topic>/verify-critic-1.md`.
`decision != pass` → 실패 항목 수정 후 Step 1 재시도.

### Step 6 — 최종 커밋
`commit-round.md` 준수:
```bash
git add docs/STATE.md docs/context/ docs/archive/
git commit -m "docs: <주제> 작업 완료 및 문서 아카이브"
```

### Step 7 — PR Manager (`_shared/personas/pr-manager.md`)
`vc-round.md` Step 3/4 수행.
- push: `git push -u origin "#<이슈-번호>"` (따옴표 필수)
- PR 생성: `mcp__github__create_pull_request`
- 본문은 이번 작업 전체 기반 (diff 아님)

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
