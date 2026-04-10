# code-round 프로토콜

execute 단계의 태스크 단위 TDD 실행 절차. `workflow-execute` 스킬이
각 태스크마다 이 프로토콜을 호출한다.

## Execution mechanism (필수)
- **Implementer만 서브에이전트로 실행한다.** 메인 스레드에서 TDD 사이클을 직접 수행하면 격리가 깨진다.
- **호출**: `Agent(subagent_type="implementer")` — 태스크당 1회.
- Implementer가 내부에서 `./gradlew test`를 직접 실행한다 (별도 Verifier dispatch 없음).
- **판정(Critic/Domain Expert)은 review 단계에서 일괄 실행** — execute 중 호출 없음.

## Participants
- **Implementer** — TDD 사이클 수행 + 테스트 실행

## Inputs
- `docs/<TOPIC>-PLAN.md` (현재 활성 태스크)
- STATE.md (active task)
- 관련 소스 파일 (Implementer가 수집)

## Outputs
- 소스 파일 변경
- RED / GREEN / REFACTOR 커밋
- PLAN.md 체크박스 업데이트 + "완료 결과"
- STATE.md active task 갱신

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

### 마지막 태스크 특별 처리
- 마지막 태스크의 GREEN 커밋 안에서 STATE.md stage → `review`로 전환
- 별도 커밋 없이 마지막 GREEN 커밋에 포함

## Pass 조건
- `./gradlew test` 전체 pass
- PLAN.md 체크박스 전부 체크

## State Transition
- 태스크 pass → 다음 태스크로 진행
- 모든 태스크 pass → `STATE.md` stage → `review`, 사용자 확인 대기
