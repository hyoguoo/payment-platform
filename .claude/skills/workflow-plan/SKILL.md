---
name: workflow-plan
description: >
  payment-platform 워크플로우의 plan 단계를 실행한다.
  discuss가 완료된 후 "plan 작성", "플랜 짜줘", "태스크 분해", "구현 계획" 등을
  말할 때 이 스킬을 사용한다. docs/topics/<TOPIC>.md를 읽고 구체적인 구현
  태스크로 분해하는 것이 목적이다.
---

# Plan 단계 가이드

Plan의 목적은 discuss에서 결정된 설계를 "바로 코딩할 수 있는 순서 있는 태스크 목록"으로 만드는 것이다.
좋은 Plan은 각 태스크가 독립적으로 커밋 가능하고, 완료 기준이 명확하다.

---

## 시작 시 — 컨텍스트 로드

1. `docs/topics/<TOPIC>.md` — 결정 사항과 설계 옵션 파악
2. `docs/STATE.md` — 현재 단계 확인
3. 관련 소스 파일 — 기존 패턴과 연동 지점 파악 (ARCHITECTURE.md의 레이어 규칙 적용)

---

## 태스크 분해 원칙

**한 태스크 = 한 커밋**
태스크가 너무 크면 나눈다. 하나의 태스크는 30분~2시간 내에 완료할 수 있어야 한다.

**의존성 순서**
포트 인터페이스 → 도메인 로직 → 애플리케이션 서비스 → 인프라 구현 → 컨트롤러 순으로 배치한다.
테스트에서 사용할 Fake 구현이 필요하면 실제 구현 이전 태스크로 배치한다.

**TDD 여부 결정**

| 상황 | tdd |
|------|-----|
| 비즈니스 로직, 상태 전환, 엣지 케이스 있는 use case | `true` |
| Domain entity 메서드 | `true` |
| 단순 CRUD use case, 조회만 하는 서비스 | `true` (권장) |
| 포트 인터페이스 정의 (interface만) | `false` |
| 설정 클래스 (@Configuration) | `false` |
| 상수, 열거형 | `false` |
| Fake 구현체 (테스트 전용 클래스) | `false` |

---

## PLAN.md 작성

`references/plan-template.md`의 형식을 사용한다.
태스크 품질 체크 항목도 해당 파일에 있다.

---

## GitHub 이슈 생성 및 브랜치 전환

PLAN.md가 완성되면 이슈를 생성하고 작업 브랜치로 전환한다.
이슈 제목/본문 작성 컨벤션은 `issue-commit-pr` 스킬을 따른다.
단, 이 시점은 코드 변경 전이므로 git diff 대신 설계 문서(docs/topics/<TOPIC>.md)와 PLAN.md를 기반으로 이슈 내용을 작성한다.

이슈 생성: `mcp__github__issue_write` 사용 (`gh` CLI 불가)
브랜치 생성: `git checkout -b "#<이슈-번호>"` (`#`이 shell 주석으로 해석되지 않도록 따옴표 필수)

생성된 이슈 번호를 STATE.md에 기록한다 (형식은 `workflow/references/templates.md` 참조).

이후 execute의 모든 커밋은 이 브랜치 위에서 이루어진다.

---

## 완료 기준

- [ ] `docs/<TOPIC>-PLAN.md` 작성
- [ ] 모든 태스크에 명확한 완료 기준 존재
- [ ] 태스크 간 의존성 순서 올바름
- [ ] GitHub 이슈 생성 + 브랜치 `#<번호>` 전환
- [ ] PLAN.md + STATE.md를 하나의 `docs:` 커밋으로 묶기 (브랜치 위에서, topics 문서는 discuss 커밋에 포함됨)
- [ ] STATE.md 단계를 `execute`, 활성 태스크를 `Task 1`, 이슈 번호 기록

완료 후: "plan 완료. 이슈 #<번호> 생성, 브랜치 #<번호>로 전환했습니다. execute 단계로 넘어가겠습니다." 라고 알린다.
