# verify-round 프로토콜

verify 단계의 최종 점검 절차. 전체 테스트 → context 문서 갱신 → 아카이브 →
STATE 종결 → PR 생성까지 결정론적으로 처리한다.

## Participants
- **Verifier** (Haiku) — 전체 `./gradlew test` 실행
- **Critic** (Opus) — `verify-ready.md` 체크리스트 판정
- **PR Manager** (Haiku) — `vc-round.md` 규칙에 따른 PR 생성/갱신

## Inputs
- `docs/<TOPIC>-PLAN.md`, `docs/topics/<TOPIC>.md`
- `docs/context/*.md`
- STATE.md
- `.claude/skills/_shared/checklists/verify-ready.md`

## Outputs
- 전체 테스트 결과
- `docs/context/` 갱신 (변경된 경우)
- `docs/archive/<topic-kebab>/` 로 아카이브
- STATE.md 최종 상태
- `docs/rounds/<topic>/verify-critic-<N>.md`
- PR URL

## Flow

### Step 1 — Verifier
- `./gradlew test` 실행 (전체)
- 실패 시 Implementer에게 복귀 (verify 중단)

### Step 2 — Context 문서 갱신
- `git diff main...HEAD --stat` 기준으로 `docs/context/` 영향 범위 식별
- 필요 시 ARCHITECTURE/CONVENTIONS/INTEGRATIONS 등 갱신

### Step 3 — 아카이브
- `git mv docs/<TOPIC>-PLAN.md docs/archive/<topic-kebab>/`
- `git mv docs/topics/<TOPIC>.md docs/archive/<topic-kebab>/<TOPIC>-CONTEXT.md`
- `docs/archive/README.md` 테이블에 행 추가

### Step 4 — STATE.md 종결
- stage → `done`
- `.continue-here.md` 삭제(존재 시)

### Step 5 — Critic 판정
- `verify-ready.md` 체크리스트 yes/no
- `verify-critic-<N>.md` 작성
- decision != pass → 실패 항목 수정 후 재시도

### Step 6 — 최종 커밋
- `commit-round.md` 규칙 준수
- context 갱신 + 아카이브 + STATE.md를 단일 `docs:` 커밋으로 묶기

### Step 7 — PR Manager
- `vc-round.md` Step 3 수행
- 이미 PR 존재 시 Step 4 갱신

## Pass 조건
- 전체 `./gradlew test` pass
- `verify-ready.md` 전 항목 yes
- Critic `decision: pass`
- PR 생성/갱신 완료

## State Transition
pass → STATE.md stage → `done`
