# Skills Index

payment-platform의 Claude Code 스킬 모음. 각 스킬은 `<name>/SKILL.md`로 발견된다.

## 워크플로우 (4단계)

**discuss → plan → execute → ship.** 메인 스레드(고지능 모델)가 인터뷰·설계·분해·마무리를 직접 수행하고, 독립 시각이 가치 있는 검토와 구현 실행만 서브에이전트로 격리한다.

| 스킬 | 단계 | 메인 직접 | 서브에이전트 |
|---|---|---|---|
| `workflow` | (라우터) | STATE.md 기반 분기 + 공통 원칙 | — |
| `workflow-discuss` | discuss | 인터뷰 + 설계 문서 작성 | 게이트: reviewer ∥ domain-expert |
| `workflow-plan` | plan | 태스크 분해 + PLAN.md | 게이트: reviewer (+조건부 domain-expert) |
| `workflow-execute` | execute | 오케스트레이션 | implementer (태스크당 1회) |
| `workflow-ship` | ship | 최종 검증·문서·아카이브·PR | 리뷰: reviewer ∥ domain-expert, 수정: implementer |

## 단독 호출 스킬

| 스킬 | 용도 |
|---|---|
| `review` | 변경 사항 코드 리뷰 (reviewer + domain-expert 1라운드) |
| `issue-commit-pr` | 이슈 → 브랜치 → 커밋 → 푸시 → PR 전체 흐름 |
| `context-update` | `docs/context/` 영구 문서 갱신 |
| `writing` | 문서 콘텐츠 작성 (포스팅, 위키, 리드미) |
| `doc-review` | 문서 검수 4관점 병렬 서브에이전트 루프 |
| `wiki-access` | `<project>.wiki/` 별도 git 저장소 자동 탐색 + Read/Grep/Edit 접근 |

## 서브에이전트 (`.claude/agents/` — 유일한 정의처)

| 에이전트 | 모델 | 역할 |
|---|---|---|
| `reviewer` | Opus | 체크리스트 + 일반 품질 검토 (discuss/plan 게이트, ship/단독 리뷰) |
| `domain-expert` | Fable | 결제 도메인 리스크 — 돈 새는 경로·상태 전이·멱등성·race, 소스 교차검증 |
| `implementer` | Sonnet | PLAN 단일 태스크 TDD 실행 + 리뷰 finding 수정 + 커밋 |

판정 출력은 파일이 아닌 **최종 메시지**(Verdict + Findings)로 반환한다. 영속 기록은 topic.md / PLAN.md(리뷰 처리 섹션) / COMPLETION-BRIEFING에 남는다.

## 공용 리소스 (`_shared/`)

### `_shared/checklists/` — 게이트 판정 기준

| 파일 | 용도 |
|---|---|
| `discuss-ready.md` | discuss 게이트 (설계 품질 + 도메인 리스크) |
| `plan-ready.md` | plan 게이트 (traceability + 태스크 품질 + TDD 명세) |
| `code-ready.md` | ship 리뷰 / 단독 리뷰 (테스트 게이트 + 컨벤션 + 도메인 리스크) |
| `ship-ready.md` | ship 마무리 (최종 검증 + 문서 동기화 + 아카이브 + PR) |

### `_shared/conventions/` — 공통 컨벤션

| 파일 | 용도 |
|---|---|
| `commit.md` | 커밋 메시지·staging·TDD 커밋 분리 규칙 |
| `github.md` | GitHub 이슈·브랜치·PR 표준 (한글 제목, 명사형 헤더, 히스토리 보존) |
| `writing.md` | 문서 작성 컨벤션 (문체, 표, Mermaid 금지 문자, 팩트 검증) |

## 전체 데이터 흐름

```
사용자 요청
   ↓
workflow (STATE.md 확인) → workflow-<stage>
   ↓
메인: 산출물 작성 (topic.md / PLAN.md) + 사전·완료 브리핑
   ↓
게이트: reviewer ∥ domain-expert 병렬 dispatch (체크리스트 + 참고 입력 명시)
   ↓
verdict 수용 → findings 반영 → 사용자 확인 → 단계 전이 (STATE.md)
```

## 참고

- 워크플로우 활성화 조건·커밋 타이밍: `workflow/SKILL.md`
- 프로젝트 전체 가이드: `/CLAUDE.md`
