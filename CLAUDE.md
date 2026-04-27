# Payment Platform — Claude Guidelines

## Project Purpose

결제 도메인 학습용 MSA 플랫폼. 4 비즈니스 서비스(payment / pg / product / user) + Eureka + Gateway, hexagonal architecture, Kafka 양방향 비동기 confirm. TPS/latency 는 k6 벤치마크로 측정.

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

## Conversation Rules

1. **태스크 ID 단독 사용 금지**: PLAN.md 의 태스크 식별자(`A1`, `A5b`, `B7`, `T-E3`, `K12` 등)를 사용자 응답에서 단독으로 던지지 않는다. 사용자가 PLAN 을 매번 열지 않아도 맥락을 알 수 있도록 ID 옆에 그 작업의 내용을 1줄 이내로 풀어서 같이 적는다.
   - 나쁨: "A5b 후속에서 처리"
   - 좋음: "A5b (product-service container_name 제거 + Eureka instanceId 고유화) 후속에서 처리"
   - 또는 ID 빼고 내용으로만: "container_name 제거 작업 후속에서 처리"
   - 단, PLAN.md / commit message / implementer 디스패치 프롬프트 같은 산출물에서는 ID 그대로 OK (그게 본 식별자)

---

## Reference Files

### 영구 문서 (docs/context/) — 프로젝트 전체 생명주기

- [`docs/context/ARCHITECTURE.md`](docs/context/ARCHITECTURE.md) — 4서비스 토폴로지, hexagonal layer 룰, 비동기 어댑터 위치, 핵심 설계 결정 인덱스
- [`docs/context/STRUCTURE.md`](docs/context/STRUCTURE.md) — 디렉토리 트리, 모듈 의존, 패키지 컨벤션
- [`docs/context/STACK.md`](docs/context/STACK.md) — 기술 스택, Flyway 운영 가이드, 빌드 / 정적 분석
- [`docs/context/CONVENTIONS.md`](docs/context/CONVENTIONS.md) — Lombok, 예외 계층, naming, LogFmt, AOP, 트랜잭션 룰
- [`docs/context/TESTING.md`](docs/context/TESTING.md) — Fake vs Mock 룰, Testcontainers, contract test, JaCoCo, TDD 흐름
- [`docs/context/INTEGRATIONS.md`](docs/context/INTEGRATIONS.md) — Toss + NicePay Strategy, cross-service HTTP, 외부 의존 관리
- [`docs/context/PAYMENT-FLOW.md`](docs/context/PAYMENT-FLOW.md) — end-to-end 결제 플로우 (브라우저 checkout → Gateway → payment ↔ pg ↔ vendor → 결과 콜백)
- [`docs/context/CONFIRM-FLOW.md`](docs/context/CONFIRM-FLOW.md) — payment-service 측 비동기 confirm 사이클 deep dive (분석 + Mermaid 다이어그램 통합)
- [`docs/context/PITFALLS.md`](docs/context/PITFALLS.md) — 학습된 도메인 함정 인덱스
- [`docs/context/CONCERNS.md`](docs/context/CONCERNS.md) — 알려진 우려 / 한계 / 회피된 우려
- [`docs/context/TODOS.md`](docs/context/TODOS.md) — Phase 4 후속 + 향후 처리 항목

### 영구 도구 가이드 (docs/smoke/) — 시점 무관

- [`docs/smoke/infra-healthcheck.md`](docs/smoke/infra-healthcheck.md) — 인프라 + 4서비스 살아있음 검사 스크립트 가이드
- [`docs/smoke/trace-continuity-check.md`](docs/smoke/trace-continuity-check.md) — 분산 트레이스 연속성 검사 가이드

### 작업 중 설계 문서 (docs/topics/) — 작업 단위 생명주기

discuss 단계에서 생성, verify 완료 후 `docs/archive/`로 이동한다.
- `docs/topics/<TOPIC>.md` — 현재 진행 중인 작업의 설계/결정 사항

### 브리핑 파일 — 단계별 요약

각 워크플로우 단계의 요약을 별도 브리핑 파일로 관리한다. 원본 문서와 같은 디렉토리에 `-BRIEFING` 접미사로 배치한다.

- `docs/topics/<TOPIC>-BRIEFING.md` — discuss 사전/사후 브리핑
- `docs/<TOPIC>-PLAN-BRIEFING.md` — plan 브리핑

**컨텍스트 참조 순서**: 브리핑 파일을 먼저 읽어 전체 그림을 파악한 뒤, 세부 결정이나 구체적 구현 사항이 필요할 때 원본 파일을 참조한다.

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
