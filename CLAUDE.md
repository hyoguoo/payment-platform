# Payment Platform — Claude Guidelines

## Project Purpose

Async payment migration project: migrate the synchronous Toss Payments confirm flow to an async architecture using three interchangeable strategies (Sync / DB Outbox / Kafka), switchable via Spring Bean configuration. Goal is to measure TPS and latency differences with k6 benchmarks.

## Workflow — Mandatory Before Writing Any Code

1. **Test-first**: Write the failing test before writing implementation.
   - Domain entities: write `@ParameterizedTest @EnumSource` covering valid AND invalid status transitions.
   - Use cases: write the Mockito unit test first.
2. **Minimal change**: Do not refactor code outside the scope of the current task. If you notice something unrelated that should change, note it in a comment but do not change it.
3. **Verify**: Run `./gradlew test` after every task to confirm no regressions.

## Reference Files

- [`docs/context/ARCHITECTURE.md`](docs/context/ARCHITECTURE.md) — hexagonal layer rules, module boundaries, async adapter placement
- [`docs/context/CONVENTIONS.md`](docs/context/CONVENTIONS.md) — Lombok conventions, exception handling, naming, LogFmt logging
- [`docs/context/TESTING.md`](docs/context/TESTING.md) — test strategy (Fake vs Mock), JaCoCo, test patterns
- [`docs/context/INTEGRATIONS.md`](docs/context/INTEGRATIONS.md) — Toss Payments integration, domain entities, confirm flow
- [`docs/context/STACK.md`](docs/context/STACK.md) — technology stack, dependencies
- [`docs/context/CONFIRM-FLOW-ANALYSIS.md`](docs/context/CONFIRM-FLOW-ANALYSIS.md) — async confirm flow analysis (Sync / Outbox / Kafka)
- [`docs/context/CONFIRM-FLOW-FLOWCHART.md`](docs/context/CONFIRM-FLOW-FLOWCHART.md) — confirm flow mermaid diagrams

## Skills

`.claude/skills/review.md` contains the code review checklist. Apply it in full whenever asked to review code.

## Commit Style

- **언어**: 커밋 메시지 본문은 한글로 작성한다. (타입 prefix는 영문 유지: `feat:`, `fix:`, `docs:` 등)
- **문서 산출물**: 같은 작업에서 나온 파일(CONTEXT.md, PLAN.md, RESEARCH.md 등)은 하나의 커밋으로 묶는다. STATE.md는 독립 커밋을 만들지 않고, 함께 작업한 산출물 커밋에 포함한다.
- **코드 변경**: 논리적으로 독립된 단위로 커밋을 나눈다 (기능 하나, 버그 수정 하나 등). 테스트 코드는 구현 코드와 같은 커밋에 포함한다.

