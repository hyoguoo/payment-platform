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

**모든 페르소나는 서브에이전트로만 실행.** 메인 스레드에서 판정/분해 금지.

1. **Planner dispatch**: `Agent(subagent_type="planner", prompt="<topic> PLAN 초안 작성. 입력: docs/topics/<TOPIC>.md")`
2. **Architect dispatch**: `Agent(subagent_type="architect", prompt="PLAN 초안 layer/module 검토")` (순차 — Planner 초안 의존)
3. **판정 dispatch (병렬, 단일 메시지)**:
   ```
   Agent(subagent_type="critic",        prompt="...", output=plan-critic-N.md)
   Agent(subagent_type="domain-expert", prompt="...", output=plan-domain-N.md)
   ```
   - 판정 대상: `plan-ready.md`의 **Gate checklist 섹션만**. Post-phase 섹션은 제외.
4. 서브에이전트 JSON `decision`만 기계적으로 읽는다.
5. 둘 다 pass → 완료. Round 2 fail 시 `unstuck-round.md`의 simplifier 주입.

---

## 완료 시 후처리

- [ ] PLAN.md + STATE.md를 `docs:` 단일 커밋 (`commit-round.md` 준수)
- [ ] STATE.md stage → `plan-review`

알림: "plan 완료. plan-review 단계로 넘어갑니다." → `workflow-plan-review` 즉시 진행.
