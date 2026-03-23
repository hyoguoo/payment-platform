---
name: workflow-execute
description: >
  payment-platform 워크플로우의 execute 단계를 실행한다.
  docs/<TOPIC>-PLAN.md가 존재하고 사용자가 "execute 시작", "구현 시작", "코딩 시작",
  "태스크 실행", "다음 태스크" 등을 말할 때 이 스킬을 사용한다.
  TDD로 태스크를 하나씩 구현하고 커밋하는 것이 목적이다.
---

# Execute 단계 가이드

Execute의 목적은 PLAN.md의 태스크를 TDD로 하나씩 완료하고 커밋하는 것이다.
각 태스크는 독립적인 커밋이 되어야 하며, 완료 후 `./gradlew test`가 항상 통과해야 한다.

---

## 시작 시 — 상태 로드

1. `docs/STATE.md` 읽기 → 활성 태스크 확인
2. `docs/<TOPIC>-PLAN.md` 읽기 → 해당 태스크 내용 파악
3. 관련 소스 파일 파악 → 수정할 클래스의 현재 상태 확인

활성 태스크가 명시되어 있으면 그 태스크부터 시작한다.

---

## 태스크 실행 루프

### tdd=true 태스크

**1단계 — RED** 테스트 파일 먼저 작성. 구현 파일은 건드리지 않는다.
패턴 상세: `references/tdd-patterns.md`

테스트 실행: `./gradlew test --tests "<패키지>.<클래스>"`
컴파일 오류 또는 테스트 실패 모두 RED로 인정한다.
실패/오류 확인 후 커밋: `git commit -m "test: <태스크 이름> 실패 테스트 작성"`

**2단계 — GREEN** 테스트를 통과하는 최소한의 코드만 작성한다.
Lombok 패턴: `references/tdd-patterns.md`

테스트 실행: `./gradlew test`
통과 확인 후 커밋: `git commit -m "feat: <태스크 이름> 구현"`

**3단계 — REFACTOR** 중복 제거, 메서드 분리, 네이밍 개선.
변경 사항이 없으면 건너뛴다.
커밋: `git commit -m "refactor: <태스크 이름> 정리"` (변경이 있을 때만)

---

### tdd=false 태스크

포트 인터페이스, 설정 클래스, 상수 등을 작성한다.
완료 후 `./gradlew test`로 기존 테스트가 깨지지 않았는지 확인한다.
커밋: `git commit -m "feat: <이름>"` 또는 `"chore: <이름>"` — STATE.md 갱신을 이 커밋에 함께 포함한다.

---

## Deviation Rules

예상 외 상황 발생 시 → `references/deviation-rules.md` 참조

- **Rule 1**: 컴파일 오류, 깨진 import, 명백한 버그 → 자동 수정 후 커밋 메시지에 기재
- **Rule 2**: DB 스키마 변경, 레이어 경계 위반, build.gradle 변경 등 → 즉시 멈추고 확인 요청

---

## Analysis Paralysis Guard

Read / Grep / Glob를 5회 이상 연속 실행 후 코드 한 줄도 작성하지 않으면 → 멈춘다.
그 후 둘 중 하나를 선택한다:
- 지금 가진 정보로 코드를 작성한다.
- "블로킹: <무엇이 없어서 못 진행하는지>"를 보고한다.

---

## 태스크 완료 후

1. `./gradlew test` 실행 → 전체 통과 확인
2. `docs/<TOPIC>-PLAN.md`에 아래 두 가지를 업데이트한다:
   - 진행 상황 섹션의 해당 태스크 체크박스를 `[x]`로 체크
   - 해당 태스크의 **완료 결과** 섹션을 채운다 — 실제 구현 방식, 계획과 달라진 점, 주요 결정 사항을 1~3줄로 기술
3. STATE.md의 활성 태스크를 다음 태스크로 갱신
4. PLAN.md 업데이트 + STATE.md 갱신을 GREEN(또는 tdd=false 완료) 커밋에 함께 포함한다
5. 다음 태스크로 진행

**마지막 태스크 완료 시**: STATE.md 단계를 `verify`로 갱신하고, PLAN.md의 마지막 체크박스 체크와 함께 마지막 GREEN 커밋에 포함한다.
별도 커밋을 만들지 않는다. 그 후 "execute 완료. verify 단계로 넘어가겠습니다." 라고 알린다.

---

## 세션 중단 시

1. 현재 진행 중인 태스크의 상태를 커밋한다 — WIP이면 `wip: <태스크 이름> 진행 중` 타입으로 커밋
2. `docs/.continue-here.md`를 작성한다 (`workflow/references/templates.md` 형식 참조)
3. `.continue-here.md` + 현재 파일들을 함께 커밋한다
