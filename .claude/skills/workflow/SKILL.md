---
name: workflow
description: >
  payment-platform의 discuss → plan → execute → ship 워크플로우 오케스트레이터.
  docs/STATE.md에 활성 토픽이 있거나, 사용자가 "discuss 시작", "plan 작성", "execute 시작",
  "워크플로우로", "다음 단계", "세션 재개", "이어서 진행", "어디까지 했지" 등을 말할 때
  반드시 이 스킬을 사용한다. 단순 질문, 빠른 수정, 일회성 코드 변경에는 사용하지 않는다.
---

# Workflow 오케스트레이터

4단계: **discuss → plan → execute → ship**. 메인 스레드가 설계·분해·오케스트레이션을 직접 수행하고, 독립 시각이 가치 있는 지점에만 서브에이전트를 쓴다.

## 핵심 원칙 — 선택적 격리 (Non-negotiable)

1. **검토는 격리한다**: 게이트 판정과 코드 리뷰는 `reviewer` / `domain-expert` 서브에이전트로만 실행한다. 메인이 만든 산출물을 메인이 판정하면 self-rubber-stamp가 된다.
2. **구현은 격리한다**: PLAN 태스크 실행과 리뷰 수정은 `implementer` 서브에이전트로만 실행한다 (태스크당 1회).
3. **나머지는 메인이 직접 한다**: 인터뷰, 설계 문서 작성, 태스크 분해, 테스트 실행, 문서 갱신, 아카이브, 이슈/PR. 대행 서브에이전트를 만들지 않는다.
4. **병렬 dispatch**: 같은 라운드의 reviewer + domain-expert는 **단일 메시지에서 동시 호출**한다. 순차 호출 시 두 번째가 첫 번째 결과로 오염된다. 두 에이전트는 서로의 출력을 참조하지 않는다.
5. **판정 수용**: 서브에이전트의 verdict(pass/revise/fail)를 메인이 재판정해 뒤집지 않는다. 개별 finding의 채택/스킵은 사용자와 결정한다.
6. **dispatch 입력 명시**: 검토 에이전트에게 대상(문서/diff 범위), 체크리스트 경로, 참고 입력(topic 결정 사항, `docs/context/PITFALLS.md`)을 프롬프트에 명시한다. 입력이 좋아야 검토가 깊어진다.

## 세션 시작 프로토콜

1. `docs/STATE.md` 읽기 → 활성 토픽·단계·재개 메모 파악
2. 재개 메모가 있으면 내용을 사용자에게 요약
3. 현재 단계에 맞는 서브 스킬로 진행, 현재 위치를 한 줄로 알림
   예: "Checkout 멱등성 작업 중 — execute 단계, Task 3 진행 중입니다."

## 단계 → 서브 스킬 라우팅

| 현재 단계 | 참조 스킬 |
|-----------|----------|
| idle / discuss | `workflow-discuss` |
| plan | `workflow-plan` |
| execute | `workflow-execute` |
| ship | `workflow-ship` |

사용자가 특정 단계를 명시하면 해당 스킬로 바로 진행한다.
`idle` 상태에서 다음 작업이 명확하지 않으면 `docs/context/TODOS.md`를 읽고 항목을 제안한다.

## 사용자 브리핑 원칙 (Non-negotiable)

게이트 라운드를 돌리기 **전**(사전 브리핑)과 게이트 pass **후**(완료 브리핑), 사용자에게 **도메인 용어 + Mermaid 플로우차트 포함 브리핑**을 제시한다. 목적: 사용자가 방향을 교정할 수 있는 게이트 보장.

- 브리핑은 별도 파일이 아니라 **topic.md / PLAN.md 상단 섹션**으로 작성하고, 채팅에는 위치 안내 한 줄만.
- 플로우차트는 **간략화 금지, 전체 경로**(모든 분기/예외/상태 전이). 노드 라벨은 메서드명 대신 도메인 용어, 코드 식별자는 `()` 부가 표기.
- Mermaid 금지 문자: `{` `}` `·` `→` — 대체 표기는 `_shared/conventions/writing.md` 참조.

## 단계 완료 후 정지 원칙

각 단계가 끝나면 **멈추고 사용자의 명시적 확인을 기다린다.** 자동 진행 금지.

```
## [단계명] 완료
<완료 내용 한 줄 요약>
다음 단계: [다음 단계명] — 계속 진행할까요?
```

예외: 사용자가 "연속으로 진행해", "끝까지 해줘" 등을 명시한 경우만 자동 진행.

## 게이트 라운드 규칙

- 게이트는 **최대 2라운드**: 1차 revise/fail → 메인이 findings 반영 수정 → 2차 재판정.
- 2차에서도 fail이면 **관점을 전환해 재검토**한 뒤 사용자에게 에스컬레이션한다. 관점 전환 프레임: 근본 전제가 틀렸다면?(contrarian) / 절반을 삭제한다면 무엇부터?(simplifier) / 검증 안 된 가정은?(researcher) / 모듈 경계가 올바른 층에 있는가?(boundary)
- 에스컬레이션 시 라운드 경과 요약 + "계속 / 방향 수정 / 중단" 선택지를 제시한다.

## STATE.md 형식

단계 전환 시 항상 이 형식으로 갱신한다. 완료 이력은 STATE에 쌓지 않는다 — 아카이브의 COMPLETION-BRIEFING이 이력의 SSOT다.

```markdown
# 현재 작업 상태

> 최종 수정: YYYY-MM-DD

## 활성 작업

- **주제**: <주제> (없으면 "없음 (idle)")
- **단계**: discuss | plan | execute | ship
- **활성 태스크**: Task N: <이름>  ← execute 단계에서만
- **이슈/브랜치**: #<번호>  ← discuss 완료 후
- **파일**: docs/topics/<TOPIC>.md / docs/<TOPIC>-PLAN.md

## 재개 메모

(세션 중단 시 작성, 평소 비움: 완료/남은 태스크, 결정·주의사항, 재개 방법)

## 최근 완료

- **<주제>** (YYYY-MM-DD) — docs/archive/<topic-kebab>/COMPLETION-BRIEFING.md
- (최대 2개 유지, 전체 이력은 docs/archive/README.md)
```

세션 중단 시: 재개 메모를 채우고 WIP를 `wip:` 커밋에 STATE.md와 함께 포함한다.

## 커밋 타이밍 요약

| 시점 | 포함 파일 | 커밋 타입 |
|------|----------|----------|
| Discuss 완료 | topic.md(브리핑 포함) + STATE.md | `docs:` |
| Plan 완료 (게이트 통과 후) | PLAN.md + STATE.md | `docs:` |
| TDD RED | 실패 테스트 파일만 | `test:` |
| TDD GREEN | 구현 + 테스트 + PLAN.md + STATE.md | `feat:` |
| TDD REFACTOR | 정리된 코드 (변경 있을 때만) | `refactor:` |
| tdd=false 태스크 완료 | 구현 + PLAN.md + STATE.md | `feat:` / `chore:` |
| 리뷰 피드백 수정 | 수정 파일 + PLAN.md 리뷰 처리 | `refactor:` |
| 세션 중단 | WIP + STATE.md(재개 메모) | `wip:` |
| Ship 마무리 | context 갱신 + 아카이브 + STATE.md | `docs:` |

세부 규칙은 `_shared/conventions/commit.md`, 이슈/브랜치/PR은 `_shared/conventions/github.md` 참조.
