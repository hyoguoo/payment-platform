# Payment Platform — Claude Guidelines

## Project Purpose

결제 도메인 학습용 MSA 플랫폼. 4 비즈니스 서비스(payment / pg / product / user) + Eureka + Gateway, hexagonal architecture, Kafka 양방향 비동기 confirm. TPS/latency 는 k6 벤치마크로 측정.

---

## Workflow

작업은 **discuss → plan → execute → ship** 4단계로 진행한다.

**활성화 조건**: `docs/STATE.md`에 활성 토픽이 있거나, 사용자가 워크플로우 단계를 명시하거나 새 토픽 설계를 요청할 때.
단순 질문이나 빠른 수정은 일반 요청으로 처리한다.

**역할 분담**: 메인 스레드가 인터뷰·설계·태스크 분해·마무리를 직접 수행한다. 서브에이전트는 독립 시각이 가치 있는 곳에만 쓴다 — 게이트 판정·코드 리뷰(`reviewer`, `domain-expert`)와 태스크 구현(`implementer`).

실행 방법은 `.claude/skills/workflow/SKILL.md`를 참고한다.

---

## Coding Rules

1. **Test-first**: 구현 전에 실패하는 테스트를 먼저 작성한다.
   - Domain entities: `@ParameterizedTest @EnumSource`로 유효/무효 상태 전환 모두 커버.
   - Use cases: Mockito 단위 테스트 먼저 작성.
2. **Minimal change**: 현재 태스크 범위 밖 코드는 수정하지 않는다. 발견한 문제는 주석으로 메모만 한다.
3. **Verify**: 매 태스크 완료 후 `./gradlew test`로 회귀 없음을 확인한다.

상세 코딩 컨벤션(주석/문서화 규칙, 안티패턴 회피 등)은 [`docs/context/CONVENTIONS.md`](docs/context/CONVENTIONS.md) 참고.

---

## Subagent 작업

대량 기계적 편집(전체 파일 주석 정리 등)을 서브에이전트로 병렬 위임하면, 에이전트는 자동 생성된 git worktree(`.claude/worktrees/agent-*`)에서 작업한다. 이 worktree 는 메인 HEAD 가 아닌 옛 커밋 기반일 수 있어, 변경이 메인 트리에 흩어지거나 결과가 메인 코드와 어긋날 수 있다.

- worktree 결과를 통째 복사하지 않는다 (옛 코드로 메인을 덮어쓸 위험).
- `git -C <worktree> diff` 로 patch 를 떠 메인에서 `git apply --check` 로 호환을 검증하고, 통과분만 `git apply` 한다. 충돌분(메인과 코드가 다른 파일)은 메인 기준으로 재작업한다.
- 작업 후 `git worktree remove -f -f <path>` 로 정리한다.

---

## Conversation Rules

1. **태스크 ID 단독 사용 금지**: PLAN.md 의 태스크 식별자(`A1`, `A5b`, `B7`, `T-E3`, `K12` 등)를 사용자 응답에서 단독으로 던지지 않는다. 사용자가 PLAN 을 매번 열지 않아도 맥락을 알 수 있도록 ID 옆에 그 작업의 내용을 1줄 이내로 풀어서 같이 적는다.
   - 나쁨: "A5b 후속에서 처리"
   - 좋음: "A5b (product-service container_name 제거 + Eureka instanceId 고유화) 후속에서 처리"
   - 또는 ID 빼고 내용으로만: "container_name 제거 작업 후속에서 처리"
   - 단, PLAN.md / commit message / implementer 디스패치 프롬프트 같은 산출물에서는 ID 그대로 OK (그게 본 식별자)

---

## Reference Files

> **작업 유형별 진입** — 전부 읽지 말고, 지금 하는 작업에 해당하는 줄의 문서만 연다.

| 지금 하는 작업 | 먼저 볼 문서 |
|---|---|
| 전체 구조 파악 / 새 기능 설계 | `ARCHITECTURE` → `STRUCTURE` |
| 결제 플로우(브라우저 → 결과) 수정 | `PAYMENT-FLOW` + `PITFALLS` |
| 비동기 confirm 사이클 수정 | `CONFIRM-FLOW` + `conventions/kafka` |
| 코드 작성 (스타일 / 규칙) | `conventions/` 해당 주제 (code-style / error-logging / transactions / kafka / testing) |
| 테스트 작성 | `TESTING` + `conventions/testing` |
| PG / 벤더 외부 연동 | `INTEGRATIONS` + `conventions/kafka` |
| 인프라 / 빌드 / 정적분석 | `STACK` |
| DB 마이그레이션 (Flyway) | `stack/flyway-operations` |
| 버그 / 도메인 함정 회피 | `PITFALLS` + `CONCERNS` |
| 인프라 헬스체크 / 트레이스 검증 | `docs/smoke/*` |
| 워크플로우 작업 재개 | `docs/STATE.md` (재개 메모) → 활성 산출물 |
| 과거 작업 맥락 파악 | `docs/archive/<topic>/COMPLETION-BRIEFING.md` |

### 영구 문서 (docs/context/) — 프로젝트 전체 생명주기

- [`docs/context/ARCHITECTURE.md`](docs/context/ARCHITECTURE.md) — 4서비스 토폴로지, hexagonal layer 룰, 비동기 어댑터 위치, 핵심 설계 결정 인덱스
- [`docs/context/STRUCTURE.md`](docs/context/STRUCTURE.md) — 디렉토리 트리, 모듈 의존, 패키지 컨벤션
- [`docs/context/STACK.md`](docs/context/STACK.md) — 기술 스택, 인프라, 빌드 / 정적 분석 (Flyway 운영 가이드는 [`stack/flyway-operations.md`](docs/context/stack/flyway-operations.md))
- [`docs/context/CONVENTIONS.md`](docs/context/CONVENTIONS.md) — 주제별 코딩 컨벤션 인덱스 (conventions/ 하위: code-style / error-logging / transactions / kafka / testing)
- [`docs/context/TESTING.md`](docs/context/TESTING.md) — Fake vs Mock 룰, Testcontainers, contract test, JaCoCo, TDD 흐름
- [`docs/context/INTEGRATIONS.md`](docs/context/INTEGRATIONS.md) — Toss + NicePay Strategy, cross-service HTTP, 외부 의존 관리
- [`docs/context/PAYMENT-FLOW.md`](docs/context/PAYMENT-FLOW.md) — end-to-end 결제 플로우 (브라우저 checkout → Gateway → payment ↔ pg ↔ vendor → 결과 콜백)
- [`docs/context/CONFIRM-FLOW.md`](docs/context/CONFIRM-FLOW.md) — payment-service 측 비동기 confirm 사이클 deep dive (분석 + Mermaid 다이어그램 통합)
- [`docs/context/PITFALLS.md`](docs/context/PITFALLS.md) — 학습된 도메인 함정 인덱스
- [`docs/context/CONCERNS.md`](docs/context/CONCERNS.md) — 알려진 우려 / 한계 / 회피된 우려
- [`docs/context/TODOS.md`](docs/context/TODOS.md) — 후속 + 향후 처리 항목

### 영구 도구 가이드 (docs/smoke/) — 시점 무관

- [`docs/smoke/infra-healthcheck.md`](docs/smoke/infra-healthcheck.md) — 인프라 + 4서비스 살아있음 검사 스크립트 가이드
- [`docs/smoke/trace-continuity-check.md`](docs/smoke/trace-continuity-check.md) — 분산 트레이스 연속성 검사 가이드

### 작업 산출물 — 작업 단위 생명주기

- `docs/STATE.md` — 활성 작업 포인터 + 재개 메모 (완료 이력은 쌓지 않는다)
- `docs/topics/<TOPIC>.md` — 진행 중 설계 문서 (상단에 사전/요약 브리핑 섹션 포함)
- `docs/<TOPIC>-PLAN.md` — 구현 플랜 (상단 요약 브리핑 + 하단 리뷰 처리 섹션)
- `docs/archive/<topic>/` — ship 완료 시 위 문서들 이동 + `COMPLETION-BRIEFING.md` (완료 작업 이력의 SSOT)

---

## Skills

- `.claude/skills/workflow/SKILL.md` — 4단계 워크플로우 라우터 + 공통 원칙 (격리·브리핑·게이트·STATE 형식)
- `.claude/skills/workflow-{discuss,plan,execute,ship}/` — 각 단계 오케스트레이터 (자기완결, 템플릿 인라인)
- `.claude/skills/{review,issue-commit-pr,context-update,writing,doc-review,wiki-access}/` — 단독 호출 스킬
- `.claude/agents/{reviewer,domain-expert,implementer}.md` — 서브에이전트 정의 (유일한 정의처)
- `.claude/skills/_shared/checklists/` — 게이트 체크리스트 4종 (discuss/plan/code/ship-ready)
- `.claude/skills/_shared/conventions/` — 커밋 / GitHub / 문서 작성 컨벤션

## Commit Style

세부 규칙은 `.claude/skills/_shared/conventions/commit.md` 참고. 요약:
- **`<type>(<scope>): <한글 제목>`** — type 은 영문(`feat`/`fix`/`refactor`/`test`/`docs`/`chore`/`build` 등), 제목·본문은 한글
- **scope 는 고정 어휘만**: 서비스(`payment`/`pg`/`product`/`user`/`gateway`/`eureka`) 또는 횡단(`docs`/`build`/`infra`/`deps`). 한 scope 로 못 묶으면 생략. 토픽명·태스크 ID 금지
- **마지막 줄 `Co-Authored-By:` 트레일러 일관 포함**
- amend 금지, 명시 staging, hook 우회 금지
- TDD: `test:`(RED) → `feat:`(GREEN+PLAN.md+STATE.md) → `refactor:`(선택)
- discuss/plan 산출물 각 단일 `docs:` 커밋, ship 최종 스냅샷 독립 커밋, STATE.md 단독 커밋 금지
