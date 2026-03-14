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

- [`.planning/codebase/ARCHITECTURE.md`](.planning/codebase/ARCHITECTURE.md) — hexagonal layer rules, module boundaries, async adapter placement
- [`.planning/codebase/CONVENTIONS.md`](.planning/codebase/CONVENTIONS.md) — Lombok conventions, exception handling, naming, LogFmt logging
- [`.planning/codebase/TESTING.md`](.planning/codebase/TESTING.md) — test strategy (Fake vs Mock), JaCoCo, test patterns
- [`.planning/codebase/INTEGRATIONS.md`](.planning/codebase/INTEGRATIONS.md) — Toss Payments integration, domain entities, confirm flow
- [`.planning/codebase/STACK.md`](.planning/codebase/STACK.md) — technology stack, dependencies

## Skills

`.claude/skills/review.md` contains the code review checklist. Apply it in full whenever asked to review code.

## Commit Style

- **언어**: 커밋 메시지 본문은 한글로 작성한다. (타입 prefix는 영문 유지: `feat:`, `fix:`, `docs:` 등)
- **문서 산출물**: 같은 작업에서 나온 파일(CONTEXT.md, PLAN.md, RESEARCH.md 등)은 하나의 커밋으로 묶는다. STATE.md는 독립 커밋을 만들지 않고, 함께 작업한 산출물 커밋에 포함한다.
- **코드 변경**: 논리적으로 독립된 단위로 커밋을 나눈다 (기능 하나, 버그 수정 하나 등). 테스트 코드는 구현 코드와 같은 커밋에 포함한다.

## Key Pending Decisions

These decisions affect upcoming implementation phases — do not assume outcomes without explicit resolution:

1. **Outbox storage** (resolve in Phase 3): Dedicated `payment_outbox` table vs. reuse `PaymentProcess` table as the Outbox record. Review `PaymentRecoverServiceImpl` queries before deciding — incorrect reuse may cause race conditions with the recovery scheduler.

2. **202 vs 200 response** (confirm during Phase 1): Kafka adapter returns 202 Accepted; Sync adapter returns 200 OK. Design `PaymentConfirmAsyncResult.status` to let the controller decide without reading Spring config directly.
