---
name: workflow-discuss
description: >
  payment-platform 워크플로우의 discuss 단계를 실행한다.
  사용자가 새 기능/버그/개선의 설계를 논의하거나, "discuss 시작", "설계 논의",
  "어떻게 구현할지 얘기해보자", "방법 고민" 등을 말할 때 이 스킬을 사용한다.
  discuss 단계는 구현 전에 결정해야 할 사항을 명확히 하는 것이 목적이다.
---

# Discuss 단계 가이드

Discuss의 목적은 "무엇을 어떻게 구현할지"에 대한 결정을 코드를 쓰기 전에 명확히 하는 것이다.
여기서 내린 결정들이 Plan 단계의 태스크 분해와 Execute 단계의 구현 방향을 결정한다.

---

## 시작 시 — TOPIC 확정 및 컨텍스트 파악

사용자 요청에서 TOPIC 이름을 먼저 확정한다.
TOPIC은 **대문자 케밥 케이스**로, 작업 내용을 명확하게 설명하는 이름을 사용한다.
예: `CHECKOUT-IDEMPOTENCY`, `PAYMENT-RETRY`, `ORDER-STATUS`
불명확하면 사용자에게 한 줄로 제안하고 확인받는다.

컨텍스트 파악:
1. `docs/context/` 디렉토리의 관련 문서들 (ARCHITECTURE.md, CONVENTIONS.md, TESTING.md 등)
2. 관련 기존 소스 파일 — 수정하거나 연동할 코드의 현재 구조 파악
3. 이미 `docs/topics/<TOPIC>.md`가 존재하면 읽고 이어서 논의

기존 코드를 읽지 않고 설계를 논의하면 현실과 동떨어진 결정이 나온다.

---

## 논의할 영역 식별

코드와 요구사항을 읽은 뒤, 아래 기준으로 "아직 결정되지 않은 영역"을 파악한다.

**기능 설계**
- 어느 레이어에 구현할지 (domain / application / infrastructure / presentation)
- 포트 인터페이스가 필요한지, 기존 포트를 확장할지
- 상태 전환이 있다면 어떤 상태 머신을 따르는지

**동시성 / 정합성**
- 동시 요청 시 어떻게 처리할지 (락, 원자적 연산, DB 제약)
- 트랜잭션 경계를 어디에 둘지

**테스트 전략**
- 단위 테스트로 충분한지, Fake가 필요한지
- 통합 테스트가 필요한 경로가 있는지

**범위 경계**
- 이번 작업에서 하는 것 vs 하지 않는 것

한 번에 모든 영역을 묻지 말고, 가장 불확실한 영역부터 하나씩 논의한다.

---

## docs/topics/<TOPIC>.md 작성

논의가 완료되면 `references/context-template.md`의 형식을 사용해 `docs/topics/<TOPIC>.md`에 작성한다.
해당하는 섹션만 포함한다.

---

## 범위 가드

논의 중 새로운 요구사항이 나오면 현재 작업에 추가하지 않는다.
"제외 범위"에 메모하고, 별도 작업으로 다룬다.

---

## 완료 기준

- [ ] `docs/topics/<TOPIC>.md` 작성
- [ ] "결정 사항" 섹션이 명확하게 채워져 있음
- [ ] Plan 단계에서 추가 논의 없이 태스크를 분해할 수 있는 상태
- [ ] STATE.md 단계를 `plan`으로 갱신
- [ ] `docs/topics/<TOPIC>.md` + `docs/STATE.md` 를 하나의 `docs:` 커밋으로 묶기 (main 브랜치에서)

완료 후: "discuss 완료. plan 단계로 넘어가겠습니다." 라고 알린다.
