# code-round 프로토콜

execute 단계의 태스크 단위 TDD 실행 및 판정 절차. `workflow-execute` 스킬이
각 태스크마다 이 프로토콜을 호출한다.

## Participants
- **Implementer** (Sonnet) — TDD 사이클 수행
- **Verifier** (Haiku) — `./gradlew test` 실행 및 결과 파싱 (결정론적)
- **Critic** (Opus) — code-ready 체크리스트 기반 판정
- **Domain Expert** (Opus) — 도메인 리스크 재검토 (해당 태스크가 `domain_risk=true`일 때만)

## Inputs
- `docs/<TOPIC>-PLAN.md` (현재 활성 태스크)
- STATE.md (active task)
- `.claude/skills/_shared/checklists/code-ready.md`
- 관련 소스 파일 (Implementer가 수집)

## Outputs
- 소스 파일 변경
- RED / GREEN / REFACTOR 커밋
- PLAN.md 체크박스 업데이트 + "완료 결과"
- STATE.md active task 갱신
- `docs/rounds/<topic>/code-<task-id>-critic-<N>.md` (Critic 판정)
- `docs/rounds/<topic>/code-<task-id>-domain-<N>.md` (`domain_risk=true`인 태스크만)

## Flow (태스크당 1회)

### Step 1 — TDD 실행 (Implementer)

**tdd=true 분기:**
1. RED: 실패하는 테스트 작성 → 테스트 실행하여 실패 확인 → `test:` 커밋
2. GREEN: 최소 구현 작성 → `./gradlew test` 전체 통과 확인
   → PLAN.md 체크박스 + "완료 결과" 업데이트 → STATE.md active task 갱신
   → `feat:` 커밋 (구현 + 문서 업데이트 단일 커밋)
3. REFACTOR (선택): 개선 → 전체 테스트 재실행 → `refactor:` 커밋

**tdd=false 분기:**
1. 산출물 작성 (설정 / 상수 / port interface 등)
2. `./gradlew test` 전체 통과 확인
3. PLAN.md + STATE.md 업데이트
4. `chore:` / `feat:` 커밋 (단일)

### Step 2 — Verifier (결정론적 백본)
- `./gradlew test` 결과 파싱
- pass 개수 / fail 개수 / 커버리지 리포트
- fail이 있으면 태스크 미완료로 간주, Implementer에게 피드백

### Step 3 — Critic 판정 (라운드 시작)
- `code-ready.md` 체크리스트 yes/no 판정
- 5차원 보조 점수 (correctness / conventions / discipline / test-coverage / domain)
- `code-<task-id>-critic-1.md` 작성

### Step 4 — Domain Expert 판정 (조건부)
태스크 플래그가 `domain_risk=true`인 경우만 호출.
결제 무관 태스크(설정 파일 등)는 스킵.

### Round 2..3 (fail 시)
- Implementer가 피드백을 받아 수정
- 새 수정은 **amend 없이 새 커밋** (commit_style 규칙 준수)
- 라운드 상한 3회
- 2라운드 fail 시 **researcher 관점** 주입: "무엇을 아직 모르고 있는가?"

### 마지막 태스크 특별 처리
- 마지막 태스크의 GREEN 커밋 안에서 STATE.md stage → `review`로 전환
- 별도 커밋 없이 마지막 GREEN 커밋에 포함

## Pass 조건
- `code-ready.md` 전 항목 yes
- Verifier: `./gradlew test` 전체 pass
- Critic: `decision: pass`
- Domain Expert (해당되는 경우): `decision: pass`

## State Transition
- 태스크 단위 pass → 다음 태스크로 진행
- 모든 태스크 pass → `STATE.md` stage → `review`, 사용자 확인 대기
