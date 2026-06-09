# review-critic-1

**Topic**: CI-PIPELINE-REDESIGN
**Round**: 1
**Persona**: Critic

## Reasoning
재사용 워크플로우 구조·6서비스 fan-out·inputs/with·모듈명·lint gate 보존·`-x integrationTest` 강제·테스트 품질은 대체로 건전하다. 그러나 취합 코멘트 파이프라인에 두 개의 독립적 critical 결함이 있다: (1) 커버리지 아티팩트 업로드 경로 가정이 upload-artifact v4+ 의 단일 파일 LCA-flatten 동작과 어긋나 report-comment.js 가 읽는 중첩 경로와 불일치하고, (2) report-comment.js 의 LINE 카운터 정규식이 JaCoCo XML 의 첫 카운터(클래스/패키지 단위)를 잡아 모듈 총계가 아닌 값을 보고한다. 어느 하나만으로도 PR 커버리지 표가 항상 깨진다. 추가로 단위 테스트 JUnit Check 리포트와 JaCoCo HTML 아티팩트가 현행 대비 소실되는 회귀(major)가 있다.

## Checklist judgement
- test gate / 전체 test 통과: **yes** — `:user-service:test`(+integrationTest+coverageVerification) UP-TO-DATE BUILD SUCCESSFUL. 결정론적 백본 건전.
- 신규 business logic 테스트 커버리지: **yes** — UserQueryUseCaseTest 가 성공/예외 양 경로 커버. tautology 아님.
- convention(var 금지 등): **yes** — Groovy `exceptionFormat = 'full'` 정정, testcontainers override Java 동형, var 미사용.
- 현행 동작 보존(lint gate / reviewdog / 트리거 / permissions / secret): **partial/no** — lint gate·reviewdog reporter/level/filter-mode·permissions·트리거 보존됨. 그러나 단위 JUnit Check 리포트와 JaCoCo HTML 아티팩트 소실(회귀).
- 재사용 워크플로우 정합(inputs/with/모듈명): **yes** — settings.gradle 6모듈 일치, has-integration 매핑 정확(gateway/eureka integrationTest 0건).
- 아티팩트 경로 정합: **no** — coverage 업로드/다운로드/JS 읽기 경로 불일치(critical).
- 취합 코멘트 데이터 정확성: **no** — JaCoCo LINE 정규식 첫-매치 결함(critical).

## Findings

### F1 (critical) — coverage 아티팩트 경로 불일치로 커버리지 표 전건 N/A
- checklist_item: 현행 동작 보존 / 아티팩트 이름·경로 정합
- location: `.github/workflows/_service-ci.yml:104-110`, `.github/scripts/report-comment.js:65-71`, `.github/workflows/ci.yml:73-113`
- problem: 업로드 step 은 단일 파일 `${{ github.workspace }}/<svc>/build/reports/jacoco/test/jacocoTestReport.xml` 를 올린다. actions/upload-artifact v4+ 는 제공 경로들의 최소공통조상(LCA)을 아티팩트 루트로 삼는다. 단일 파일이면 LCA = 그 파일의 부모 디렉토리이므로 아티팩트 내부에는 `jacocoTestReport.xml` 만 평탄하게 저장된다. download 후 실제 경로는 `artifacts/coverage-<svc>/jacocoTestReport.xml`. 그러나 report-comment.js 는 `artifacts/coverage-<svc>/<svc>/build/reports/jacoco/test/jacocoTestReport.xml`(중첩) 를 읽는다 → readFileSync 실패 → parseJacocoCoverage 가 catch 로 null → 모든 서비스 pct=N/A. 커버리지 표가 항상 깨진다(D4 단일 통합 코멘트의 핵심 산출물 무력화).
- evidence: 같은 워크플로우의 lint 아티팩트(`_service-ci.yml:138-144`, 단일 파일 `/tmp/lint-artifacts/lint-<svc>.json`)는 JS 에서 `artifacts/lint-<svc>/lint-<svc>.json`(평탄)로 읽도록 작성돼 있어 단일 파일 평탄화 동작을 작성자 스스로 전제하고 있다. coverage 만 중첩 경로를 가정 — 내부 모순.
- suggestion: 업로드를 워크스페이스 루트 기준으로 디렉토리 구조 보존하도록 변경(`path: <svc>/build/reports/jacoco/test/jacocoTestReport.xml` 를 `working-directory` 없이 상대 경로로 주고 LCA 가 워크스페이스가 되게 하거나, 더미 sibling 으로 LCA 를 올리기), 또는 JS 읽기 경로를 평탄 경로 `artifacts/coverage-<svc>/jacocoTestReport.xml` 로 수정. lint 와 동일 규약(평탄)으로 통일 권장.

### F2 (critical) — report-comment.js LINE 정규식이 모듈 총계가 아닌 첫 카운터를 집계
- checklist_item: 취합 코멘트 데이터 정확성
- location: `.github/scripts/report-comment.js:53`
- problem: `xml.match(/type="LINE"\s+missed="(\d+)"\s+covered="(\d+)"/)` 는 String.match(비-global) 이라 문서 순서상 첫 `type="LINE"` 카운터만 잡는다. JaCoCo XML 은 `<package>`/`<class>`/`<sourcefile>` 별 카운터가 앞에 나오고 리포트 총계 카운터는 `<report>` 끝에 위치한다. 따라서 첫 매치는 모듈 총계가 아니라 임의의 첫 클래스/패키지 LINE 값이다.
- evidence: 실측 `product-service/build/reports/jacoco/test/jacocoTestReport.xml` — 첫 LINE 카운터 `missed="6" covered="0"`(0%), 리포트 총계 `missed="25" covered="21"`(≈45.7%, 문서상 45.65% 와 일치). 현 정규식은 product-service 를 0.00% 로 보고하게 된다(파일 내 LINE 카운터 25개 중 첫 1개만 사용).
- suggestion: 전체 매치를 누적하거나(global match 후 합산), JaCoCo `<report>` 직속 마지막 LINE 카운터(또는 마지막 매치)를 취하도록 변경. 안전하게는 모든 `type="LINE"` 카운터를 합산하지 말고 — 중첩 카운터는 중복 집계됨 — `<report>` 의 직계 자식 총계만 파싱(예: 마지막 LINE 카운터 사용 또는 XML 파서로 report-level counter 선택).

### F3 (major) — 단위 테스트 JUnit Check 리포트 소실 (현행 회귀)
- checklist_item: 현행 동작 보존
- location: `.github/workflows/_service-ci.yml:39-40` (build-test-lint), 대조 `git show main:.github/workflows/ci.yml` JUnit Test Report step
- problem: main ci.yml 은 `mikepenz/action-junit-report` 로 `**/build/test-results/test/**/*.xml`(단위) + integrationTest 결과를 GitHub Check 로 발행했다. 신규 _service-ci.yml 은 integration-test job 에만 JUnit 리포트가 있고 build-test-lint(단위) job 에는 action-junit-report step 이 없다. 결과적으로 단위 테스트 실패의 인라인 Check 가시성이 사라지고, gateway/eureka(통합 없음)는 JUnit Check 가 아예 없다.
- evidence: `_service-ci.yml` 내 action-junit-report 등장 1회(integration job line 184-190)뿐. build-test-lint job(line 21-156)에 JUnit 리포트 step 부재.
- suggestion: build-test-lint job 에 `if: always()` action-junit-report step 추가(`report_paths: <svc>/build/test-results/test/**/*.xml`), check_name 서비스별 분리.

### F4 (major) — JaCoCo HTML 리포트 아티팩트 소실 (현행 회귀)
- checklist_item: 현행 동작 보존
- location: `.github/workflows/_service-ci.yml:104-110`, 대조 main ci.yml `Upload JaCoCo HTML report`(retention 14)
- problem: main 은 6서비스 jacoco HTML 디렉토리를 `jacoco-report` 아티팩트로 14일 보존해 사람이 브라우징할 수 있었다. 신규 흐름은 XML 만 retention 1일로 업로드하고 HTML 업로드가 없다. 커버리지 상세 리포트 다운로드 수단 상실.
- evidence: _service-ci.yml upload step 은 `jacocoTestReport.xml` 단일 파일만, HTML 경로 미포함.
- suggestion: HTML 디렉토리 업로드 step 복원(서비스별 이름 `jacoco-html-<svc>`) 또는 사용자에게 HTML 아티팩트 폐기가 의도된 범위 축소인지 확인.

### F5 (minor) — user-service 커버리지 게이트 근거 코멘트 부정확
- checklist_item: build.gradle 변경 정합
- location: `user-service/build.gradle:11`
- problem: `0.97` 게이트 근거 코멘트가 "UserQueryUseCase 3/3라인 100%" 만 언급하나, jacoco 측정 대상에는 User 도메인 클래스도 포함된다(infrastructure/dto 등은 excludePattern 으로 제외되지만 domain/usecase 는 측정). 게이트 수치 자체는 통과하나 근거 설명이 측정 범위를 과소 기술.
- evidence: 측정 대상 비제외 클래스: `domain/User.java`, `application/usecase/UserQueryUseCase.java`. User 는 Lombok 전용이라 generated 라인 위주이나 코멘트는 이를 누락.
- suggestion: 코멘트에 측정 범위(usecase + domain) 명시. 기능 영향 없음.

## JSON
```json
{
  "stage": "review",
  "persona": "critic",
  "round": 1,
  "task_id": null,
  "decision": "fail",
  "reason_summary": "취합 코멘트 파이프라인에 독립적 critical 2건(coverage 아티팩트 경로 불일치, JaCoCo LINE 정규식 첫-매치)으로 PR 커버리지 표가 항상 깨진다. 단위 JUnit Check·JaCoCo HTML 아티팩트 소실 회귀(major) 동반.",
  "checklist": {
    "source": "_shared/checklists/code-ready.md",
    "items": [
      { "section": "test gate", "item": "전체 ./gradlew test 통과", "status": "yes", "evidence": ":user-service:test+integrationTest+jacocoTestCoverageVerification UP-TO-DATE BUILD SUCCESSFUL" },
      { "section": "test gate", "item": "신규 business logic 테스트 커버리지 존재", "status": "yes", "evidence": "UserQueryUseCaseTest 성공/예외 양 경로 (UserQueryUseCaseTest.java:34-66)" },
      { "section": "convention", "item": "var 키워드 금지 / Groovy 문법 정합", "status": "yes", "evidence": "exceptionFormat = 'full' 정정, testcontainers eachDependency 명시 타입 사용" },
      { "section": "현행 보존", "item": "lint gate / reviewdog reporter·level·filter-mode 보존", "status": "yes", "evidence": "_service-ci.yml:63-88,149-156 main 동일 로직" },
      { "section": "현행 보존", "item": "재사용 워크플로우 inputs/with/모듈명 정합", "status": "yes", "evidence": "ci.yml:19-59 settings.gradle 6모듈 일치, has-integration 매핑 정확" },
      { "section": "현행 보존", "item": "단위 JUnit Check 리포트 보존", "status": "no", "evidence": "_service-ci.yml build-test-lint job 에 action-junit-report step 부재 (line 21-156)" },
      { "section": "현행 보존", "item": "JaCoCo HTML 아티팩트 보존", "status": "no", "evidence": "_service-ci.yml:104-110 XML 단일 파일만 업로드" },
      { "section": "정합", "item": "coverage 아티팩트 업로드/다운로드/파싱 경로 일치", "status": "no", "evidence": "upload 단일파일 LCA-flatten → coverage-<svc>/jacocoTestReport.xml vs JS 읽기 coverage-<svc>/<svc>/build/.../...xml (report-comment.js:65-71)" },
      { "section": "정합", "item": "취합 코멘트 커버리지 수치 정확성", "status": "no", "evidence": "report-comment.js:53 첫 LINE 카운터 매치; product XML 실측 첫=covered0/missed6, 총계=covered21/missed25" }
    ],
    "total": 9,
    "passed": 5,
    "failed": 4,
    "not_applicable": 0
  },
  "scores": {
    "correctness": 0.55,
    "conventions": 0.90,
    "discipline": 0.85,
    "test-coverage": 0.80,
    "domain": 0.80,
    "mean": 0.78
  },
  "findings": [
    {
      "severity": "critical",
      "checklist_item": "coverage 아티팩트 업로드/다운로드/파싱 경로 일치",
      "location": ".github/workflows/_service-ci.yml:104-110, .github/scripts/report-comment.js:65-71",
      "problem": "upload-artifact v4+ 는 단일 파일 업로드 시 LCA(파일 부모)를 루트로 평탄화한다. coverage 아티팩트는 jacocoTestReport.xml 만 저장되어 download 후 artifacts/coverage-<svc>/jacocoTestReport.xml 이 되나, report-comment.js 는 artifacts/coverage-<svc>/<svc>/build/reports/jacoco/test/jacocoTestReport.xml 을 읽어 readFileSync 실패 → 전 서비스 커버리지 N/A.",
      "evidence": "동일 워크플로우 lint 아티팩트(단일파일)는 JS 에서 평탄 경로 lint-<svc>/lint-<svc>.json 으로 읽어 단일파일 평탄화를 전제 — coverage 만 중첩 가정으로 내부 모순.",
      "suggestion": "업로드를 워크스페이스 루트 기준 상대경로로 구조 보존하거나, JS 읽기 경로를 평탄 경로(coverage-<svc>/jacocoTestReport.xml)로 통일."
    },
    {
      "severity": "critical",
      "checklist_item": "취합 코멘트 커버리지 수치 정확성",
      "location": ".github/scripts/report-comment.js:53",
      "problem": "비-global String.match 가 JaCoCo XML 의 첫 type=LINE 카운터(클래스/패키지 단위)만 잡아 모듈 총계가 아닌 값을 보고. 경로가 맞아도 잘못된 수치 표시.",
      "evidence": "product-service/build/reports/jacoco/test/jacocoTestReport.xml 실측: 첫 LINE=missed6/covered0(0%), 리포트 총계=missed25/covered21(≈45.7%, 문서 45.65% 일치). 현 정규식은 0.00% 보고.",
      "suggestion": "report-level 총계 카운터(예: 마지막 LINE 매치 또는 XML 파서로 <report> 직속 counter)만 파싱하도록 수정."
    },
    {
      "severity": "major",
      "checklist_item": "단위 JUnit Check 리포트 보존",
      "location": ".github/workflows/_service-ci.yml:39-40 (build-test-lint job)",
      "problem": "main ci.yml 의 단위 test JUnit Check(**/build/test-results/test/**/*.xml) 발행이 신규 흐름의 build-test-lint job 에 없다. integration job 에만 JUnit 리포트 존재 → 단위 실패 가시성 상실, gateway/eureka 는 JUnit Check 전무.",
      "evidence": "_service-ci.yml 내 action-junit-report 1회(line 184-190, integration job)뿐. build-test-lint job(21-156)에 부재.",
      "suggestion": "build-test-lint job 에 if: always() action-junit-report step 추가 (report_paths: <svc>/build/test-results/test/**/*.xml)."
    },
    {
      "severity": "major",
      "checklist_item": "JaCoCo HTML 아티팩트 보존",
      "location": ".github/workflows/_service-ci.yml:104-110",
      "problem": "main 의 jacoco HTML 리포트 아티팩트(retention 14) 소실. 신규는 XML 단일 파일(retention 1)만 업로드 — 사람이 브라우징할 커버리지 상세 리포트 다운로드 수단 상실.",
      "evidence": "_service-ci.yml upload step 은 jacocoTestReport.xml 단일 파일만 포함, HTML 경로 없음.",
      "suggestion": "HTML 디렉토리 업로드 step 복원(jacoco-html-<svc>) 또는 폐기가 의도된 범위인지 사용자 확인."
    },
    {
      "severity": "minor",
      "checklist_item": "build.gradle 변경 정합",
      "location": "user-service/build.gradle:11",
      "problem": "0.97 게이트 근거 코멘트가 UserQueryUseCase 만 언급하나 측정 대상에 domain/User 도 포함. 게이트 수치는 통과하나 근거 기술이 측정 범위 과소.",
      "evidence": "비제외 측정 클래스: domain/User.java, application/usecase/UserQueryUseCase.java.",
      "suggestion": "코멘트에 측정 범위(usecase + domain) 명시. 기능 영향 없음."
    }
  ],
  "previous_round_ref": null,
  "delta": null,
  "unstuck_suggestion": null
}
```
