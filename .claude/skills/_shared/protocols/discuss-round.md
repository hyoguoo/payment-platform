# discuss-round 프로토콜

discuss 단계의 라운드 기반 토론 절차. `workflow-discuss` 스킬이 이 프로토콜을
호출하여 설계 논의를 진행한다.

## Execution mechanism (필수)
- **모든 판정 페르소나는 서브에이전트로만 실행한다.** 메인 스레드에서 페르소나를 흉내 내어 체크리스트를 판정하면 self-rubber-stamp가 된다.
- **호출 방식**: `Agent` 툴 + `subagent_type: "<name>"` (critic / domain-expert / architect / ...). 정의는 `.claude/agents/*.md`.
- **Interviewer만 예외**: `AskUserQuestion` 등 사용자 상호작용이 필요하므로 메인 스레드 실행.
- **병렬 dispatch 필수**: 같은 라운드의 Critic + Domain Expert는 **단일 메시지 안에서 두 Agent 툴콜을 동시에** 호출한다. 순차 호출 시 두 번째가 첫 번째 결과로 오염된다.
- **격리 원칙**: 페르소나는 같은 라운드의 sibling 출력(`discuss-critic-N.md` ↔ `discuss-domain-N.md`)을 Read 하지 않는다. 독립 판정이 생명.
- **판정 수용 규칙**: 오케스트레이터는 서브에이전트가 저장한 JSON의 `decision` 필드만 기계적으로 읽는다. 메인 스레드에서 재해석·재판정 금지.

예시 (병렬 dispatch):
```
Agent(subagent_type="critic",       prompt="<topic> Round 1 판정. 산출물: docs/topics/<TOPIC>.md. 출력: docs/rounds/<topic>/discuss-critic-1.md")
Agent(subagent_type="domain-expert", prompt="<topic> Round 1 판정. 산출물: docs/topics/<TOPIC>.md. 출력: docs/rounds/<topic>/discuss-domain-1.md")
```
두 호출은 **같은 응답 블록**에서 내보낸다.

## Participants
- **Interviewer** (Opus) — 되묻기와 가정 검증으로 모호함 해소
- **Architect** (Opus) — 설계안 작성 및 수정
- **Critic** (Opus) — discuss-ready 체크리스트 기반 판정
- **Domain Expert** (Opus) — 결제 도메인 리스크 관점 판정

## Inputs
- 사용자 요청 (자연어)
- `docs/context/ARCHITECTURE.md`, `CONVENTIONS.md`, `INTEGRATIONS.md`
- 관련 소스 코드 (Architect/Interviewer가 Read/Grep으로 수집)
- `.claude/skills/_shared/checklists/discuss-ready.md`

## Outputs
- `docs/topics/<TOPIC>.md` (최종 설계 문서)
- `docs/rounds/<topic>/discuss-critic-<N>.md` (라운드별 Critic 판정)
- `docs/rounds/<topic>/discuss-domain-<N>.md` (라운드별 Domain Expert 판정)
- `docs/rounds/<topic>/discuss-interview-<N>.md` (라운드별 Interviewer 질의응답 요약)

## Flow

### Round 0 — Interviewer 주도 명료화
1. Interviewer가 사용자 요청을 받아 **ambiguity ledger**(scope / constraints /
   outputs / verification 4트랙) 초기화
2. 각 질문을 3-Path Routing으로 분류:
   - **Path 1 (code)**: Read/Grep으로 답변 후 사용자 확인
   - **Path 2 (user)**: AskUserQuestion으로 직접 질문
   - **Path 3 (hybrid)**: 코드 조사 결과 제시 + 사용자 판단 요청
   - **Path 4 (research)**: WebFetch/Context7로 조사 후 사용자 확인
3. **Dialectic Rhythm Guard**: Path 1/4가 3연속이면 다음 질문은 반드시 Path 2
4. scope / constraints / outputs / verification 4트랙 모두 최소 1회 커버되면 종료
5. 결과를 `discuss-interview-0.md`로 저장, Architect에게 넘김

### Round 1..N — 토론 라운드 (최대 3)
1. **Architect**: Interviewer 결과를 바탕으로 `docs/topics/<TOPIC>.md` 작성 (또는 수정)
2. **Critic**: `discuss-ready.md` 체크리스트 각 항목을 yes/no로 판정,
   fail 항목에 대해 근거 · 위치 · 수정 제안 작성 → `discuss-critic-<N>.md`
3. **Domain Expert**: 체크리스트의 "domain risk" 섹션 + 결제 도메인 추가 검토
   → `discuss-domain-<N>.md`
4. 5차원 보조 점수(clarity / completeness / risk / testability / fit) 출력 (판정 기준 아님)
5. **합의 판정**: 두 페르소나의 JSON `decision: pass` 확인
   - 둘 다 pass → 다음 단계(plan)로 전이
   - 하나라도 revise/fail → Architect가 다음 라운드에서 수정

### Round 2 특별 처리 — Unstuck 주입
Round 2에서도 fail이면, Critic에게 **unstuck-round의 contrarian 관점**을 주입:
"이제까지의 비판 각도와 다른 관점으로 근본 가정을 뒤집어 보라."
Architect는 이를 받아 설계 근본 전제를 재검토한다.

### Round 3 소진 시
사용자에게 에스컬레이션:
- 현재까지의 라운드 문서 요약 제시
- "계속 진행 / 추가 라운드 / 중단" 중 선택 요청

## Pass 조건
- `discuss-ready.md` 전 항목 yes
- 두 페르소나 모두 `decision: pass`
- 3라운드 이내 달성 (또는 사용자 승인)

## State Transition
pass 시 `STATE.md` stage → `plan`
