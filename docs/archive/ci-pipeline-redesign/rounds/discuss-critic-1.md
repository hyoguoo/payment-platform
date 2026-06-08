# discuss-critic-1

**Topic**: CI-PIPELINE-REDESIGN
**Round**: 1
**Persona**: Critic

## Reasoning
설계는 Gate checklist 의 scope/design-decisions/acceptance/verification/artifact 항목을 대부분 충족하며, 코드 사실(exceptionFormat 4곳, test-retry 부재, jacoco minimum payment 0.89/pg 0.91/product 0.40, check.dependsOn integrationTest, integrationTest task 3서비스 한정, UserQueryUseCase 미커버, gateway/eureka build.gradle 존재)은 직접 검증 결과 전부 일치한다. 내부 모순 없음(D8 user has-integration false→true 가 ci.yml line 157 에 정합 반영). 그러나 변경 범위 전수성에 누락이 있다: 현행 lint 신호 체계의 두 구성요소(`lint-summary.js` 단일 lint 요약 코멘트, `spotbugs-to-rdjsonl.py`)가 새 per-service + 단일 report job 구조에서 어떻게 재배치되는지 §3 스케치에 전혀 등장하지 않아, 단일 통합 코멘트가 커버리지만 다루고 lint 요약은 6서비스로 난립할지/사라질지 미정이다. critical 은 없어 이전 단계 복귀는 불필요하나, major 1건으로 revise.

## Checklist judgement

### scope
- TOPIC UPPER-KEBAB-CASE 확정: **yes** (line 1)
- 모듈/패키지 경계 명시: **yes** (§3 변경 범위 표 — 2 워크플로우 신규 + 4 build.gradle + 2 테스트 신규, 6서비스 전부 매핑)
- non-goals 1개 이상: **yes** (§ non-goals 7건)
- 범위 밖 이슈 TODOS 위임/포함: **yes** (헤더 CLEANUP-BATCH-B / FLYWAY-USER-SEED-GAP 인용, 본 토픽 흡수)
- 변경 전수성(현행 신호 구성요소 보존): **no** — 현행 lint-summary.js 단일 lint 요약 코멘트와 spotbugs-to-rdjsonl.py 가 새 구조에서 어디로 가는지 미명시

### design decisions
- hexagonal layer 배치: **n/a** (CI/빌드 인프라 토픽, 런타임 도메인 무변경 — § non-goals 명시). D7/D8 테스트 배치는 product 동형으로 결정됨.
- 포트 인터페이스 위치: **n/a** (동상)
- 새 상태 전이 다이어그램: **n/a** (상태 추가 없음)
- 전체 결제 흐름 호환성 검토: **yes** (V4: CI 인프라라 도메인 리스크 ≈0, § non-goals 재확인)
- D4 단일코멘트 vs jacoco 내장비교 양립성 plan 이연: **yes** (D4 검증필요 + §5 적신호, 양립 불가 시 단일코멘트 우선 명문화 — 적절한 이연)
- D2 build -x integrationTest 강제: **yes** (D2 주의 + §5 plan 확인 항목, check.dependsOn integrationTest 코드 확인됨)
- 액션 메이저 bump breaking 플래그: **yes** (§3 표 액션별 breaking 비고 + §4-1 PR 실증, checkout v4→v6 / setup-gradle v3→v6 / github-script v7→v9 각각 점검 항목 명시)
- 내부 모순 부재: **yes** (D8 has-integration 전환이 ci.yml line 157 user: true 와 정합)

### acceptance criteria
- 성공 조건 관찰 가능 형태: **yes** (6 독립 막대, coverage minimum 구체 수치 표, 단일 코멘트 렌더)
- 실패 관찰 수단: **yes** (lint gate, jacocoTestCoverageVerification, JUnit 리포트)

### verification plan
- 테스트 계층 결정: **yes** (§4: 단위+통합+jacoco verify+PR 자체 실행, act 미채택 사유 명시)
- 벤치마크 지표: **n/a** (k6/벤치는 별도 토픽으로 명시 기각)

### artifact
- 결정 사항 섹션 존재: **yes** (§2 D1~D8)

## Findings

- **id**: F1
  **severity**: major
  **checklist_item**: 이 변경이 건드리는 모듈/패키지 경계가 명시됨 (scope)
  **location**: docs/topics/CI-PIPELINE-REDESIGN.md §3 (ci.yml report job, lines 161-168; _service-ci.yml sketch lines 122-143) / .github/scripts/lint-summary.js, .github/scripts/spotbugs-to-rdjsonl.py
  **problem**: 현행 파이프라인의 lint 신호는 reviewdog 인라인 외에 (1) `actions/github-script` + `lint-summary.js` 단일 lint 요약 PR 코멘트, (2) `spotbugs-to-rdjsonl.py` 변환 스크립트 두 구성요소를 갖는다. 재설계 §3 스케치에서 단일 통합 report job 은 "6서비스 JaCoCo 표"만 조립하고 lint 요약 코멘트의 행방을 다루지 않으며, _service-ci.yml 의 reviewdog step 도 spotbugs 변환/요약을 어떻게 per-service 로 옮길지 미명시다. 결과적으로 lint-summary 가 (a) 6서비스로 난립하거나(O4 단일코멘트 원칙 위반), (b) 조용히 제거되거나(현행 신호 손실), (c) JaCoCo report job 으로 흡수되는지 — 세 갈래가 plan 에서 결정 없이 열려 있어 변경 전수성이 깨진다. D4 적신호가 jacoco-report 코멘트 난립만 다루고 lint-summary 코멘트는 동일 위험인데 누락됐다.
  **evidence**: 현행 .github/workflows/ci.yml lines 138-144 에 lint-summary.js github-script step 존재 확인. .github/scripts/ 에 lint-summary.js + spotbugs-to-rdjsonl.py 양쪽 존재 확인. 재설계 §3 ci.yml report job(lines 161-168)은 JaCoCo 만 언급, lint 요약 미언급. _service-ci.yml(line 131)은 reviewdog 인라인만 적고 spotbugs 변환 스크립트·lint-summary 미반영.
  **suggestion**: plan 진입 전 또는 plan 첫 항목으로 lint 신호 재배치를 결정한다 — lint-summary 도 JaCoCo 와 동일하게 per-service 산출→취합 report job 단일 코멘트로 흡수(권장, O4 정합), 또는 명시적 제거(현행 신호 손실 수용 시 근거 명문화). spotbugs-to-rdjsonl.py 의 per-service reviewdog step 내 호출 위치도 §3 스케치에 반영.

- **id**: F2
  **severity**: minor
  **checklist_item**: 이 변경이 건드리는 모듈/패키지 경계가 명시됨 (scope)
  **location**: docs/topics/CI-PIPELINE-REDESIGN.md §3 line 185 (UserQueryUseCaseTest.java 경로)
  **problem**: 신규 테스트 대상 경로를 `user-service/.../application/UserQueryUseCaseTest.java` 로 적었으나 실제 프로덕션 클래스는 `application/usecase/UserQueryUseCase.java` 에 위치한다. 테스트 패키지가 application 직하인지 application/usecase 인지 plan 에서 흔들릴 여지. 코드 사실 영향은 없고 경로 정밀도 문제.
  **evidence**: 실제 경로 user-service/src/main/java/com/hyoguoo/paymentplatform/user/application/usecase/UserQueryUseCase.java 확인(29줄). 측정 라인 표(line 213)의 "0/3" 은 메서드 본문 라인 기준으로 정합.
  **suggestion**: plan 에서 테스트 패키지를 production 미러(application/usecase)로 명시.

## JSON
```json
{
  "stage": "discuss",
  "persona": "critic",
  "round": 1,
  "task_id": null,

  "decision": "revise",
  "reason_summary": "Gate checklist 대부분 충족·코드 사실 전부 일치·내부 모순 없음. 그러나 현행 lint 신호(lint-summary.js 단일 코멘트 + spotbugs-to-rdjsonl.py)의 새 per-service/취합 구조 재배치가 §3 에서 누락되어 변경 전수성에 major gap.",

  "checklist": {
    "source": "_shared/checklists/discuss-ready.md",
    "items": [
      { "section": "scope", "item": "TOPIC UPPER-KEBAB-CASE 확정", "status": "yes", "evidence": "CI-PIPELINE-REDESIGN.md line 1" },
      { "section": "scope", "item": "모듈/패키지 경계 명시", "status": "no", "evidence": "§3 가 현행 lint-summary.js/spotbugs-to-rdjsonl.py 의 새 구조 재배치를 다루지 않음 (ci.yml lines 138-144 대비 §3 report job lines 161-168)" },
      { "section": "scope", "item": "non-goals 1개 이상", "status": "yes", "evidence": "§ non-goals 7건 (lines 249-257)" },
      { "section": "scope", "item": "범위 밖 이슈 TODOS 위임/포함", "status": "yes", "evidence": "header lines 4 (CLEANUP-BATCH-B / FLYWAY-USER-SEED-GAP 인용)" },
      { "section": "design decisions", "item": "hexagonal layer 배치", "status": "n/a", "evidence": "CI/빌드 인프라 토픽, 런타임 도메인 무변경 (§ non-goals line 256)" },
      { "section": "design decisions", "item": "포트 인터페이스 위치", "status": "n/a", "evidence": "동상" },
      { "section": "design decisions", "item": "새 상태 전이 다이어그램", "status": "n/a", "evidence": "상태 추가 없음" },
      { "section": "design decisions", "item": "전체 결제 흐름 호환성 검토", "status": "yes", "evidence": "V4 도메인 리스크 ≈0 (§ non-goals line 256, interview-0 V4)" },
      { "section": "design decisions", "item": "D4 단일코멘트 vs jacoco 내장비교 양립성 plan 이연", "status": "yes", "evidence": "D4 검증필요 lines 72-75 + §5 적신호 line 243, 양립불가 시 단일코멘트 우선 명문화" },
      { "section": "design decisions", "item": "D2 build -x integrationTest 강제", "status": "yes", "evidence": "D2 주의 line 52 + §5 line 244, 코드 check.dependsOn integrationTest 확인" },
      { "section": "design decisions", "item": "액션 메이저 bump breaking 플래그", "status": "yes", "evidence": "§3 액션 표 lines 191-202 각 액션 breaking 비고 + §4-1 PR 실증" },
      { "section": "design decisions", "item": "내부 모순 부재", "status": "yes", "evidence": "D8 has-integration false→true (line 99) 가 ci.yml user: true (line 157) 와 정합" },
      { "section": "acceptance criteria", "item": "성공 조건 관찰 가능 형태", "status": "yes", "evidence": "6 독립 막대 + coverage minimum 수치 표 lines 208-216 + 단일 코멘트 렌더" },
      { "section": "acceptance criteria", "item": "실패 관찰 수단", "status": "yes", "evidence": "lint gate + jacocoTestCoverageVerification (§4-2 line 224)" },
      { "section": "verification plan", "item": "테스트 계층 결정", "status": "yes", "evidence": "§4 단위+통합+jacoco verify+PR 자체실행 (lines 222-227)" },
      { "section": "verification plan", "item": "벤치마크 지표", "status": "n/a", "evidence": "k6/벤치 별도 토픽 명시 기각 (§ non-goals line 257)" },
      { "section": "artifact", "item": "결정 사항 섹션 존재", "status": "yes", "evidence": "§2 D1~D8 (lines 36-100)" }
    ],
    "total": 17,
    "passed": 12,
    "failed": 1,
    "not_applicable": 4
  },

  "scores": {
    "clarity": 0.88,
    "completeness": 0.72,
    "risk": 0.85,
    "testability": 0.83,
    "fit": 0.90,
    "mean": 0.836
  },

  "findings": [
    {
      "severity": "major",
      "checklist_item": "이 변경이 건드리는 모듈/패키지 경계가 명시됨",
      "location": "docs/topics/CI-PIPELINE-REDESIGN.md §3 (report job lines 161-168, _service-ci.yml lines 122-143) / .github/scripts/lint-summary.js, spotbugs-to-rdjsonl.py",
      "problem": "현행 lint 신호의 lint-summary.js 단일 요약 코멘트와 spotbugs-to-rdjsonl.py 변환이 새 per-service + 취합 report job 구조에서 어떻게 재배치되는지 미명시. lint 요약 코멘트가 6서비스 난립(O4 위반)/조용한 제거(신호 손실)/JaCoCo report job 흡수 중 어디로 갈지 plan 에 미결로 열려 변경 전수성이 깨짐. D4 적신호는 jacoco-report 코멘트 난립만 다루고 동일 위험인 lint-summary 누락.",
      "evidence": "현행 ci.yml lines 138-144 lint-summary.js github-script step 존재. .github/scripts/ 에 lint-summary.js + spotbugs-to-rdjsonl.py 양쪽 존재. 재설계 §3 report job(161-168)은 JaCoCo 표만, _service-ci.yml(131)은 reviewdog 인라인만 언급.",
      "suggestion": "plan 에서 lint 신호 재배치 확정 — lint-summary 도 per-service 산출→취합 report job 단일 코멘트 흡수(O4 정합, 권장) 또는 명시적 제거 근거 명문화. spotbugs-to-rdjsonl.py 의 per-service reviewdog step 호출 위치도 §3 반영."
    },
    {
      "severity": "minor",
      "checklist_item": "이 변경이 건드리는 모듈/패키지 경계가 명시됨",
      "location": "docs/topics/CI-PIPELINE-REDESIGN.md §3 line 185",
      "problem": "신규 테스트 경로를 application/UserQueryUseCaseTest.java 로 표기했으나 실제 production 클래스는 application/usecase/UserQueryUseCase.java. 경로 정밀도 문제(코드 사실 영향 없음).",
      "evidence": "실제 user-service/src/main/java/com/hyoguoo/paymentplatform/user/application/usecase/UserQueryUseCase.java(29줄) 확인. 측정 0/3 라인은 정합.",
      "suggestion": "plan 에서 테스트 패키지를 production 미러(application/usecase)로 명시."
    }
  ],

  "previous_round_ref": "discuss-interview-0.md",
  "delta": {
    "newly_passed": [],
    "newly_failed": [],
    "still_failing": []
  },

  "unstuck_suggestion": null
}
```
