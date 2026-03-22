---
name: workflow-verify
description: >
  payment-platform 워크플로우의 verify 단계를 실행한다.
  execute 완료 후 "verify 시작", "테스트 확인", "검증", "최종 확인", "작업 마무리",
  "아카이브", "정리하고 마무리" 등을 말할 때 이 스킬을 사용한다.
  전체 테스트 통과 확인 → 문서 아카이브 → 최종 커밋으로 작업을 완전히 닫는 것이 목적이다.
---

# Verify 단계 가이드

Verify의 목적은 두 가지다.
1. 구현이 전체 테스트 스위트를 깨뜨리지 않았는지 확인한다.
2. 작업 관련 문서를 아카이브하고 최종 커밋으로 작업을 깔끔하게 닫는다.

---

## Step 1 — 전체 테스트 실행

```bash
./gradlew test
```

단위 테스트(integration 태그 제외)가 모두 통과해야 한다.

### 실패 시 처리

| 유형 | 처리 방법 |
|------|----------|
| 이번 작업과 직접 관련된 버그 | 즉시 수정 후 재실행 (workflow-execute Rule 1 적용) |
| 기존에 이미 실패하던 테스트 | `git stash` 후 동일 실패인지 확인 → 기존 문제면 무시 |
| 아키텍처 변경이 필요한 문제 | 멈추고 사용자에게 보고 (workflow-execute Rule 2 적용) |

기존 실패 여부 확인:
```bash
git stash
./gradlew test 2>&1 | grep "FAILED"
git stash pop
```
`&&` 체이닝을 쓰면 테스트 실패 시 `stash pop`이 실행되지 않으므로 별도 라인으로 실행한다.

---

## Step 2 — 문서 아카이브

아카이브 대상:
- `docs/<TOPIC>-PLAN.md` → `docs/archive/<TOPIC>-PLAN.md`
- `docs/topics/<TOPIC>.md` → `docs/archive/<TOPIC>-CONTEXT.md`

아카이브하지 않는 것: `docs/context/`의 영구 문서(ARCHITECTURE.md, CONVENTIONS.md 등) 및 STATE.md

```bash
mkdir -p docs/archive
git mv docs/<TOPIC>-PLAN.md docs/archive/<TOPIC>-PLAN.md
git mv docs/topics/<TOPIC>.md docs/archive/<TOPIC>-CONTEXT.md
```
`git mv`를 사용하면 git이 rename으로 추적하므로 이력이 깔끔하게 유지된다.

---

## Step 3 — STATE.md 갱신

`workflow/references/templates.md`의 "verify 완료 후 형식"으로 갱신한다.
`.continue-here.md`가 존재하면 삭제한다.

---

## Step 4 — 최종 커밋

`git mv`로 이미 staged 상태이므로 STATE.md만 추가 stage한다.
`.continue-here.md`를 삭제했다면 함께 stage한다.

```bash
git add docs/STATE.md
# .continue-here.md를 삭제한 경우
git add docs/.continue-here.md
git commit -m "docs: <주제> 작업 완료 및 문서 아카이브"
```

---

## Step 5 — Push 및 PR 생성

STATE.md에서 이슈 번호와 브랜치 이름을 확인한 뒤 push한다.

```bash
git push -u origin "#<이슈-번호>"   # 따옴표 필수: #이 shell 주석으로 해석됨
```

PR 생성은 `issue-commit-pr` 스킬의 PR 작성 컨벤션을 따른다.
PR 내용은 git diff가 아닌 이번 작업에서 구현된 전체 내용을 기반으로 작성한다.
`mcp__github__create_pull_request` 사용 (`gh` CLI 불가):
- `head`: `#<이슈-번호>`
- `base`: `main`

---

## 완료 후 알림

```
## 작업 완료

**주제**: <주제>
**완료된 태스크**: N개
**테스트**: 전체 통과
**아카이브**: docs/archive/<TOPIC>-PLAN.md, docs/archive/<TOPIC>-CONTEXT.md
**PR**: <PR URL>

다음 작업이 있으면 새 discuss 단계로 시작하면 됩니다.
```
