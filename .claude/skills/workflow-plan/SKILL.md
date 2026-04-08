---
name: workflow-plan
description: >
  payment-platform 워크플로우의 plan 단계를 실행한다.
  discuss가 완료된 후 "plan 작성", "플랜 짜줘", "태스크 분해", "구현 계획" 등을
  말할 때 이 스킬을 사용한다. docs/topics/<TOPIC>.md를 읽고 구체적인 구현
  태스크로 분해하는 것이 목적이다.
---

# Plan 단계 오케스트레이터

`plan-round` 프로토콜을 실행하는 얇은 오케스트레이터.

---

## 컨텍스트 로드

- `docs/topics/<TOPIC>.md` (discuss 산출물)
- `docs/rounds/<topic>/discuss-*.md` (리스크 이력)
- `docs/context/ARCHITECTURE.md`, `TESTING.md`
- STATE.md

---

## 프로토콜 실행

`.claude/skills/_shared/protocols/plan-round.md`의 Flow 수행.

1. **Planner**(`_shared/personas/planner.md`)
   - `docs/<TOPIC>-PLAN.md` 초안
   - 각 태스크: `tdd` + `domain_risk` 플래그
   - layer 의존 순서

2. **Architect**(`_shared/personas/architect.md`)
   - 초안에 인라인 주석 개입 (별도 라운드 문서 없음)

3. **Critic**(`_shared/personas/critic.md`)
   - `plan-ready.md` 체크리스트 판정 → `plan-critic-<N>.md`

4. **Domain Expert**(`_shared/personas/domain-expert.md`)
   - discuss에서 식별된 domain risk가 전부 태스크로 매핑되었는지 확인
   - `plan-domain-<N>.md`

5. 둘 다 pass → 완료. Round 2 fail 시 `unstuck-round.md`의 simplifier 주입.

---

## 완료 시 후처리

- [ ] PLAN.md + STATE.md를 `docs:` 단일 커밋 (`commit-round.md` 준수)
- [ ] STATE.md stage → `plan-review`

알림: "plan 완료. plan-review 단계로 넘어갑니다." → `workflow-plan-review` 즉시 진행.
