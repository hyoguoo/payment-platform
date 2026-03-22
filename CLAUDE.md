# Payment Platform — Claude Guidelines

## Project Purpose

Async payment migration project: migrate the synchronous Toss Payments confirm flow to an async architecture using three interchangeable strategies (Sync / DB Outbox / Kafka), switchable via Spring Bean configuration. Goal is to measure TPS and latency differences with k6 benchmarks.

---

## Workflow

작업은 **discuss → plan → execute → verify** 4단계로 진행한다.

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
- [`docs/context/CONFIRM-FLOW-ANALYSIS.md`](docs/context/CONFIRM-FLOW-ANALYSIS.md) — async confirm flow analysis (Sync / Outbox / Kafka)
- [`docs/context/CONFIRM-FLOW-FLOWCHART.md`](docs/context/CONFIRM-FLOW-FLOWCHART.md) — confirm flow mermaid diagrams

### 작업 중 설계 문서 (docs/topics/) — 작업 단위 생명주기

discuss 단계에서 생성, verify 완료 후 `docs/archive/`로 이동한다.
- `docs/topics/<TOPIC>.md` — 현재 진행 중인 작업의 설계/결정 사항

---

## Skills

- `.claude/skills/workflow/SKILL.md` — discuss → plan → execute → verify 워크플로우 실행 가이드
- `.claude/skills/review/SKILL.md` — 코드 리뷰 체크리스트

---

## Commit Style

- **언어**: 커밋 메시지 본문은 한글로 작성한다. (타입 prefix는 영문 유지: `feat:`, `fix:`, `docs:` 등)
- **문서 산출물**: plan 단계 완료 시 산출물(context 문서, PLAN.md 등)을 하나의 커밋으로 묶는다. 모든 작업 완료 후 최종 문서 상태를 독립 커밋으로 한 번 더 남긴다.
- **코드 변경**: 논리적으로 독립된 단위로 커밋을 나눈다. 테스트 코드는 구현 코드와 같은 커밋에 포함한다.
- **TDD 커밋**: RED(`test:`) → GREEN(`feat:`) → REFACTOR(`refactor:`) 각각 별도 커밋.
