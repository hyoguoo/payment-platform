# discuss-round 프로토콜

discuss 단계의 라운드 기반 토론 절차. `workflow-discuss` 스킬이 이 프로토콜을
호출하여 설계 논의를 진행한다.

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
