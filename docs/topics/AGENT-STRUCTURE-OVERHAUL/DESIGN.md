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

순서는 실제 참조 관계로 정했다. 지침 문서(T4~T7)는 T1의 프로필과 T3의 역할 이름을 참조하고, 문서 검사(T8)는 T1~T7의 새 경로를 기준으로 하며,
2단계 문서 정리(T11~T14)는 T8의 식별자 대조로 확인한다. implementer 후보는 쓰기 범위가 겹치지 않는 T12·T13·T14다.
1단계 중간(T2 이후)에는 옛 지침 파일과 새 파일이 잠시 함께 있다. 옛 파일 삭제는 각 태스크에서 대체가 끝난 직후 한다.

- [x] **T0 — 컨테이너를 띄우는 테스트 9개를 통합 테스트로 옮긴다**
  - 변경 위치: D25의 9개 테스트 클래스(클래스 선언에 `@Tag("integration")`)
  - 의존: 없음
  - 완료 기준: 컨테이너 사용 + 태그 없음 0건. Docker를 끈 상태에서 `test`가 컨테이너 없이 통과(R11). 태그를 붙인 클래스가 `integrationTest`에서 실행된다(FS-15)
  - 검증: `grep` 대조, `./gradlew :payment-service:test :pg-service:test :product-service:test`(Docker 끈 상태), 정적 분석 3개 모듈. Docker가 켜지면 `:payment-service:integrationTest :pg-service:integrationTest :product-service:integrationTest --rerun-tasks`
  - tdd: false (테스트 분류만 바꾼다) | risk: false | 담당: 메인
  - 결과: 9개 클래스에 `@Tag("integration")`와 import 추가(스크립트로 넣어 편집 hook 대신 정적 분석을 직접 실행). 컨테이너 사용 + 태그 없음 0건. Docker를 못 찾게 한 상태(`DOCKER_HOST`를 없는 소켓으로)에서 `:payment-service:test :pg-service:test :product-service:test --rerun-tasks` 통과(698·442·70개, 옮긴 클래스 0개 포함). `checkstyleTest`·`spotbugsTest` 3개 모듈 통과. Docker를 켠 상태에서 `integrationTest --rerun-tasks` 통과(761·84·12개, 옮긴 클래스 결과 파일 6·2·1개). 세 서비스 모두 CI `has-integration: true`
- [x] **T1 — `stack.md`와 `risk.md` 프로필을 만든다**
  - 변경 위치: `.agents/profiles/stack.md`, `.agents/profiles/risk.md`
  - 의존: T0 (stack.md 코드 규칙에 D26 테스트 분류 규칙을 넣는다)
  - 완료 기준: 버전 표가 `build.gradle`·wrapper와 같다(Java 21, Spring Boot 3.4.4, Gradle 8.14.4 등). 모듈 표 6개와 커밋 scope. `verify-config`는 D4. risk.md 불변식 INV-1~7, 핵심 경로 glob은 실제 패키지로 확인한 값(R2, D10)
  - 검증: 버전·경로 대조(`ls`, `grep`)
  - tdd: false | risk: false | 담당: 메인
  - 결과: `.agents/profiles/stack.md`, `risk.md` 작성. 버전은 `build.gradle`(Spring Boot 3.4.4, Checkstyle 10.17.0, ArchUnit 1.3.0, Java 21)·wrapper(8.14.4)·서비스 `springCloudVersion`(2024.0.0)과 대조. 핵심 경로 glob이 가리키는 디렉토리 존재 확인. 위험 범주 메모는 근거가 확인된 것만 점검 질문 형태로 적음
- [x] **T2 — hook을 `.agents/hooks/`로 바꾸고 hook 테스트를 다시 쓴다**
  - 변경 위치: `.agents/hooks/{lib/common.sh,verify-on-stop.sh,lint-file.sh,verify-pause.sh}`(kit), `config/checkstyle/check-file.sh`(D5), `.claude/settings.json`(D6), `scripts/test-hooks.sh`, `build.gradle:59` 주석, `.github/workflows/ci.yml` 경로(D24). 삭제 `.claude/hooks/`
  - 의존: T1
  - 완료 기준: FS-1~FS-10을 test-hooks 케이스로 확인. 테스트 케이스를 먼저 쓰고 옛 hook 경로로 실패하는 것을 본 뒤 새 hook을 넣는다(R5)
  - 검증: `bash scripts/test-hooks.sh`, `bash -n` 각 스크립트
  - tdd: true | risk: false | 담당: 메인
  - 결과: kit hook 4개·`check-file.sh`(+`-p` 속성 파일)·`settings.json` 복사, `.claude/hooks/` 삭제, `build.gradle` 주석 경로 수정. `scripts/test-hooks.sh`를 새로 써서 옛 구조에서 실패(새 hook 없음) 확인 후 34케이스 통과(FS-1~FS-10). 가짜 gradlew는 결과 XML을 1초 뒤에 쓴다(hook이 실행 시작 표식과 초 단위로 비교). CI 단계는 경로가 같아 변경 없음. `docs/context/STACK.md`의 옛 hook 설명은 T11에서 고친다
- [x] **T3 — 역할을 implementer·reviewer·risk-reviewer로 바꾼다**
  - 변경 위치: `.agents/roles.toml`, `.agents/roles/{implementer,reviewer,risk-reviewer}.md`(kit 본문), 삭제 `.agents/roles/domain-expert.md`·생성 파일 2개, 생성 `.claude/agents/*`, `.codex/agents/*`. `.agents/roles/README.md`는 T7에서 `.agents/README.md`로 합친다
  - 의존: T1 (risk.md 경로)
  - 완료 기준: 생성 파일 3+3개, risk-reviewer `opus`·Codex 모델 유지(R4, D8, D9, FS-11)
  - 검증: `python3 scripts/sync-agent-configs.py` 후 `--check`
  - tdd: false | risk: false | 담당: 메인
  - 결과: `roles.toml` 3역할, `.agents/roles/*.md`는 kit 본문. 첫 sync에서 옛 domain-expert 생성 파일 2개를 "정본에 없는 생성 파일"로 보고(FS-11) → 삭제 후 `--check` 통과(6개). risk-reviewer Claude `opus`, Codex `gpt-6-astra`. `sync-agent-configs.py`는 변경 불필요
- [x] **T4 — conventions와 templates를 kit에서 가져온다**
  - 변경 위치: `.agents/conventions/{git,testing,writing}.md`, `.agents/templates/{BRIEF,DESIGN,EXPERIMENTS}.md`. 삭제 `.agents/skills/_shared/`(writing 계열은 T6에서 옮긴 뒤 삭제)
  - 의존: T1
  - 완료 기준: git.md에 이 프로젝트 scope·Codex 작성자 표기(D11), writing.md 문장 절에 상세판 링크(D12). kit과 diff하면 프로젝트 고유 줄만 다르다
  - 검증: `diff -r` kit 대조
  - tdd: false | risk: false | 담당: 메인
  - 결과: kit `conventions/` 3개·`templates/` 3개 복사. kit과 다른 줄은 git.md scope 목록·Codex 작성자 표기, writing.md 문장 절의 상세판 링크(D12) 3곳뿐(`diff` 확인). 템플릿은 kit과 동일. `_shared/checklists/` 4개와 `commit.md`·`github.md` 삭제. writing 계열 4개는 T6에서 옮긴 뒤 삭제
- [x] **T5 — workflow·review·explain-change-html 스킬을 kit 버전으로 바꾼다**
  - 변경 위치: `.agents/skills/{workflow,workflow-discuss,workflow-plan,workflow-execute,workflow-ship,review,explain-change-html}/`. 삭제 `issue-commit-pr/`, `explain-diff-html/`
  - 의존: T3, T4
  - 완료 기준: kit과 diff하면 프로젝트 고유 줄(Codex·멀티모듈·context-update 연결)만 다르다(R3, D14)
  - 검증: `diff -r` kit 대조
  - tdd: false | risk: false | 담당: 메인
  - 결과: kit 스킬 7개 복사, `issue-commit-pr`·`explain-diff-html` 삭제. kit과 다른 줄은 ship 문서 동기화의 context-update 연결 한 줄뿐(`diff -r`). explain은 kit 방식(퀴즈 없음, 결과물을 토픽 폴더나 `docs/explanations/`에 둠)을 따른다
- [x] **T6 — 전용 스킬 6개를 새 구조에 맞춘다**
  - 변경 위치: `.agents/skills/{payment-live-drill,wiki-access,portfolio-access,writing,doc-review,context-update}/`, 신규 `writing/references/style-guide.md`(D12, D13), 삭제 `_shared/conventions/writing*.md`
  - 의존: T4, T5
  - 완료 기준: 옛 경로(`_shared`, `domain-expert`, `issue-commit-pr`, `PLAN`) 참조 0건. style-guide에 원문 1~7절과 D13 항목. doc-review가 7절 표를 검수 목록으로 쓴다. context-update는 ship 문서 동기화 절차(D15)
  - 검증: `grep -rn` 옛 경로
  - tdd: false | risk: false | 담당: 메인
  - 결과: `writing/references/style-guide.md` = 원문 1~7절 + 8절(D13 항목, 동의어 표에 메모리의 "재전송" 규칙 추가). 원문과 충돌하는 옛 규칙("단문 위주", "명사형 종결")은 넣지 않고 "기존 문서의 종결 형식을 따른다"로 대체. writing·doc-review·context-update 재작성(context-update는 문서 목록을 context README에 위임). payment-live-drill의 PLAN·옛 TODOS 경로 수정. `_shared/` 디렉토리 삭제. 옛 경로 참조 0건(`grep`)
- [x] **T7 — `AGENTS.md`를 다시 쓰고 운영 문서를 옮긴다**
  - 변경 위치: `AGENTS.md`, 신규 `CLAUDE.md`, `.agents/README.md`(옛 `.agents/skills/README.md`, `.agents/roles/README.md`, `docs/context/CODEX.md` 통합), `.agents/references/claude-5-prompting.md`(이동)
  - 의존: T1~T6
  - 완료 기준: AGENTS.md 절 구성이 kit과 같고 "참고 문서" 표가 새 경로를 가리킨다. Code Review Rules는 risk.md로 옮겨졌다(R1, D21)
  - 검증: kit과 diff, 링크 확인(T8 이후 strict로 재확인)
  - tdd: false | risk: false | 담당: 메인
  - 결과: kit AGENTS.md에 프로젝트 소개, 역할 정본 위치(`.agents/roles/`)와 생성 규칙, 지침 검사 명령, Codex는 hook 없음, "코드 리뷰" 절(옛 Code Review Rules 요지 + risk.md), 참고 문서 5행을 더함(kit 대비 28줄 차이). `CLAUDE.md`는 kit과 동일. `.agents/README.md`가 옛 skills README·roles README·`docs/context/CODEX.md`를 대체. 프롬프팅 조사 기록은 `.agents/references/`로 이동. `docs/context/STACK.md:208`의 옛 roles README 참조는 T11에서 고친다
- [x] **T8 — 문서 검사 스크립트를 새 구조로 바꾸고 식별자 대조를 더한다**
  - 변경 위치: `scripts/check-agent-docs.py`, 신규 테스트 `scripts/test_check_agent_docs.py`
  - 의존: T7
  - 완료 기준: 체크리스트·중복 규칙 검사 삭제, 식별자 대조(D17) 추가. 경로·클래스·설정 키 각각 존재/부재 픽스처 테스트. 새 구조에서 `--strict` 통과(R6)
  - 검증: `python3 -m unittest scripts/test_check_agent_docs.py`, `python3 scripts/check-agent-docs.py --strict`
  - tdd: true | risk: false | 담당: 메인
  - 결과: 판정을 6종으로 재구성(체크리스트·중복 규칙 삭제, 식별자 대조 신설). 검사 범위에 guides·smoke·quality·STATE·TODOS·archive README 추가, `.agents/templates/`는 참조 검사 제외(토픽 폴더 기준 링크). 식별자 대조는 클래스(Java·Gradle·YAML·XML·JSON에 나오는 단어)와 설정 키(펼친 YAML 키, 상위 키, 하이픈 형태 포함). 우리 코드가 안 쓰는 외부 타입 8개·설정 키 1개는 명시 목록으로 허용. `scripts/test_check_agent_docs.py` 15개(오탐 세 종류는 테스트로 실패 확인 후 수정). 첫 테스트 실행은 새 함수가 없어 단언이 아닌 속성 오류로 실패했다. CI 지침 검사 job에 테스트 단계 추가(D24 확장). 현재 `--strict` 결과: 참조 18건·식별자 37건 남음(T9·T11~T14 대상, 대부분 삭제된 `StockOutbox*` 계열)
- [ ] **T9 — STATE·TODOS·archive README를 새 형식으로 바꾼다**
  - 변경 위치: `docs/STATE.md`, 신규 `docs/TODOS.md`, 삭제 `docs/context/TODOS.md`, `docs/archive/README.md`
  - 의존: T7
  - 완료 기준: STATE 토픽 항목은 제목+5줄, 별건 메모는 TODOS로. TODOS 항목은 코드로 해결을 확인한 것만 지우고 근거를 진행 기록에. archive README 목차 = 실제 폴더, 옛 형식 안내(R7, R10, D18)
  - 검증: 폴더 대조 스크립트, `git diff --stat main -- docs/archive`
  - tdd: false | risk: false | 담당: 메인
- [ ] **T10 — 진행 중인 두 토픽 문서를 새 형식으로 다시 쓴다**
  - 변경 위치: `docs/topics/PAYMENT-THROUGHPUT/{BRIEF,DESIGN,EXPERIMENTS}.md`(옛 `topics/PAYMENT-THROUGHPUT.md`, `PAYMENT-THROUGHPUT-PLAN.md`, `reports/tps-*`), `docs/topics/CHECKOUT-RECEIPT-TRACKING/BRIEF.md`(옛 `topics/CHECKOUT-RECEIPT-TRACKING.md`, `reports/checkout-receipt-*`)
  - 의존: T9
  - 완료 기준: 측정 결과·결정·남은 일이 빠짐없이 옮겨졌다(옛 문서의 수치·결정 목록과 대조). 재개 지점(T2.4 — 100/s 시험)이 새 문서와 STATE에서 같다(D19)
  - 검증: 옛·새 문서 수치 대조, 링크 확인
  - tdd: false | risk: false | 담당: 메인
- [ ] **T11 — 겹치는 context 문서를 합친다**
  - 변경 위치: `docs/context/{ARCHITECTURE,STRUCTURE,PITFALLS,CONCERNS,TESTING,STACK,CONVENTIONS}.md`, `conventions/testing.md`, `stack/flyway-operations.md`, 신규 `docs/context/README.md`, `docs/guides/payment-flow.md`(이동)
  - 의존: T8 (식별자 대조), T9 (CONCERNS 미해결 항목 → TODOS)
  - 완료 기준: BRIEF 질문 1의 A안 표대로 구성. 이력 줄 0건. 식별자 대조 0건. 합친 문서의 사실은 코드로 확인(R8, D21, D22, D26)
  - 검증: `check-agent-docs.py --strict`, `grep "최종 갱신"`
  - tdd: false | risk: false | 담당: 메인
- [ ] **T12 — 나머지 context 문서를 코드와 대조해 고친다**
  - 변경 위치: `docs/context/{PAYMENT-FLOW,CONFIRM-FLOW,INTEGRATIONS}.md`, `docs/context/conventions/{code-style,error-logging,kafka,transactions}.md`
  - 의존: T11
  - 완료 기준: 이력 줄 0건, 식별자 대조 0건, 코드와 다른 서술은 고치고 목록을 결과에 남긴다(R8)
  - 검증: `check-agent-docs.py --strict`
  - tdd: false | risk: false | 담당: implementer
- [ ] **T13 — smoke 가이드 5개를 대조해 고친다**
  - 변경 위치: `docs/smoke/*.md`
  - 의존: T8
  - 완료 기준: 명령·경로·지표 이름이 `scripts/smoke/`, `observability/` 설정과 같다. 식별자 대조 0건(R9)
  - 검증: `check-agent-docs.py --strict`, 명령의 스크립트 존재·인자 대조
  - tdd: false | risk: false | 담당: implementer
- [ ] **T14 — quality 문서(RUNBOOK 포함)를 대조해 고친다**
  - 변경 위치: `docs/quality/{README,RUNBOOK}.md`
  - 의존: T8, T10 (처리량 토픽 EXPERIMENTS의 고정 조건 위치)
  - 완료 기준: 명령·옵션이 `scripts/quality/`, `scripts/k6/`와 같다. 바뀐 명령·조건은 D20 형식으로 처리량 EXPERIMENTS와 결과에 남긴다(R9)
  - 검증: `check-agent-docs.py --strict`, 명령 `--help` 대조
  - tdd: false | risk: false | 담당: implementer
- [ ] **T15 — 판정 시나리오를 실행한다**
  - 변경 위치: 없음(결과는 BRIEF 완료 요약)
  - 의존: T0~T14
  - 완료 기준: 판정 1~4와 R11 확인. 실제 세션 trace.log, 새 세션 행동 확인(작은 수정·토픽 요청), Codex 가능 여부
  - 검증: BRIEF 판정 목록
  - tdd: false | risk: false | 담당: 메인

## 리뷰 처리

## 진행 기록

- 2026-11-06: T1 커밋(`7a2053fa`)에 미리 `git rm`으로 스테이징해 둔 삭제 5건(옛 hook 2개, domain-expert 파일 3개)이 함께 들어갔다. 커밋 전에 staged diff를 확인하지 않은 실수다. 내용은 T2·T3 결과와 같고, 커밋 이력만 섞였다. 이후 커밋은 `git diff --cached --stat`을 먼저 확인한다.
- 2026-11-06: 합의 브리핑 확정(질문 7 = 테스트 9개 태그 이동을 범위에 추가, 불변식 초안·에이전트 제안 그대로). 이슈 #163 생성.
- 2026-11-06: 브랜치를 `#162`(`483fd9e2`) 위에서 시작. 사전 브리핑 승인. 질문 1~6 답변. kit 기준 커밋 `7273cfd`.
