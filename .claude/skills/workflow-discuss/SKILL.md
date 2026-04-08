---
name: workflow-discuss
description: >
  payment-platform 워크플로우의 discuss 단계를 실행한다.
  사용자가 새 기능/버그/개선의 설계를 논의하거나, "discuss 시작", "설계 논의",
  "어떻게 구현할지 얘기해보자", "방법 고민" 등을 말할 때 이 스킬을 사용한다.
  discuss 단계는 구현 전에 결정해야 할 사항을 명확히 하는 것이 목적이다.
---

# Discuss 단계 오케스트레이터

이 스킬은 `discuss-round` 프로토콜을 실행하는 **얇은 오케스트레이터**다.
실제 로직은 프로토콜과 페르소나 파일에 있다.

---

## 시작 시 — TOPIC 확정

사용자 요청에서 TOPIC(UPPER-KEBAB-CASE) 확정.
불명확하면 `AskUserQuestion`으로 제안·확인.

예: `CHECKOUT-IDEMPOTENCY`, `PAYMENT-RETRY`

---

## 프로토콜 실행

`.claude/skills/_shared/protocols/discuss-round.md`의 Flow를 그대로 수행한다.

1. **Round 0 — Interviewer**
   페르소나: `_shared/personas/interviewer.md`
   - 4트랙(scope/constraints/outputs/verification) ambiguity ledger
   - 3-Path Routing + Dialectic Rhythm Guard
   - 출력: `docs/rounds/<topic>/discuss-interview-0.md`

2. **Round 1..3 — Architect → Critic → Domain Expert**
   페르소나: `_shared/personas/architect.md`, `critic.md`, `domain-expert.md`
   - Architect가 `docs/topics/<TOPIC>.md` 작성/수정
   - Critic이 `discuss-ready.md` 체크리스트로 판정 → `discuss-critic-<N>.md`
   - Domain Expert가 도메인 리스크 판정 → `discuss-domain-<N>.md`
   - 둘 다 `decision: pass` → 다음 단계
   - Round 2 fail 시 `unstuck-round.md`의 contrarian 관점 주입
   - Round 3 소진 시 사용자 에스컬레이션

판정은 `qa-round.md` JSON 스키마의 `decision` 필드만 기계적으로 읽는다.

---

## 완료 시 후처리

- [ ] `docs/topics/<TOPIC>.md` 존재 + 두 페르소나 pass
- [ ] GitHub 이슈 생성 (`mcp__github__issue_write`)
- [ ] 브랜치 생성: `git checkout -b "#<이슈-번호>"`
- [ ] STATE.md stage → `plan`, 이슈 번호·브랜치 기록
- [ ] `docs:` 단일 커밋 (topic.md + STATE.md + 라운드 문서) — `commit-round.md` 준수

알림: "discuss 완료. 이슈 #<번호>, 브랜치 #<번호>. plan 단계로 넘어갑니다."
