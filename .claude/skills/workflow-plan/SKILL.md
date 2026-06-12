---
name: workflow-plan
description: >
  payment-platform 워크플로우의 plan 단계를 실행한다.
  discuss가 완료된 후 "plan 작성", "플랜 짜줘", "태스크 분해", "구현 계획" 등을
  말할 때 이 스킬을 사용한다. docs/topics/<TOPIC>.md를 읽고 구체적인 구현
  태스크로 분해하고, 게이트 검수까지 통과시키는 것이 목적이다.
---

# Plan 단계

메인 스레드가 태스크 분해를 직접 수행하고, 완료 게이트만 서브에이전트로 격리한다.
공통 원칙은 `workflow` 스킬 참조.

## 1. 컨텍스트 로드

- `docs/topics/<TOPIC>.md` (discuss 산출물 — 결정 사항이 분해의 원천)
- `docs/context/ARCHITECTURE.md`, `TESTING.md`
- STATE.md

## 2. 태스크 분해 (메인 직접)

`docs/<TOPIC>-PLAN.md` 작성:

```markdown
# <주제> 구현 플랜

> 작성일: YYYY-MM-DD

## 목표
<한 줄 — 무엇이 완료되면 이 플랜이 끝나는지>

## 컨텍스트
- 설계 문서: docs/topics/<TOPIC>.md
- 주요 변경 파일: <예상 핵심 파일>

## 진행 상황
- [ ] Task 1: <이름>
- [ ] Task N: <이름>

## 태스크

### Task 1: <이름> [tdd=true|false] [domain_risk=true|false]

**테스트 (RED)** ← tdd=true만
- 테스트 클래스 + 메서드 스펙 (예: `IdempotencyStoreTest` — `get_존재하는_키_반환`)
- 패턴: `@ParameterizedTest @EnumSource` / Mockito BDD / AssertJ

**구현 (GREEN)**
- 무엇을 어디에 만들지 (파일 경로)

**완료 기준**
- <객관적 기준: 테스트 X pass, `./gradlew test` 회귀 없음>

**완료 결과**
> (execute에서 채움)

## 리뷰 처리
> (ship 단계에서 채움 — finding별 채택/스킵 + 사유)
```

분해 원칙:
- **layer 의존 순서 정렬**: port → domain → application → infrastructure → controller. Fake는 소비 태스크보다 먼저.
- **한 태스크 = 한 커밋 단위, ≤ 2시간.** "서비스 전체 구현" 같은 뭉뚱그림 금지.
- **tdd=true 기준**: business logic / state machine / edge case. 설정·상수·포트 선언은 tdd=false.
- **domain_risk=true 기준**: 결제 상태 전이 · 멱등성 · 정합성/트랜잭션 경계 · PII · 외부 PG 연동 · race window 중 하나라도 해당.
- **traceability**: 모든 태스크는 topic.md의 결정 중 하나 이상에 매핑. 매핑 없는 태스크도, 태스크 없는 결정도 금지.
- 작성 후 self-check: 의존 누락(뒤 태스크 산출물을 앞에서 사용), 모호한 완료 기준, Fake 누락.

## 3. 게이트 (서브에이전트, 최대 2라운드)

```
Agent(subagent_type="reviewer", prompt="stage=plan, topic=<TOPIC>.
  대상: docs/<TOPIC>-PLAN.md
  체크리스트: .claude/skills/_shared/checklists/plan-ready.md 의 Gate 섹션
  참고: docs/topics/<TOPIC>.md (traceability 대조)")
```

토픽에 domain_risk=true 태스크가 있으면 **같은 메시지에서 domain-expert도 병렬 dispatch** (plan-ready.md의 domain risk 섹션 + discuss 리스크 → 태스크 매핑 검증).

- pass → 4로. revise → 메인이 findings 반영 수정 후 재게이트. fail(구조적) → discuss 재논의 안내.
- 2라운드 소진 시 `workflow` 스킬의 교착 처리.

## 4. 요약 브리핑

PLAN.md 상단에 `## 요약 브리핑` 섹션:

1. **Task 목록** — 번호 + 한 줄 설명 (도메인 용어)
2. **변경 후 전체 플로우차트** — Mermaid (전체 경로, `workflow` 브리핑 원칙 준수)
3. **핵심 결정 → Task 매핑** — traceability 요약
4. **트레이드오프 / 후속 작업** — 불릿

채팅에는 위치 안내 한 줄만. 사용자 확인 후 후처리.

## 5. 후처리 (plan-ready.md Post-phase)

- [ ] STATE.md stage → `execute`, 활성 태스크 → Task 1
- [ ] PLAN.md + STATE.md `docs:` 단일 커밋

알림: "plan 완료. 다음 단계: execute — 계속 진행할까요?"
