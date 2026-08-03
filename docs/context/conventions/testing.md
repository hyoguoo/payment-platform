# Coding Conventions — Validation / TDD

> Bean Validation, TDD 흐름. 테스트 상세는 [`../TESTING.md`](../TESTING.md), 커밋 규칙은 `CLAUDE.md` / [`commit.md`](../../../.claude/skills/_shared/conventions/commit.md) 참고.

## Bean Validation

- request DTO 에 `@NotNull`, `@NotBlank`, `@Min`, `@Max` 등
- `@Valid` 는 controller method parameter 에서만
- 도메인 entity 의 invariant 는 도메인 메서드 내부 가드로 (`Objects.requireNonNull` 또는 `IllegalArgumentException`)

## TDD 흐름 (정본)

개발 흐름(무엇을 먼저 쓰고 무엇을 나중에 하는지)의 정본. 커밋 타입 매핑(`test:` / `feat:` / `refactor:`)은 [`commit.md`](../../../.claude/skills/_shared/conventions/commit.md) TDD 커밋 분리 절이 정본이다.

1. **RED**: 실패하는 테스트를 먼저 작성한다.
   - 도메인 entity: `@ParameterizedTest @EnumSource` 로 유효/무효 상태 전환 모두 커버.
   - Use case: Mockito 단위 테스트 먼저 작성.
2. **GREEN**: 테스트를 통과하는 최소 구현. PLAN.md 체크박스 + STATE.md 를 함께 갱신한다.
3. **REFACTOR** (선택): 개선. 변경이 없으면 생략한다.

매 태스크 완료 후 `./gradlew test` 로 회귀 없음을 확인한다.
