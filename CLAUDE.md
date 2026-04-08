# Payment Platform — Claude Guidelines

## Project Purpose

Payment platform based on a hexagonal architecture. The confirm flow runs asynchronously end-to-end; TPS/latency is measured via k6 benchmarks.

---

## Workflow

작업은 **discuss → plan → plan-review → execute → review → verify** 6단계로 진행한다.

**활성화 조건**: `docs/<TOPIC>-PLAN.md` 파일이 존재할 때만 이 워크플로우를 따른다.
단순 질문이나 빠른 수정은 일반 요청으로 처리한다.

워크플로우 실행 방법은 `.claude/skills/workflow/SKILL.md`를 참고한다.

---

## Coding Rules

1. **Test-first**: 구현 전에 실패하는 테스트를 먼저 작성한다.
   - Domain entities: `@ParameterizedTest @EnumSource`로 유효/무효 상태 전환 모두 커버.
   - Use cases: Mockito 단위 테스트 먼저 작성.
2. **Minimal change**: 현재 태스크 범위 밖 코드는 수정하지 않는다. 발견한 문제는 주석으로 메모만 한다.
3. **Verify**: 매 태스크 완료 후 `./gradlew test`로 회귀 없음을 확인한다.

---

## Reference Files

### 영구 문서 (docs/context/) — 프로젝트 전체 생명주기

- [`docs/context/ARCHITECTURE.md`](docs/context/ARCHITECTURE.md) — hexagonal layer rules, module boundaries, async adapter placement
- [`docs/context/CONVENTIONS.md`](docs/context/CONVENTIONS.md) — Lombok conventions, exception handling, naming, LogFmt logging
- [`docs/context/TESTING.md`](docs/context/TESTING.md) — test strategy (Fake vs Mock), JaCoCo, test patterns
- [`docs/context/INTEGRATIONS.md`](docs/context/INTEGRATIONS.md) — Toss Payments integration, domain entities, confirm flow
- [`docs/context/STACK.md`](docs/context/STACK.md) — technology stack, dependencies
- [`docs/context/CONFIRM-FLOW-ANALYSIS.md`](docs/context/CONFIRM-FLOW-ANALYSIS.md) — async confirm flow analysis
- [`docs/context/CONFIRM-FLOW-FLOWCHART.md`](docs/context/CONFIRM-FLOW-FLOWCHART.md) — confirm flow mermaid diagrams
- [`docs/context/TODOS.md`](docs/context/TODOS.md) — 향후 정리 예정 작업 목록 (discuss idle 시 참고)

### 작업 중 설계 문서 (docs/topics/) — 작업 단위 생명주기

discuss 단계에서 생성, verify 완료 후 `docs/archive/`로 이동한다.
- `docs/topics/<TOPIC>.md` — 현재 진행 중인 작업의 설계/결정 사항

---

## Skills

워크플로우 각 단계는 얇은 오케스트레이터 + 공용 프로토콜/페르소나/체크리스트 구조로 구성된다.

- `.claude/skills/workflow/SKILL.md` — 6단계 워크플로우 상위 가이드
- `.claude/skills/workflow-{discuss,plan,plan-review,execute,review,verify}/` — 각 단계 오케스트레이터
- `.claude/skills/review/`, `.claude/skills/plan-review/` — 단독 호출용 (1라운드)
- `.claude/skills/_shared/checklists/` — 단계별 판정 체크리스트 (discuss/plan/code/verify-ready.md)
- `.claude/skills/_shared/protocols/` — 라운드 프로토콜 (discuss/plan/code/verify/qa/unstuck/commit/vc-round.md)
- `.claude/skills/_shared/personas/` — 8개 페르소나 (interviewer/architect/planner/critic/domain-expert/implementer/verifier/pr-manager)

## Commit Style

세부 규칙은 `.claude/skills/_shared/protocols/commit-round.md` 참고. 요약:
- 영문 type prefix + 한글 본문
- amend 금지, 명시 staging, hook 우회 금지
- TDD: `test:`(RED) → `feat:`(GREEN+PLAN.md+STATE.md) → `refactor:`(선택)
- plan 산출물 단일 `docs:` 커밋, verify 최종 스냅샷 독립 커밋
- STATE.md 단독 커밋 금지
