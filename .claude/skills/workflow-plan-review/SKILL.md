---
name: workflow-plan-review
description: >
  payment-platform 워크플로우의 plan-review 단계를 실행한다.
  plan 완료 직후 자동으로 실행되거나, "plan-review 시작"을 말할 때 사용한다.
  PLAN.md + topics 문서를 자동 로드하여 plan-review 스킬로 검수하고 STATE.md를 갱신한다.
  워크플로우 외부에서 임의 파일/내용을 검수할 때는 plan-review 스킬을 직접 사용한다.
---

# Workflow Plan Review

## 1. 컨텍스트 로드

`docs/STATE.md`를 읽어 TOPIC을 파악한 뒤 아래 파일을 로드한다.

- `docs/<TOPIC>-PLAN.md` — 검수 대상
- `docs/topics/<TOPIC>.md` — discuss 결정 사항 (설계 일치 검사용)
- `docs/context/ARCHITECTURE.md`, `docs/context/TESTING.md` — 규칙 참조용

## 2. 검수 실행

`plan-review` 스킬을 사용하여 로드한 파일들을 검수한다.

## 3. 결과에 따른 STATE.md 처리

| 결과 | STATE.md 갱신 | 다음 행동 |
|------|--------------|----------|
| 🔴 CRITICAL 있음 | 단계 → `discuss` | PLAN.md 상단에 `<!-- [REVIEW FAILED - CRITICAL] -->` 추가 후 재논의 안내 |
| 🟡 MAJOR 있음 | 변경 없음 | MAJOR 수정 요청 → 수정 완료 후 영향 항목 재점검 → 통과 시 아래로 |
| CRITICAL·MAJOR 없음 | 단계 → `execute`, 활성 태스크 → `Task 1` | workflow-execute로 진행 |
