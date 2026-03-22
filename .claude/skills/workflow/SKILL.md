---
name: workflow
description: >
  payment-platform의 discuss → plan → execute → verify 워크플로우 오케스트레이터.
  docs/<TOPIC>-PLAN.md 파일이 존재하거나, 사용자가 "plan 작성", "execute 시작",
  "워크플로우로", "다음 단계", "세션 재개", "이어서 진행", "어디까지 했지" 등을 말할 때
  반드시 이 스킬을 사용한다. 단순 질문, 빠른 수정, 일회성 코드 변경에는 사용하지 않는다.
---

# Workflow 오케스트레이터

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
| execute | `workflow-execute` |
| verify | `workflow-verify` |

사용자가 특정 단계를 명시하면 해당 스킬로 바로 진행한다.
현재 단계가 불명확하거나 `idle`이면 discuss 스킬로 진행한다.

---

## STATE.md / .continue-here.md 형식

→ `references/templates.md` 참조

---

## 커밋 타이밍 요약

| 시점 | 포함 파일 | 커밋 타입 |
|------|----------|----------|
| Discuss 완료 | topics 문서 + STATE.md(plan으로 갱신) | `docs:` |
| Plan 완료 | PLAN.md + STATE.md(execute로 갱신, 이슈/브랜치 기록) | `docs:` |
| TDD RED | 실패 테스트 파일만 | `test:` |
| TDD GREEN | 구현 코드 + 테스트 | `feat:` |
| TDD REFACTOR | 정리된 코드 (변경 있을 때만) | `refactor:` |
| tdd=false 태스크 완료 | 구현 코드 | `feat:` or `chore:` |
| 세션 중단 | .continue-here.md + 현재 산출물 | 해당 타입 |
| 전체 작업 완료 | 최종 STATE.md | `docs:` |

커밋 메시지 본문은 한글, type prefix는 영문 유지.
