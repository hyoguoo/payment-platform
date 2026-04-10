# plan-round 프로토콜

plan 단계의 라운드 기반 토론 절차. `workflow-plan` 스킬이 이 프로토콜을
호출하여 태스크 분해를 진행한다.

## Execution mechanism (필수)
- **모든 페르소나는 서브에이전트로만 실행한다.** 메인 스레드 흉내 금지.
- **호출**: `Agent` + `subagent_type: "planner" | "architect" | "critic" | "domain-expert"`.
- **병렬 dispatch**: 같은 라운드의 Critic + Domain Expert는 **단일 메시지에서 동시 호출**. Planner/Architect는 순차 (Architect가 Planner 초안에 주석 개입).
- **격리 원칙**: Critic/Domain Expert는 서로의 라운드 출력을 Read 하지 않는다.
- **판정 수용**: 서브에이전트가 저장한 JSON `decision` 필드만 기계적으로 읽는다.

## Participants
- **Planner** (Sonnet) — 태스크 분해
- **Architect** (Opus) — layer 의존성 · 아키텍처 적합성 검토 (Planner 초안에 주석 개입)
- **Critic** (Opus) — plan-ready 체크리스트 기반 판정
- **Domain Expert** (Opus) — domain risk 대응 태스크 존재 여부 판정

## Inputs
- `docs/topics/<TOPIC>.md` (discuss 산출물, 라운드 문서 포함)
- `docs/context/ARCHITECTURE.md`, `TESTING.md`
- `.claude/skills/_shared/checklists/plan-ready.md`

## Outputs
- `docs/<TOPIC>-PLAN.md`
- `docs/rounds/<topic>/plan-critic-<N>.md`
- `docs/rounds/<topic>/plan-domain-<N>.md`

## Plan 산출물 추가 필드
각 태스크에 `domain_risk: true | false` 플래그를 기재한다. code-round에서
Domain Expert 조건부 호출 여부를 결정하는 신호로 사용된다.
판단 기준: 결제 상태 전이 · 멱등성 · 정합성 · PII · 외부 PG 연동 · race window
중 하나라도 관련되면 `true`.

## Flow

### Round 1..N (최대 3)
1. **Planner**: PLAN.md 초안 작성
   - 태스크를 layer 의존 순서로 정렬 (port → domain → application → infrastructure → controller)
   - 각 태스크에 `tdd=true/false` + `domain_risk=true/false` 분류
   - 한 태스크 = 한 커밋 원칙, 크기 ≤ 2시간
   - tdd=true: 테스트 클래스 · 메서드 스펙 명시
   - tdd=false: 산출물 파일 · 위치 명시
2. **Architect**: layer 규칙 · 포트 위치 · 모듈 경계 검토, Planner 초안에 주석 개입
   (별도 라운드 문서 없음)
3. **Critic**: `plan-ready.md` 체크리스트 각 항목 yes/no → `plan-critic-<N>.md`
4. **Domain Expert**: domain risk 태스크 매핑 검토 → `plan-domain-<N>.md`
5. 5차원 보조 점수(traceability / decomposition / ordering / specificity / risk-coverage)
6. **합의 판정**: 둘 다 pass면 다음 단계(execute)로 전이

### Round 2 Unstuck 주입
2라운드 fail 시 Critic에 **simplifier 관점** 주입:
"태스크를 절반으로 줄이면 어디가 먼저 깎여야 하는가? 각 태스크가 정말 필요한가?"

### Round 3 소진 시
사용자 에스컬레이션 (discuss-round와 동일 패턴).

## Pass 조건
- `plan-ready.md` 전 항목 yes
- 두 페르소나 모두 `decision: pass`
- discuss에서 식별된 domain risk 전부 태스크로 매핑됨

## State Transition
pass 시 `STATE.md` stage → `plan-review`
