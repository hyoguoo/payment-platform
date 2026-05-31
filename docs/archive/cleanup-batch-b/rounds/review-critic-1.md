# review-critic-1

**Topic**: CLEANUP-BATCH-B
**Round**: 1
**Persona**: Critic

## Reasoning
결정론적 백본(`:payment-service:test` + 4서비스 `spotbugsTest`/`checkstyleTest`/`jacocoTestCoverageVerification`) 전부 GREEN 확인. NET-RETRY(D-NR1a/b/c), spotbugs 코드 정정(D-SB1/D-SB1-EI, 억제 0건), jacoco 공통화/임계(D-COV1/2/3) 모두 설계 결정과 정합한다. critical 결함 없음. 단 C-2 커밋이 범위 밖 사전 부채 4건을 `build:` 커밋에 혼입한 점(CLAUDE.md Rule 2 위반, 커밋 응집도)을 major 로 본다 — 정정 자체는 전부 올바르나 분리 커밋/주석·TODOS 경로를 거치지 않았다. element CLASS→BUNDLE 게이트 약화·user 0.0 무게이트는 설계가 명시 수용한 baseline 철학 범위라 minor.

## Checklist judgement

### task execution
- RED/GREEN 커밋 존재: **yes** — B-1 `test(payment): ...502/504...(RED)`(b4cd99b4) → B-2 `feat(payment): ...502/504...(GREEN)`(af4b156a). A-1/A-2 tdd=false 라 RED 불요.
- 커밋 메시지 포맷: **yes** — `feat/test/fix/build/docs` 준수. scope 고정 어휘(`payment`/`build`/`docs`).
- STATE.md active task 갱신 / stage→review: **yes** — C-2 커밋 메시지에 "stage=review 전환" 명시(디테일은 STATE.md diff, 오케스트레이터 영역과 경계).

### test gate (결정론적 백본)
- 전체 test 통과: **yes** — `:payment-service:test :payment-service:spotbugsTest` BUILD SUCCESSFUL(실행 확인). pg/product `spotbugsTest`+`checkstyleTest` GREEN.
- 신규/수정 business logic 테스트: **yes** — ErrorDecoder 502/504 케이스 4건 추가(ProductFeignConfigTest/UserFeignConfigTest), 500 회귀 케이스 유지.
- state machine 전이: **n/a** — 본 토픽은 도메인 상태 전이 없음(§3).

### convention
- null 반환 금지/Optional: **n/a (해당 변경 없음)** — FakeMessagePublisher 는 Supplier 전환, port 계약 불변.
- catch(Exception): **n/a** — 신규 도입 없음.
- 신규 로깅 LogFmt: **yes** — ErrorDecoder `LogFmt.warn(... status=)` 기존 패턴 유지.

### execution discipline
- 범위 밖 코드 수정 없음: **no** — C-2 커밋(54db1941)이 pg/product/payment 사전 부채 4건을 함께 수정(F1, major). 정정은 올바르나 범위·커밋 응집 이탈.

### domain risk (Domain Expert 전용)
- 본 Critic 판정 범위 외 — Domain Expert 가 별도 판정.

## Findings

### F1 (major) — C-2 커밋에 범위 밖 사전 부채 4건 혼입
- checklist_item: 범위 밖 코드 수정 없음 (발견한 이슈는 주석 또는 docs/context/TODOS.md)
- location: 커밋 54db1941 — `pg-service/.../PgOutboxImmediateWorkerMdcPropagationTest.java`, `pg-service/.../mock/FakePgEventPublisher.java`, `product-service/.../mock/FakeEventDedupeStoreTest.java`, `payment-service/.../integration/StockCompensationRecoveryIntegrationTest.java`
- problem: in-scope(§2)는 payment-service spotbugs 5건 + NET-RETRY + jacoco 공통화였다. 4서비스 jacoco 게이트 실효화가 build GREEN 을 4서비스로 확대하면서 pg/product 사전 부채(NP_NULL/EI_EXPOSE_REP/DLS/UnusedImports)가 노출됐고, 이를 별도 커밋/주석/TODOS 경유 없이 `build: C-2` 단일 커밋에 흡수했다. CLAUDE.md Rule 2(범위 밖 수정 금지) 위반이며 커밋 응집도 저하.
- evidence: `git show --stat 54db1941` 가 jacoco build.gradle 변경과 4개 테스트 파일 변경을 한 커밋에 포함. PLAN.md C-2 완료 결과에 "자동 수정 (범위 밖 사전 부채, build GREEN 완료 기준 포함)"으로 자인.
- 보강 판정(주목 1 명시): 4건 전부 **코드 정정**(null if-throw 가드 / compact constructor + accessor defensive copy / dead local 제거 / unused import 제거)이며 **억제(exclude xml / @SuppressFBWarnings) 0건** — D-SB1 정신과 정합. 빌드 정확성·게이트 정직성에는 결함 없음. 불가피성은 인정되나(4서비스 공통 게이트의 구조적 귀결) 분리 커밋(예: 선행 `fix:` 또는 별도 사전 부채 커밋)이 적절했다.
- suggestion: 향후 동종 작업은 사전 부채를 선행 `fix:` 커밋으로 분리하거나 TODOS 등재 후 처리. 본 건은 사후 정정 부담이 크므로 수용하되 verify 단계에서 사전 부채 등재 여부 확인 권장.

### F2 (minor) — jacocoTestCoverageVerification element CLASS→BUNDLE 변경 (설계 외, 게이트 약화)
- checklist_item: 신규/수정 business logic 테스트 커버리지 존재 (게이트 실효성)
- location: `build.gradle` jacocoTestCoverageVerification `rule { element = 'BUNDLE' }`; PLAN.md C-2 완료 결과 "element = 'CLASS' → 'BUNDLE'"
- problem: Round 0 결정(topic.md §3-3 / §4 D-COV1)은 element 를 명시하지 않았고 기존 payment 블록은 CLASS 였다. BUNDLE 합산은 테스트 0% 신규 클래스가 번들 평균이 임계 위면 통과시켜 게이트를 약화한다 — D-COV1 "게이트 실효화" 의도와 일부 어긋난다.
- evidence: 기존 payment-service/build.gradle 제거 블록은 `element = 'CLASS'`; 신규 루트 블록은 `element = 'BUNDLE'`.
- suggestion: 변경 합리성(테스트 없는 관리 클래스 0%가 전체 차단하는 CLASS 문제 회피)은 타당하므로 수용. 다만 게이트가 "신규 미테스트 클래스"를 못 잡는다는 한계를 TODOS/CONCERNS 에 1줄 등재.

### F3 (minor) — user-service minimum 0.0 = 무게이트 (D-COV1 실효화 의도와 부분 상충)
- checklist_item: 게이트 실효성 (D-COV1)
- location: user-service/build.gradle (ext `jacoco.lineCoverageMinimum` 미설정 → 가드 default 0.0); PLAN.md C-2 표 user-service 0.00
- problem: user-service 게이트가 사실상 무력(minimum=0.0). product 0.40(실측 45.65%) 도 마진이 넓다.
- evidence: `jacocoTestCoverageVerification` 가드가 ext 부재 시 `BigDecimal.ZERO` 적용; user-service 에 ext 미설정 확인.
- 보강 판정(주목 3 명시): topic.md §3-3 baseline 절·§2 non-goal 이 "gateway/eureka 0% 허용 / 서비스별 baseline 편차 수용 / 측정 대상 확대 안 함(G1)"을 명시 수용했으므로 설계 위반은 아니다. baseline 동결 철학과 정합. 다만 user-service 게이트는 현재 의미 없음을 인지하고 후속 토픽(측정 대상 확대)으로 둘 것.
- suggestion: 후속 토픽에서 user-service 측정 대상 점검 + 임계 도입. 현재는 수용.

### F4 (minor) — TODOS.md 미갱신 (502/504 갭 stale + 설계 명시 후속 미등재)
- checklist_item: 발견한 이슈는 docs/context/TODOS.md 등재 (doc-sync)
- location: `docs/context/TODOS.md:125-127` (브랜치에서 미수정)
- problem: TODOS 125-127 이 502/504→500 갭을 "본 토픽으로 변경되지 않아 갭 잔존"으로 기술하나, 본 브랜치가 그 갭을 정정했다 → stale. 또 설계가 TODOS/Phase4 후속으로 약속한 D-NR1d(Retry-After/TTL 정렬), G1(커버리지 측정 대상 확대)이 미등재.
- evidence: `git diff main...HEAD --stat docs/context/TODOS.md` 비어 있음(미수정). topic.md §4 D-NR1d / "후속 여지" 및 §2 G1 이 TODOS 위임을 명시.
- suggestion: verify 단계에서 TODOS 125-127 을 "502/504 해소(CLEANUP-BATCH-B), 429/503 분리·자동재시도·Retry-After/TTL 정렬·커버리지 측정 확대 잔존"으로 갱신. doc-sync 라 verify 게이트에서 처리.

## JSON
```json
{
  "stage": "code",
  "persona": "critic",
  "round": 1,
  "task_id": null,
  "decision": "revise",
  "reason_summary": "결정론적 백본 전부 GREEN, NET-RETRY/spotbugs/jacoco 결정 정합·억제 0건. C-2 커밋이 범위 밖 사전 부채 4건을 혼입(Rule 2 위반·커밋 응집도)해 major 1건 — revise.",
  "checklist": {
    "source": "_shared/checklists/code-ready.md",
    "items": [
      { "section": "task execution", "item": "RED/GREEN 커밋 존재 + 포맷 준수", "status": "yes", "evidence": "b4cd99b4(RED test) → af4b156a(GREEN feat), A-1/A-2 tdd=false" },
      { "section": "test gate", "item": "전체 ./gradlew test 통과", "status": "yes", "evidence": ":payment-service:test :payment-service:spotbugsTest BUILD SUCCESSFUL 실행 확인" },
      { "section": "test gate", "item": "신규 business logic 테스트 커버리지", "status": "yes", "evidence": "ProductFeignConfigTest/UserFeignConfigTest 502/504 케이스 4건 추가 + 500 회귀 유지" },
      { "section": "test gate", "item": "state machine 전이 @ParameterizedTest", "status": "n/a", "evidence": "본 토픽 도메인 상태 전이 없음 (topic.md §3)" },
      { "section": "convention", "item": "신규 로깅 LogFmt 사용", "status": "yes", "evidence": "ErrorDecoder LogFmt.warn(... status=) 기존 패턴 유지" },
      { "section": "convention", "item": "catch(Exception)/null 반환 금지", "status": "n/a", "evidence": "해당 신규 도입 없음; FakeMessagePublisher Supplier 전환은 port 계약 불변" },
      { "section": "execution discipline", "item": "범위 밖 코드 수정 없음", "status": "no", "evidence": "커밋 54db1941 가 pg/product/payment 사전 부채 4건 혼입 (F1)" },
      { "section": "execution discipline", "item": "분석 마비 없음", "status": "yes", "evidence": "PLAN 완료 결과 각 태스크 실측·검증 기록 존재" },
      { "section": "final task only", "item": "STATE.md stage → review 전환", "status": "yes", "evidence": "C-2 커밋 메시지 'stage=review 전환' 명시 (STATE.md diff 세부는 오케스트레이터 경계)" }
    ],
    "total": 9,
    "passed": 6,
    "failed": 1,
    "not_applicable": 3
  },
  "scores": {
    "correctness": 0.93,
    "conventions": 0.85,
    "discipline": 0.6,
    "test_coverage": 0.82,
    "domain": 0.9,
    "mean": 0.82
  },
  "findings": [
    {
      "severity": "major",
      "checklist_item": "범위 밖 코드 수정 없음 (발견 이슈는 주석/TODOS)",
      "location": "commit 54db1941 — pg PgOutboxImmediateWorkerMdcPropagationTest.java / pg FakePgEventPublisher.java / product FakeEventDedupeStoreTest.java / payment StockCompensationRecoveryIntegrationTest.java",
      "problem": "in-scope(§2)는 payment spotbugs 5건+NET-RETRY+jacoco 공통화였으나, 4서비스 게이트 실효화가 build GREEN 을 확대하며 pg/product 사전 부채(NP_NULL/EI_EXPOSE_REP/DLS/UnusedImports)를 별도 커밋/주석/TODOS 경유 없이 build:C-2 단일 커밋에 흡수. CLAUDE.md Rule 2 위반 + 커밋 응집도 저하.",
      "evidence": "git show --stat 54db1941 가 jacoco build.gradle + 4 테스트 파일을 한 커밋에 포함. PLAN.md C-2 완료결과에 '자동 수정 (범위 밖 사전 부채)' 자인. 4건 전부 코드 정정이며 억제(exclude xml/@SuppressFBWarnings) 0건 — D-SB1 정합, 빌드 정확성 무결.",
      "suggestion": "사전 부채는 선행 fix: 커밋 분리 또는 TODOS 등재 후 처리. 본 건은 사후 정정 부담이 커 수용하되 verify 에서 등재·정정 성격 재확인."
    },
    {
      "severity": "minor",
      "checklist_item": "게이트 실효성 (D-COV1)",
      "location": "build.gradle jacocoTestCoverageVerification rule element='BUNDLE'; PLAN.md C-2 완료결과",
      "problem": "element CLASS→BUNDLE 는 Round 0 미결정(설계 외) 변경. BUNDLE 합산은 테스트 0% 신규 클래스를 번들 평균이 임계 위면 통과시켜 게이트를 약화 — D-COV1 실효화 의도와 부분 상충.",
      "evidence": "제거된 payment-service/build.gradle 블록 element='CLASS' vs 신규 루트 element='BUNDLE'. CLASS 가 미테스트 관리 클래스로 전체 차단하는 문제 회피라는 합리성은 인정.",
      "suggestion": "변경 수용. 단 'BUNDLE 은 신규 미테스트 클래스를 못 잡는다'는 한계를 CONCERNS/TODOS 1줄 등재."
    },
    {
      "severity": "minor",
      "checklist_item": "게이트 실효성 (D-COV1) — user 0.0 무게이트",
      "location": "user-service/build.gradle (ext 미설정 → 0.0); PLAN.md C-2 표 user 0.00 / product 0.40",
      "problem": "user-service minimum=0.0 으로 사실상 무게이트, product 0.40(실측 45.65%) 마진 넓음. D-COV1 실효화 의도와 부분 상충.",
      "evidence": "가드가 ext 부재 시 BigDecimal.ZERO; user-service ext 미설정 확인. 단 topic.md §3-3/§2 가 'baseline 편차 수용 + 0% 허용 + 측정 확대 안 함(G1)'을 명시 수용 — 설계 위반 아님.",
      "suggestion": "후속 토픽에서 user-service 측정 대상 점검 + 임계 도입. 현재는 수용."
    },
    {
      "severity": "minor",
      "checklist_item": "발견 이슈 TODOS 등재 (doc-sync)",
      "location": "docs/context/TODOS.md:125-127 (브랜치 미수정)",
      "problem": "TODOS 125-127 이 502/504→500 갭을 '갭 잔존'으로 기술하나 본 브랜치가 정정 → stale. 설계 약속 후속(D-NR1d Retry-After/TTL 정렬, G1 커버리지 측정 확대) 미등재.",
      "evidence": "git diff main...HEAD --stat docs/context/TODOS.md 비어 있음. topic.md §4 D-NR1d / §2 G1 이 TODOS 위임 명시.",
      "suggestion": "verify 단계에서 TODOS 125-127 갱신 + D-NR1d/G1 후속 등재."
    }
  ],
  "previous_round_ref": null,
  "delta": null,
  "unstuck_suggestion": null
}
```
