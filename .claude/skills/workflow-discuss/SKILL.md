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

## 사전 브리핑 (토론 시작 전, 필수)

본격적인 라운드 실행 **전에** 메인 스레드가 사용자에게 직접 브리핑한다. 내용:

1. **현재 이해한 문제** — 1~3줄 요약 (도메인 용어, 메서드명 금지)
2. **현재 시스템 동작** — Mermaid `flowchart` (as-is). **간략화 금지, 전체 경로**(모든 분기/예외/상태 전이)를 표현한다. 하나에 담기 어려우면 여러 개로 쪼개서 그린다. **노드 라벨은 최대한 도메인 용어로** — 메서드명·클래스명·enum 식별자 대신 "결제 승인 확정", "재시도 한도 소진", "결제 없음" 같은 도메인 표현을 쓴다. 코드 식별자가 불가피하면 `()` 괄호로 부가 표기.
3. **이번 discuss에서 결정하려는 것** — 불릿 3~5개
4. **열린 질문 / 가정** — 불릿 (사용자가 즉석에서 정정할 수 있도록)

**출력 방식**: `docs/topics/<TOPIC>.md` 상단에 `## 사전 브리핑` 섹션으로 직접 작성한다 (topic.md가 아직 없으면 새로 생성). Mermaid가 IDE/GitHub 프리뷰에서 렌더링됨. 채팅 메시지에는 "사전 브리핑을 `docs/topics/<TOPIC>.md` 상단에 작성했습니다. 확인 후 진행/정정 알려주세요." 한 줄만.

사용자 승인("ok"/"진행" 등) 또는 정정 반영 후 Round 0 진입.

목적: 사용자가 서브에이전트 라운드에 들어가기 전에 **방향 정정 기회**를 갖게 한다.

---

## 프로토콜 실행

`.claude/skills/_shared/protocols/discuss-round.md`의 Flow를 그대로 수행한다.

1. **Round 0 — Interviewer**
   페르소나: `_shared/personas/interviewer.md`
   - 4트랙(scope/constraints/outputs/verification) ambiguity ledger
   - 3-Path Routing + Dialectic Rhythm Guard
   - 출력: `docs/rounds/<topic>/discuss-interview-0.md`

2. **Round 1..3 — Architect → (Critic ∥ Domain Expert)**
   - **Architect dispatch**: `Agent(subagent_type="architect", prompt="<topic> Round N, 이전 라운드 findings: ...")` → `docs/topics/<TOPIC>.md` 작성/수정
   - **판정 dispatch (병렬, 단일 메시지)**:
     ```
     Agent(subagent_type="critic",        prompt="...", output=discuss-critic-N.md)
     Agent(subagent_type="domain-expert", prompt="...", output=discuss-domain-N.md)
     ```
     두 호출은 같은 응답 블록에서 내보낸다. 순차 호출 금지 (교차 오염).
   - **격리**: 메인 스레드에서 체크리스트를 직접 판정하지 않는다. 서브에이전트의 JSON `decision` 필드만 읽는다.
   - **Gate 판정 대상**: `discuss-ready.md`의 **Gate checklist 섹션만**. Post-phase 섹션(issue/branch/STATE)은 페르소나가 판정하지 않는다.
   - 둘 다 `decision: pass` → 후처리로
   - Round 2 fail 시 `unstuck-round.md` contrarian 관점 주입
   - Round 3 소진 시 사용자 에스컬레이션

---

## 완료 브리핑 (라운드 pass 후, 후처리 전)

모든 라운드가 pass하면 **후처리(이슈/브랜치/커밋) 전에** 결과 브리핑. 내용:

1. **결정된 접근** — 2~4줄 요약 (도메인 용어)
2. **변경 후 동작** — Mermaid `flowchart` (to-be). **간략화 금지, 전체 경로**를 표현한다. as-is와 대비 가능하도록 동일 레벨로 그리며, 필요 시 여러 개로 쪼갠다. 노드 라벨은 도메인 용어 우선 (사전 브리핑과 동일 원칙).
3. **핵심 결정 ID 목록** — topic.md §4의 키 결정만 불릿
4. **알려진 트레이드오프 / 후속 작업** — 불릿

**출력 방식**: `docs/topics/<TOPIC>.md` 상단에 `## 요약 브리핑` 섹션으로 직접 작성한다. 사전 브리핑 섹션이 있으면 그 아래에, 없으면 맨 위에 둔다. 채팅에는 "요약 브리핑을 `docs/topics/<TOPIC>.md`에 추가했습니다. 확인 후 진행/정정 알려주세요." 한 줄만.

사용자 확인 후에만 후처리 체크리스트 실행. 수정 요청 시 discuss 재진입 또는 해당 섹션만 재판정.

---

## 후처리

- [ ] `docs/topics/<TOPIC>.md` 존재 + 두 페르소나 pass
- [ ] GitHub 이슈 생성 (`mcp__github__issue_write`)
- [ ] 브랜치 생성: `git checkout -b "#<이슈-번호>"`
- [ ] STATE.md stage → `plan`, 이슈 번호·브랜치 기록
- [ ] `docs:` 단일 커밋 (topic.md(브리핑 포함) + STATE.md + 라운드 문서) — `commit-round.md` 준수

알림: "discuss 완료. 이슈 #<번호>, 브랜치 #<번호>. plan 단계로 넘어갑니다."
