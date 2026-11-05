# 프로젝트 지침

서버 백엔드 프로젝트다. 스택과 검증 명령은 [.agents/profiles/stack.md](.agents/profiles/stack.md),
지켜야 할 불변식과 핵심 경로는 [.agents/profiles/risk.md](.agents/profiles/risk.md)에 있다.

결제 도메인 학습용 Java 21 / Spring Boot 멀티모듈 프로젝트다. 비즈니스 서비스는 `payment-service`, `pg-service`,
`product-service`, `user-service`이고 `gateway`, `eureka-server`가 진입점과 서비스 탐색을 맡는다.
Claude Code와 Codex가 이 지침과 `.agents/`의 스킬·역할을 함께 쓴다. 도구별 운영은 [.agents/README.md](.agents/README.md)에 있다.

## 작업 원칙

- 한국어로 간결하게 보고한다. 태스크 ID를 말할 때는 내용을 함께 적는다. 비교와 흐름은 표·다이어그램으로 보여 준다.
  보고가 한 화면을 크게 넘으면 문서로 남긴다(discuss 중이면 BRIEF, 아니면 남길지 묻는다).
  사람이 읽는 문서는 [문서 작성 기준](.agents/conventions/writing.md)을 따른다.
- 요청과 관련된 코드와 문서만 읽는다. 현재 동작의 근거는 실제 코드와 빌드 설정이다.
- 새로 만들기 전에 이미 있는 기능·유틸·지난 결정([docs/archive](docs/archive/README.md))을 찾는다.
  요구에 없는 추상화, 설정값, 확장 지점을 만들지 않는다. "나중에 필요할 수 있다"는 근거가 되지 않는다.
- 기존 사용자 변경을 보존한다. 범위 밖 문제는 결과 보고에 적거나 [docs/TODOS.md](docs/TODOS.md)에 남긴다.
- 질문과 작업 요청이 섞여 있으면 질문에 먼저 답한다.
- 요청이 두 가지 이상으로 해석되면 추측해서 진행하지 않는다. 코드로 확인할 수 있는 것은 먼저 확인하고,
  남은 것은 해석 후보와 추천을 제시해 구체화한다. workflow 토픽에서는 discuss의 질문 방법을 따른다.
- 고르는 것에 따라 결과가 달라지는 선택지는 같은 기준으로 비교하고 추천과 이유를 붙인다. 기준은 복잡도, 위험,
  되돌리기 비용, 이후 작업에 주는 영향 중 해당하는 것만 쓴다. 단순 확인 질문에는 비교를 붙이지 않는다.

## 작업 규모

요청을 받으면 규모를 먼저 판단한다. 개발자가 규모나 진행 방식을 지정하면 그것을 따른다.

| 규모 | 기준 | 진행 |
|---|---|---|
| 작음 | 한두 파일, 동작 결정이 필요 없음 | 바로 수정한다. workflow를 쓰지 않는다. 아래 "작은 작업"을 따른다. |
| 중간 | 여러 파일이거나 동작 결정이 1~2개 | [workflow](.agents/skills/workflow/SKILL.md). 태스크 목록을 DESIGN에 함께 둔다. |
| 큼 | 서비스 경계·상태 모델·데이터 구조 변경, 여러 세션에 걸침 | workflow 4단계 전부 |
| 실험 | 측정·튜닝처럼 결과를 보고 다음 작업을 정함 | workflow 실험 모드 |

규모가 애매하면 사전 브리핑에서 규모를 제안하고 개발자가 정한다. 진행 중인 토픽이 있다는 이유만으로
무관한 요청을 그 토픽에 편입하지 않는다.

### 작은 작업

workflow 문서(BRIEF, DESIGN, STATE, 이슈, 브랜치, 아카이브)를 만들지 않는다. "작업 원칙", "구현과 검증",
"검증 hook"은 그대로 적용된다.

- 검토: 메인이 직접 한다. 변경이 risk.md의 [호출 조건](.agents/profiles/risk.md#호출-조건)에 해당하면 아래
  서브에이전트의 검토 배정을 그대로 따른다.
- 전환: 진행 중에 아직 정하지 않은 동작 결정이 필요해지거나 한두 파일을 넘어 범위가 커지면 멈춘다.
  동작 결정의 기준은 discuss의 [반드시 묻는다](.agents/skills/workflow-discuss/SKILL.md#질문하는-방법) 표다
  (보이는 동작, 범위, 외부 계약, 데이터, 새 라이브러리·인프라 도입 등). 작업 전에 이미 해당하면 작은 작업이 아니다.
  알게 된 것과 남은 결정을 보여 주고 workflow 토픽으로 전환할지 묻는다. 그때까지 고친 내용은 보존하고,
  전환하면 discuss 0단계의 조사 결과로 쓴다.
- 문서: 이번 수정 때문에 사실과 달라진 문서(docs/context, README, stack.md 등)는 같은 작업 안에서 고친다.
  고친 문서가 없으면 확인했다는 사실만 보고에 적는다.

## 승인 범위

- 승인된 범위(예: "구현까지 해줘")는 단계 전환, 파일 종류, 역할 호출을 이유로 다시 묻지 않고 끝까지 진행한다.
- 다음은 진행 중에도 묻는다: 아직 정하지 않은 제품 동작이나 범위 확장, workflow의 개발자 확인 지점,
  되돌리기 어려운 작업(데이터 삭제, 공유 환경 변경).
- 개발자가 건 제한("브리핑만", "계획까지만", "로컬에서만", "이슈 만들지 마")이 기본 절차보다 우선한다.
- 푸시·PR·병합은 요청할 때만 한다. 커밋도 요청할 때만 하되, workflow 토픽의 작업 브랜치에서는
  [Git 규칙](.agents/conventions/git.md#커밋)의 커밋 시점에 기본 수행한다. 이슈 생성과 브랜치 준비도 기본 수행한다.
- "진행하자" 같은 짧은 승인은 직전에 승인을 요청한 항목에만 적용한다. 열어 둔 질문의 답으로 보지 않는다.
  그 항목이 어디까지 포함하는지는 해당 스킬의 정의를 따른다(예: 합의 브리핑 확정은 계획과 구현까지 포함한다).

## 서브에이전트

| 역할 | 맡기는 일 | 정의 |
|---|---|---|
| implementer | 분리 가능한 구현 묶음, 리뷰 수정 묶음 | [.agents/roles/implementer.md](.agents/roles/implementer.md) |
| reviewer | 요구·설계 충족, 회귀, 테스트 누락 검토 | [.agents/roles/reviewer.md](.agents/roles/reviewer.md) |
| risk-reviewer | 핵심 경로 변경의 불변식·장애 경로 검토, 설계 사전 점검 | [.agents/roles/risk-reviewer.md](.agents/roles/risk-reviewer.md) |

역할 본문의 정본은 `.agents/roles/`, 모델·도구 설정의 정본은 `.agents/roles.toml`이다. `.claude/agents/`와 `.codex/agents/`는
`python3 scripts/sync-agent-configs.py`가 만드는 파일이라 직접 고치지 않는다.

- 작은 수정은 메인이 직접 한다. 서브에이전트 호출을 개발자에게 매번 묻지 않는다.
- 검토 배정은 변경 내용으로 정한다. 작은 작업은 위 "작은 작업"의 검토 규칙이 우선한다.
  - risk.md의 [호출 조건](.agents/profiles/risk.md#호출-조건)에 해당하는 변경(핵심 경로): risk-reviewer와 reviewer
  - 의미 있는 로직 변경: reviewer
  - 문서·테스트·설정만 바뀐 변경: 메인이 직접 검토
- 위임할 때는 목표, 대상 파일 또는 diff 기준, 관련 결정, 허용 쓰기 범위, 검증 범위를 준다.
  쓰기 범위가 겹치는 작업을 동시에 맡기지 않는다. 두 검토자는 서로의 결론을 입력받지 않는다.
- 서브에이전트를 쓸 수 없으면(사용량 제한, 도구 부재) 같은 호출을 반복하지 않는다. 메인이 같은 기준으로
  검토하고 완료 보고에 "독립 검토 미실시"를 적는다.

## 구현과 검증

- 행동 변경과 버그 수정은 실패하는 테스트를 먼저 쓰고 구현한다. 문구·문서·단순 설정 변경에는
  형식적인 테스트를 추가하지 않는다. 실패는 컴파일 오류가 아니라 기대한 이유의 단언 실패로 확인한다.
  테스트 목록에는 정상 경로와 함께 [테스트 관점](.agents/conventions/testing.md)의 예외 케이스를 넣는다.
- 검증은 stack.md의 순서를 따른다: 변경 모듈의 단위 테스트 → 정적 분석 → DB·메시징·설정 배선을 바꾼 경우
  통합 테스트. 공통 빌드나 공유 코드를 바꾸면 영향받는 모듈로 넓힌다.
- 상태 전이, 멱등성, 트랜잭션 경계, 외부 연동을 바꾸면 정상 경로와 함께 실패·재시도 경로도 확인한다.
- 지침·스킬·context 문서를 바꾸면 `python3 scripts/check-agent-docs.py --strict`로, 역할을 바꾸면
  `python3 scripts/sync-agent-configs.py` 뒤 `--check`로 확인한다. 문서만 바뀌면 Java 테스트는 생략한다.
- 완료 보고에는 실행한 검사와 결과(명령, 실행·통과·실패 건수), 실행하지 못한 검사와 사유를 구분해 적는다.
  workflow 토픽이거나 동작을 바꾼 작업이면, 요구사항·장애 시나리오별로 정상·실패·중복·동시성 중 무엇을
  어떤 테스트로 확인했는지 표로 덧붙인다. 문서·설정만 바꾼 작업에는 표를 붙이지 않는다.

## 검증 hook

`.claude/settings.json`이 아래 hook을 등록한다. 명령과 대상은 stack.md의 `verify-config` 블록에서 읽는다.

| hook | 언제 | 하는 일 |
|---|---|---|
| `.agents/hooks/lint-file.sh` | Edit·Write 직후 (메인, implementer) | 방금 고친 파일 하나만 린트한다. 위반이 있으면 바로 고친다. |
| `.agents/hooks/verify-on-stop.sh` | 메인 턴 종료, implementer 종료 | 기준 브랜치 분기점 이후 바뀐 모듈의 단위 테스트를 돌린다. 실패하면 종료가 막히고 실패 내용이 전달된다. |

- 종료 hook은 같은 변경 상태를 두 번 막지 않고, 세션당 3회까지만 막는다. 막히면 원인을 고치거나, 이번
  변경과 무관한 실패라면 근거를 보고한다. hook을 우회하거나 설정을 끄지 않는다.
- 코드 파일은 Edit·Write로 고친다. 셸(sed 등)로 고치면 편집 hook이 돌지 않는다.
- hook은 단위 테스트만 강제한다. 정적 분석과 통합 테스트는 위 검증 순서대로 직접 실행한다.
- **일시정지**: 개발자가 중단·일시정지를 요청했거나, 개발자 확인을 기다리며 의도적으로 미완성 상태(실패하는
  테스트를 먼저 쓴 상태 등)를 남길 때만 `.agents/hooks/verify-pause.sh "<사유>"`를 실행한다. 지금의 변경
  상태에만 적용되고 코드가 바뀌면 자동으로 풀린다. 쓴 경우 보고에 일시정지 사실, 사유, 남은 실패를 적는다.
- 판정 기록은 `.git/agent-verify/trace.log`에 남는다. hook이 돌았는지 의심되면 여기서 확인한다.
- hook은 Claude Code 설정이다. Codex 세션에는 hook이 없으므로 stack.md의 검증 순서를 직접 실행하고 결과를 보고한다.

## 흔한 실수

- 단순 질문이나 작은 수정을 workflow로 키운다.
- "브리핑만" 요청한 턴에 설계 상세 작성, 이슈 생성, 구현까지 진행한다.
- 사전 브리핑에서 합의하지 않은 결정을 DESIGN에 새로 넣는다.
- 물어야 할 제품 동작이나 범위를 "제안"으로 처리하고 넘어간다. 미결 질문이 남은 채 다음 단계로 간다.
- 합의 뒤 들어온 변경 요청을 수준 판별 없이 바로 구현하거나, 리뷰 뒤 변경 요청으로 바뀐 코드를 재검토 없이 마무리한다.
- 코드나 문서로 확인할 수 있는 것을 개발자에게 묻는다.
- 테스트나 검증 없이 완료로 보고하거나, 실패한 태스크를 완료로 표시한다.
- 검증 실패를 피하려고 일시정지 스크립트를 쓴다.
- 다른 토픽의 STATE 항목이나 재개 메모를 지운다.

## 코드 리뷰

리뷰 기준은 [review](.agents/skills/review/SKILL.md)다. 이 프로젝트에서는 중복 결제, 메시지 재전송, 재시도, 부분 실패에서
돈과 재고 정합성이 깨지는 경로를 먼저 보고, 판단 기준은 [risk.md](.agents/profiles/risk.md)의 불변식이다.
구체적인 재현 조건과 파일 위치가 있는 결함만 보고하고, 스타일은 정적 분석 결과에 맡긴다.

## 참고 문서

| 작업 | 문서 |
|---|---|
| 진행 중인 토픽, 재개 | [docs/STATE.md](docs/STATE.md) |
| 프로젝트 구조·흐름·규칙 | [docs/context/](docs/context/README.md) |
| 지난 작업과 결정 | [docs/archive/README.md](docs/archive/README.md) |
| Git 규칙(이슈, 브랜치, 커밋, PR) | [.agents/conventions/git.md](.agents/conventions/git.md) |
| 테스트 목록 작성(예외 케이스 관점) | [.agents/conventions/testing.md](.agents/conventions/testing.md) |
| 사람이 읽는 문서 작성(브리핑, 보고서, PR 본문) | [.agents/conventions/writing.md](.agents/conventions/writing.md) |
| 위키·포스팅·README 작성 | [writing 스킬](.agents/skills/writing/SKILL.md) |
| 사람용 결제 흐름 안내 | [docs/guides/](docs/guides/) |
| 스모크·트레이스·알람 확인 | [docs/smoke/](docs/smoke/) |
| 결제 품질·처리량 측정 | [docs/quality/README.md](docs/quality/README.md) |
| Claude Code·Codex 운영, 역할 동기화 | [.agents/README.md](.agents/README.md) |
