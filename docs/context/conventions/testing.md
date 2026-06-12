# Coding Conventions — Validation / TDD

> Bean Validation, TDD 흐름. 테스트 상세는 [`../TESTING.md`](../TESTING.md), 커밋 규칙은 `CLAUDE.md` / [`commit.md`](../../../.claude/skills/_shared/conventions/commit.md) 참고.

## Bean Validation

- request DTO 에 `@NotNull`, `@NotBlank`, `@Min`, `@Max` 등
- `@Valid` 는 controller method parameter 에서만
- 도메인 entity 의 invariant 는 도메인 메서드 내부 가드로 (`Objects.requireNonNull` 또는 `IllegalArgumentException`)

## TDD 흐름

1. **RED**: 실패하는 테스트 작성 → `git commit -m "test: ..."`
2. **GREEN**: 최소 구현으로 테스트 통과 → `git commit -m "feat: ..."` (PLAN.md / STATE.md 함께 갱신)
3. **REFACTOR** (선택): `git commit -m "refactor: ..."`

도메인 entity 는 `@ParameterizedTest @EnumSource` 로 유효/무효 상태 전환 모두 커버.
