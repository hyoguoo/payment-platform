# Skills Index

payment-platform의 Claude Code 스킬 모음. 각 스킬은 `<name>/SKILL.md`로 발견된다.

## 워크플로우 오케스트레이터 (페르소나 기반)

6단계 워크플로우. 각 스킬은 얇은 오케스트레이터로, 실제 로직은 `_shared/` 프로토콜·페르소나·체크리스트에 있다.

| 스킬 | 단계 | 호출 시점 | 핵심 페르소나 |
|---|---|---|---|
| `workflow` | (라우터) | STATE.md 기반 현재 단계로 분기 | — |
| `workflow-discuss` | discuss | 새 작업 설계 논의 시작 | Interviewer, Architect, Critic, Domain Expert |
| `workflow-plan` | plan | discuss 완료 후 태스크 분해 | Planner, Architect, Critic, Domain Expert |
| `workflow-plan-review` | plan-review | plan 완료 후 검수 게이트 | Plan Reviewer (1라운드) |
| `workflow-execute` | execute | TDD 태스크 실행 | Implementer, Verifier, Critic, Domain Expert(조건부) |
| `workflow-review` | review | execute 완료 후 코드 리뷰 | Critic, Domain Expert (1라운드) |
| `workflow-verify` | verify | 사용자 명시 요청 시 최종 검증 | Verifier, Critic, PR Manager |

## 단독 호출 스킬

워크플로우 외부에서도 자유롭게 호출 가능.

| 스킬 | 용도 |
|---|---|
| `review` | 변경 사항 코드 리뷰 (Critic + Domain Expert 1라운드) |
| `plan-review` | 임의 PLAN/설계 문서 검수 (Critic + 조건부 Domain Expert 1라운드) |
| `issue-commit-pr` | 이슈 → 브랜치 → 커밋 → 푸시 → PR 전체 흐름 |
| `context-update` | `docs/context/` 영구 문서 갱신 |
| `k6-benchmark` | 서버 기동 + k6 부하 테스트 실행 |
| `k6-summarize` | k6 결과 요약 테이블 |

## 공용 리소스 (`_shared/`)

스킬이 아닌 공용 파일. `_` prefix로 스캔 대상에서 제외.

### `_shared/checklists/` — 판정 체크리스트
단계별 yes/no 판정 기준. Critic 페르소나의 결정론적 입력.

| 파일 | 용도 |
|---|---|
| `discuss-ready.md` | discuss 단계 완료 조건 |
| `plan-ready.md` | plan 단계 완료 조건 |
| `code-ready.md` | code 태스크 완료 조건 |
| `verify-ready.md` | verify 최종 게이트 |

### `_shared/protocols/` — 라운드 프로토콜
페르소나 협업 흐름·출력 스키마·공통 규칙.

| 파일 | 역할 |
|---|---|
| `discuss-round.md` | discuss 라운드 Flow (Interviewer → Architect → Critic → Domain Expert) |
| `plan-round.md` | plan 라운드 Flow + `domain_risk` 플래그 규칙 |
| `code-round.md` | TDD 태스크 실행 Flow |
| `verify-round.md` | 최종 검증 Flow (테스트 → 아카이브 → PR) |
| `qa-round.md` | Critic/Domain Expert 공통 JSON 출력 스키마 |
| `unstuck-round.md` | 교착 시 관점 전환 5종 (contrarian/simplifier/researcher/hacker/architect) |
| `commit-round.md` | 커밋 메시지·staging·TDD 커밋 분리 규칙 |
| `vc-round.md` | GitHub 이슈·브랜치·PR 표준 절차 |

### `_shared/personas/` — 페르소나 정의
모델·책임·입출력·금지 사항.

| 페르소나 | 모델 | 주 사용 단계 |
|---|---|---|
| `interviewer.md` | Opus | discuss Round 0 (되묻기와 가정 검증) |
| `architect.md` | Opus | discuss, plan (설계/주석 개입) |
| `planner.md` | Sonnet | plan (태스크 분해) |
| `critic.md` | Opus | discuss, plan, code, review, verify (체크리스트 판정) |
| `plan-reviewer.md` | Sonnet | plan-review (문서 정합성 경량 검증) |
| `domain-expert.md` | Opus | discuss, plan, code(조건부) (결제 도메인 리스크) |
| `implementer.md` | Sonnet | execute (TDD 구현) |
| `verifier.md` | Haiku | code, verify (`./gradlew test` 결정론적 백본) |
| `pr-manager.md` | Haiku | verify (gh CLI 호출) |

## 전체 데이터 흐름

```
사용자 요청
   ↓
workflow (STATE.md 확인)
   ↓
workflow-<stage>  ←  _shared/protocols/<stage>-round.md
                     ↓
                     페르소나 호출 (_shared/personas/*.md)
                     ↓
                     체크리스트 판정 (_shared/checklists/*.md)
                     ↓
                     qa-round.md 스키마 JSON 출력
                     ↓
                     docs/rounds/<topic>/<stage>-<persona>-<N>.md
   ↓
STATE.md 다음 단계로 전이
```

## 참고
- 워크플로우 활성화 조건·커밋 타이밍: `workflow/SKILL.md`
- 프로젝트 전체 가이드: `/CLAUDE.md`
