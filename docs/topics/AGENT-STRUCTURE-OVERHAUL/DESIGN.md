# AGENT-STRUCTURE-OVERHAUL 설계 상세

> 에이전트가 근거와 작업 기록으로 쓰는 문서다. [BRIEF](BRIEF.md)에서 합의하지 않은 결정은 넣지 않는다.
> 에이전트가 근거 없이 정한 것은 "제안"으로 표시한다. kit = `../agent-backend-kit` (커밋 `7273cfd`).

## 결정

| ID | 결정 | 이유 | 버린 대안 (가장 단순한 방법 포함) | 근거 |
|---|---|---|---|---|
| D1 | kit 파일을 복사해 시작하고, 프로젝트 고유 부분만 덧붙인다. kit 문장을 다시 쓰지 않는다 | 이후 kit 개선분을 diff로 가져오려면 문장이 같아야 한다 | 현재 지침을 kit 방향으로 고쳐 쓰기: 두 저장소의 문장이 갈라진다 | 승인된 브리핑 (제안 방향) |
| D2 | 작업 순서: 1단계 지침 구조(T1~T10) → 2단계 문서 정리(T11~T14) → 판정(T15) | 2단계가 1단계 규칙(context README, 작성 기준, 문서 검사)을 실제로 써 본다 | 문서부터 정리: 옛 규칙으로 쓴 문서를 새 규칙으로 다시 고쳐야 한다 | 승인된 브리핑 |
| D3 | 프로젝트 값은 `.agents/profiles/stack.md`, `risk.md`에만 둔다. hook 스크립트는 kit 그대로 두고 stack.md의 `verify-config`만 채운다 | 스택이 바뀌어도 hook을 고치지 않는다 | 현재 hook(`.claude/hooks/`)에 모듈 목록 하드코딩 유지 | 요구사항 R2·R5 |
| D4 | `verify-config`: `MODULES=payment-service pg-service product-service user-service gateway eureka-server`, `ROOT_FILES=build.gradle settings.gradle gradle.properties gradle/ config/`, `UNIT_TEST=./gradlew --console=plain {tasks}`, `UNIT_TEST_TASK=:{m}:test`, `TEST_REPORTS={m}/build/test-results/test/*.xml`, `VERIFY_PATTERN`은 현재 hook과 같은 `\.(java|sql|gradle|ya?ml|properties)$` | 현재 hook의 판정 범위를 그대로 옮긴다 | kit 기본 패턴(`kts`, `xml` 포함): `xml`이 들어가면 Checkstyle·SpotBugs 설정 변경도 테스트를 일으키는데, 그건 `ROOT_FILES`의 `config/`가 이미 잡는다 | 제안 |
| D5 | `config/checkstyle/check-file.sh`는 kit 파일에 `-p build/checkstyle-cli.properties`만 더한다 | 이 프로젝트의 `checkstyle.xml`이 속성 파일(`writeCheckstyleCliClasspath`가 만듦)을 읽는다 | 속성 없이 실행: 설정 로드에 실패해 린트가 늘 "도구 실패"로 통과한다 | 제안 (현재 `checkstyle-file.sh`·`build.gradle:74` 확인) |
| D6 | `SubagentStop`은 `matcher: implementer`로만 건다. 현재 hook의 읽기 전용 에이전트 제외 목록과 "종류 미상 중복 이벤트" 제외는 kit 방식으로 대체된다 | kit은 등록 단계에서 대상을 거른다 | 현재 제외 목록 유지: 역할 이름이 바뀔 때마다 hook을 고쳐야 한다 | 제안 |
| D7 | 판정 상태 디렉토리가 `.git/claude-stop-verify/` → `.git/agent-verify/`로 바뀐다. 옛 디렉토리는 지우지 않는다 | 로컬 상태라 남아도 무해하고, 지우는 것은 되돌릴 수 없다 | 옛 디렉토리 삭제 | 제안 |
| D8 | 역할: `implementer`·`reviewer`·`risk-reviewer`. 본문은 kit의 `.claude/agents/*.md` 본문을 `.agents/roles/*.md`로 옮기고, `roles.toml` → `sync-agent-configs.py`로 생성한다. risk-reviewer Claude 모델 `opus`, effort `high`. Codex 모델은 지금 값 유지 | Codex 지원 유지와 kit 역할 본문을 둘 다 만족 | kit처럼 `.claude/agents`를 직접 쓰기: Codex 설정이 따로 놀게 된다 | 개발자 답변 (Q2, Codex 유지) |
| D9 | `domain-expert` 생성 파일(`.claude/agents/domain-expert.md`, `.codex/agents/domain-expert.toml`)과 `.agents/roles/domain-expert.md`는 지운다. 결제 점검 항목은 `risk.md`의 불변식과 위험 범주 "프로젝트 메모"로 옮긴다 | 역할 기준이 `risk.md` 한 곳이 된다 | 별칭으로 남기기 | 개발자 답변 (Q2) |
| D10 | `risk.md` 불변식 초안은 아래 "불변식 초안" 표. 합의 브리핑에서 확인받는다 | 불변식은 업무 규칙이라 개발자가 정한다 | 비워 두기: risk-reviewer가 호출되지 않는다 | 합의 브리핑에서 확인 |
| D11 | Git 규칙은 kit `conventions/git.md` 하나로 합친다. scope 목록은 지금 규칙(`payment`, `pg`, `product`, `user`, `gateway`, `eureka`, `docs`, `build`, `infra`, `deps`)을 stack.md 모듈 표에 둔다. Codex 작성자 표기(`Co-Authored-By: Codex <noreply@openai.com>`)를 덧붙인다 | `commit.md`·`github.md`·issue-commit-pr 스킬 세 곳을 하나로 | 스킬 유지 | 승인된 브리핑 (R6) |
| D12 | 작성 기준 두 단계: 공통 `.agents/conventions/writing.md`(kit) + `.agents/skills/writing/references/style-guide.md`(개발자 원문 1~7절 + 지금 규칙 중 원문에 없는 것). 공통 기준 문장 절에 "위키·포스팅은 상세판을 따른다" 링크를 둔다 | 브리핑은 가볍게, 위키·포스팅은 강하게 | 한 파일로 합치기, 두 벌 유지 | 개발자 답변 (Q4) |
| D13 | 지금 `writing-style.md`에서 옮길 항목: 메타 도입어 금지, 균일한 병렬 나열 금지, 번역투 조사, 영어 구조 대구·종속절, 문장 밀도(긴 불릿은 하위 단계로). `writing-terminology.md`: 식별자 vs 도메인 표현 4단계 규칙, 메서드 호출 서술 금지, 즉석 코드 라벨 금지, 동의어 통일, 약어 풀어쓰기. `writing-visuals.md`: Mermaid 금지 문자, 점선 라벨 표기. 옮길 때 원문과 같은 뜻의 항목은 원문 쪽만 남긴다 | 원문에 없는 규칙만 보탠다 | 4개 파일 통째 보존 | 개발자 답변 (Q4) |
| D14 | 스킬 이름: `review`(kit), `explain-change-html`(kit, `explain-diff-html` 대체), `issue-commit-pr` 삭제. 남기는 전용 스킬: payment-live-drill, wiki-access, portfolio-access, writing, doc-review, context-update | | | 개발자 답변 (전용 스킬 선택) |
| D15 | context-update 스킬은 ship의 "문서 동기화"가 호출하는 절차로 줄인다. 단독 요청("context 문서 점검해줘")에도 쓴다 | kit ship 3단계가 같은 일을 하지만 이 프로젝트는 문서가 많아 대조 절차가 필요하다 | 삭제하고 ship에 흡수 | 개발자 답변 (유지) + 제안 (역할 축소) |
| D16 | `check-agent-docs.py`: 체크리스트 참조 검사·중복 규칙 검사를 지운다(대상이 사라짐). 참조 무결성·frontmatter·Mermaid·고아 문서·역할 동기화는 유지. 새 검사 "문서 식별자 대조"를 더한다 | 판정 4를 자동화 | 별도 스크립트: 실행 진입점이 늘어난다 | 판정 4 + 제안 (배치) |
| D17 | 문서 식별자 대조 범위: `docs/context`, `docs/guides`, `docs/smoke`, `docs/quality`의 백틱 스팬 중 (a) 경로 모양 → 파일 존재, (b) `UpperCamelCase` 단어 → `*/src/**/*.java`에 같은 이름의 타입 선언, (c) 점으로 이은 소문자 키(`spring.kafka.x` 등) → `*/src/main/resources/**/*.yml` 또는 `docker/**`·`scripts/**`에 등장. 코드 펜스 안은 제외 | 오탐을 줄이려고 모양이 분명한 것만 본다 | 모든 백틱 스팬 대조: 상태값·명령어가 오탐이 된다 | 제안 |
| D18 | STATE는 kit 형식(토픽마다 제목 + 다섯 줄). "재개 메모"의 별건과 `docs/context/TODOS.md`는 `docs/TODOS.md` 표로 합치고, 코드로 해결이 확인된 항목은 지운다(근거는 진행 기록에) | | | 요구사항 R7 |
| D19 | 진행 중 두 토픽은 새 형식으로 다시 쓴다. PAYMENT-THROUGHPUT은 측정 토픽이라 `BRIEF.md`(사전 브리핑 = 측정 목표·판정 기준·고정 조건) + `EXPERIMENTS.md`(회차). 기존 PLAN의 태스크 중 측정이 아닌 코드 태스크(T3 회귀·복구 검증 등)는 EXPERIMENTS가 아닌 DESIGN의 태스크로. CHECKOUT-RECEIPT-TRACKING은 discuss 중이라 `BRIEF.md`(질문 목록 포함)만. 두 토픽의 `docs/reports/*` 파일은 각 토픽 폴더로 `git mv` | 개발자가 새 형식으로 다시 쓰기를 골랐다 | 폴더 이동만 | 개발자 답변 (Q5) |
| D20 | RUNBOOK 명령·측정 조건을 고치면, 바뀐 항목마다 "이전 → 이후, 이전 회차와 비교 가능 여부"를 PAYMENT-THROUGHPUT의 EXPERIMENTS 고정 조건 아래와 완료 보고에 적는다 | 개발자 요청 | | 개발자 답변 (Q5) |
| D21 | context 구성은 BRIEF 질문 1의 A안 표를 따른다. `PAYMENT-FLOW-GUIDE.md` → `docs/guides/payment-flow.md`, `CODEX.md` → `.agents/README.md`(도구 운영·역할 동기화·검증), `CLAUDE-5-PROMPTING.md` → `.agents/references/claude-5-prompting.md` | | | 개발자 답변 (Q1) |
| D22 | 모든 context 문서 머리의 "최종 갱신 · 이전: …" 줄을 지운다. 갱신 이력은 Git과 archive가 가진다 | 판정 4 | 최신 날짜 한 줄만 남기기: 날짜도 Git에 있다 | 판정 4 |
| D23 | 이슈는 합의 브리핑 확정 직후 만들고, 커밋은 kit Git 규칙의 커밋 시점(BRIEF·DESIGN → 태스크마다 → 리뷰 수정 → 아카이브)에 한다 | | | 개발자 답변 (Q6) |
| D24 | `.github/workflows/ci.yml`의 agent-docs·sync·hook 테스트 단계는 경로만 새 구조로 고치고 실행 방식(agent-docs는 정보 보고, sync·hook은 실패 시 중단)은 유지 | 공유 빌드 변경은 최소로 | strict로 올리기: 범위 밖 | 제안 |
| D25 | 컨테이너를 띄우는 테스트 9개에 `@Tag("integration")`를 붙여 `integrationTest`로 옮긴다. 대상: payment `StockCacheRedisAdapterTest`, `StockCacheRedisAdapterOrderLockTest`, `StockDecrementAtomicLuaTest`, `StockRejectCompensationLuaTest`, `JpaPaymentOutboxRepositoryTest`, `StockHoldRecordRepositoryImplTest` / pg `PgOutboxRepositoryImplTest`, `PgInboxRepositoryImplTest` / product `ProductRepositoryImplPageTest`. hook은 kit 동작 그대로 | 테스트 분류 기준이 "앱 전체를 띄우는가"여서, Docker가 꺼지면 단위 테스트와 종료 검증이 실패했다. JaCoCo가 `**/infrastructure/**`를 이미 빼므로 커버리지 게이트 영향 없음 | hook이 Docker 부재 실패를 경고로 넘기기: 테스트가 안 돈 턴이 지나간다 / 지금처럼 막기: 원인이 남는다 | 개발자 답변 (Q7) |
| D26 | TESTING.md 분류 기준을 "외부 프로세스(컨테이너)를 띄우면 통합"으로 고치고, 같은 규칙을 `stack.md` 코드 규칙에 한 줄로 둔다. ArchUnit 같은 자동 강제는 넣지 않는다 | 새 테스트가 같은 실수를 반복하지 않게 | ArchUnit 규칙 추가: 요구에 없는 강제 장치 | 제안 |

### 불변식 초안 (D10)

지금 domain-expert 본문의 점검 항목, AGENTS.md의 Code Review Rules, 결제 상태 enum(`PaymentEventStatus`, `PaymentOrderStatus`)에서 뽑았다.

| ID | 불변식 | 깨지면 생기는 일 |
|---|---|---|
| INV-1 | 같은 결제는 PG에 한 번만 승인을 요청하고, 승인은 한 번만 반영된다. 요청·메시지가 다시 와도 같다 | 이중 결제 |
| INV-2 | 종결 상태(`DONE`, `FAILED`, `CANCELED`, `PARTIAL_CANCELED`, `EXPIRED`)로 간 결제는 다른 상태로 되돌아가지 않는다 | 승인된 결제가 실패로 보이거나 재승인 |
| INV-3 | PG의 "이미 처리됨" 응답은 주문 ID·금액·상태를 대조한 뒤에만 성공으로 본다 | 다른 주문이나 다른 금액의 승인을 성공으로 처리 |
| INV-4 | 승인 금액은 주문 금액과 같다 | 금액 불일치 |
| INV-5 | 재고 차감과 복구(보상)는 주문·상품마다 한 번만 반영된다. 보상을 다시 실행해도 결과가 같다 | 재고 과다·과소 |
| INV-6 | 결과를 모르는 결제(타임아웃 등)를 실패로 확정하지 않는다. 확인 경로를 거쳐 종결한다 | 승인됐는데 실패 처리, 재고만 복구 |
| INV-7 | 로그·응답·메시지에 결제 키·카드 정보·비밀 값이 남지 않는다 | 민감정보 노출 |

핵심 경로 초안: `payment-service/src/main/java/**`, `pg-service/src/main/java/**`, `product-service/src/main/java/**/stock/**`(재고), `*/src/main/resources/db/migration/**`, Kafka 토픽·메시지 계약(`**/messaging/**`, `**/event/**`). T1에서 실제 패키지로 확인해 glob을 확정한다.

## 영향 범위

| 영역 | 바뀌는 것 |
|---|---|
| 지침 | `AGENTS.md`(재작성), `CLAUDE.md`(신규, `@AGENTS.md`) |
| `.agents/` | 신규 `profiles/`, `hooks/`, `conventions/`, `templates/`, `README.md`, `references/`. 재작성 `roles/`, `roles.toml`, `skills/` 전부. 삭제 `skills/_shared/`, `skills/issue-commit-pr/`, `skills/explain-diff-html/`, `roles/domain-expert.md` |
| `.claude/` | `settings.json` hook 경로. 삭제 `hooks/`. 생성 파일 `agents/*.md` |
| `.codex/` | 생성 파일 `agents/*.toml` |
| `scripts/` | `check-agent-docs.py`, `test-hooks.sh`. `sync-agent-configs.py`는 바꿀 필요가 없을 수 있다(T3에서 확인) |
| 빌드·CI | `config/checkstyle/check-file.sh`(신규), `build.gradle:59` 주석의 hook 경로, `.github/workflows/ci.yml` 경로 |
| 문서 | `docs/STATE.md`, `docs/TODOS.md`(신규), `docs/archive/README.md`, `docs/context/*`, `docs/guides/`(신규), `docs/smoke/*`, `docs/quality/*`, `docs/topics/*`, `docs/reports/*`(토픽 폴더로 이동), `docs/PAYMENT-THROUGHPUT-PLAN.md`(토픽 폴더로) |
| 바뀌지 않는 것 | 서비스 코드·테스트, archive 폴더 안의 기록 |

## 장애 시나리오

에이전트 작업 흐름에서 지침·hook이 잘못 동작하는 경우다. 결제 불변식과는 관련이 없다.

| ID | 상황 | 기대 동작 | 확인 방법 |
|---|---|---|---|
| FS-1 | 문서만 고친 턴 | 이미 통과한 지문이면 테스트를 돌리지 않는다 | test-hooks 케이스 + trace.log |
| FS-2 | `payment-service` Java 파일만 바뀜 | `:payment-service:test`만 실행 | test-hooks(가짜 gradlew가 받은 인자 확인) + 실제 세션 trace.log |
| FS-3 | `build.gradle` 또는 `config/` 변경 | 같은 설정 상태로 통과하기 전까지 6개 모듈 전부 실행, 통과 뒤에는 바뀐 모듈만 | test-hooks |
| FS-4 | 테스트 실패 | exit 2로 막고 실패 테스트 이름을 전달. 같은 지문은 다시 막지 않고, 세션당 주체별 3회 | test-hooks |
| FS-5 | 컴파일 실패로 결과 XML이 갱신되지 않음 | 지난 실행의 XML을 읽지 않고 빌드 출력 끝부분을 전달 | test-hooks |
| FS-6 | reviewer·risk-reviewer·Explore 종료 | 종료 검증이 돌지 않는다 | settings.json matcher + 실제 세션 trace.log |
| FS-7 | implementer와 메인이 동시에 종료 검증 | 잠금으로 직렬화, 대기 중 통과된 지문은 다시 돌리지 않는다 | test-hooks |
| FS-8 | Checkstyle classpath 생성 실패·java 없음 | 린트가 막지 않고 trace에 도구 실패를 남긴다 | test-hooks |
| FS-9 | Java 파일 하나에 Checkstyle 위반 | 편집 직후 exit 2와 위반 줄 | test-hooks(실제 Checkstyle) |
| FS-10 | 일시정지 후 코드 변경 | 표식이 자동으로 풀리고 다시 검증 | test-hooks |
| FS-11 | 역할 이름 변경 뒤 옛 생성 파일이 남음 | `sync-agent-configs.py --check`가 "정본에 없는 생성 파일"로 실패 | 스크립트 실행 |
| FS-12 | Codex 세션 | hook이 없으므로 AGENTS.md의 검증 순서를 직접 실행하고 보고에 적는다 | 판정 3의 Codex 확인(가능하면) |
| FS-13 | `#162`에 새 커밋이 생김(처리량 측정 재개) | 이 브랜치를 `#162` 위로 다시 rebase. 두 토픽 문서를 이 브랜치에서 다시 썼으므로 `#162`의 같은 파일 변경은 충돌한다 | 진행 기록에 rebase 기준 커밋 기록 |
| FS-15 | 태그를 옮긴 뒤 CI 통합 테스트 shard에 새 클래스가 들어감 | `ci-integration-shard.py`는 컴파일된 모든 테스트 클래스를 shard에 배정하므로 추가 설정 없이 실행된다 | `--rerun-tasks`로 3개 서비스 `integrationTest`, shard 스크립트 출력에 9개 클래스 포함 확인 |
| FS-14 | 메모리(`~/.claude/.../memory`)가 옛 경로(`writing-style.md` 목소리 섹션, `ship-ready.md`, workflow-ship B1)를 가리킴 | ship에서 메모리를 새 경로로 고치거나 이식 완료 표시 | ship 체크 |

## 검증 기준

| 요구사항 | 확인 방법 |
|---|---|
| R1 지침 정본 | `CLAUDE.md` 내용이 `@AGENTS.md` 한 줄(+주석). AGENTS.md 절 구성이 kit과 같다 (diff 확인) |
| R2 프로필 | stack.md 버전 표가 `build.gradle`·wrapper와 일치. hook이 stack.md만 읽는다(`.agents/hooks/`에 모듈 이름이 없음: `grep`) |
| R3 workflow | kit 스킬과 diff 시 프로젝트 고유 줄만 다르다. `docs/*-PLAN.md`·`_shared/checklists`가 없다 |
| R4 역할 | `sync-agent-configs.py --check` 통과, 생성 파일 3+3개, risk-reviewer `model: "opus"` |
| R5 hook | `bash scripts/test-hooks.sh` 통과(FS-1~FS-10), 실제 세션 trace.log(판정 2) |
| R6 스킬 | `check-agent-docs.py --strict` frontmatter·참조 통과. `issue-commit-pr`, `explain-diff-html` 없음 |
| R7 STATE·TODOS | STATE 토픽 항목마다 6줄(제목+5). `docs/context/TODOS.md` 없음 |
| R8 context | 문서 식별자 대조 0건, 이력 줄 0건(`grep -l "최종 갱신" docs/context` 결과 없음), README 목록 = 실제 파일 |
| R9 smoke·quality | 문서 식별자 대조 0건. RUNBOOK 명령을 바꿨으면 D20 보고 |
| R10 archive | README 목차 = 실제 폴더(스크립트로 대조), archive 안 파일 diff 없음(`git diff --stat main -- docs/archive` 중 README만) |
| R11 테스트 분류 | 컨테이너 사용 + 태그 없음 0건(`grep`), Docker를 끈 상태 6개 모듈 `test` 통과, Docker를 켠 상태 payment·pg·product `integrationTest` 통과 |

## 사전 점검 (risk-reviewer)

해당 없음. 핵심 경로(서비스 코드)를 바꾸지 않는다. 문서 대조 정확성은 ship에서 reviewer가 본다.

## 태스크

합의 브리핑 확정 뒤 plan 단계에서 채운다. 아래는 순서 초안이다.

0단계 (테스트 분류): T0 컨테이너 테스트 9개 태그 이동
1단계 (지침 구조): T1 프로필 → T2 hook·hook 테스트 → T3 역할 → T4 conventions·templates → T5 workflow·review·explain 스킬 → T6 전용 스킬 → T7 AGENTS·CLAUDE·README·운영 문서 이동 → T8 문서 검사 스크립트 → T9 STATE·TODOS·archive README → T10 진행 중 두 토픽 재작성
2단계 (문서): T11 context 통합(ARCHITECTURE·PITFALLS·TESTING·STACK·guides) → T12 나머지 context 대조(PAYMENT-FLOW·CONFIRM-FLOW·INTEGRATIONS·conventions) → T13 smoke → T14 quality(RUNBOOK 포함, D20)
판정: T15

## 리뷰 처리

## 진행 기록

- 2026-11-06: 합의 브리핑 확정(질문 7 = 테스트 9개 태그 이동을 범위에 추가, 불변식 초안·에이전트 제안 그대로). 이슈 #163 생성.
- 2026-11-06: 브랜치를 `#162`(`483fd9e2`) 위에서 시작. 사전 브리핑 승인. 질문 1~6 답변. kit 기준 커밋 `7273cfd`.
