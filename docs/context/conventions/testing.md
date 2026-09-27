# Coding Conventions — Validation / TDD

> Bean Validation, TDD 흐름. 테스트 상세는 [`../TESTING.md`](../TESTING.md), 커밋 규칙은 `AGENTS.md` / [`commit.md`](../../../.agents/skills/_shared/conventions/commit.md) 참고.

## Bean Validation

- request DTO 에 `@NotNull`, `@NotBlank`, `@Min`, `@Max` 등
- `@Valid` 는 controller method parameter 에서만
- 도메인 entity 의 invariant 는 도메인 메서드 내부 가드로 (`Objects.requireNonNull` 또는 `IllegalArgumentException`)

## TDD 흐름 (정본)

개발 흐름(무엇을 먼저 쓰고 무엇을 나중에 하는지)의 정본. 커밋 단위와 타입은 [`commit.md`](../../../.agents/skills/_shared/conventions/commit.md)의 TDD와 작업 기록 절이 정본이다.

비즈니스 로직·상태 전이·버그 수정에 적용한다. 문서·문구·단순 선언에는 산출물에 맞는 검사를 한다.

1. **RED**: 실패하는 테스트를 먼저 작성한다.
   - 도메인 entity: `@ParameterizedTest @EnumSource` 로 유효/무효 상태 전환 모두 커버.
   - Use case: Mockito 단위 테스트 먼저 작성.
2. **GREEN**: 테스트를 통과하는 최소 구현. PLAN과 STATE가 있는 작업이면 진행 기록도 갱신한다.
3. **REFACTOR** (선택): 개선. 변경이 없으면 생략한다.

변경 모듈의 단위 테스트부터 실행하고, 공유 코드·서비스 경계 변경이면 영향받는 모듈로 넓힌다.
DB·Kafka·Spring 배선 변경은 해당 서비스의 통합 테스트로 확인한다. 상세 명령과 정적 분석 범위는
[AGENTS.md](../../../AGENTS.md)의 구현과 검증 절을 따른다. 동일 변경 상태에서 통과한 검사는
새 변경·환경 차이·미해결 위험이 있을 때 다시 실행한다.
