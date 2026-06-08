# CI-PIPELINE-REDESIGN — discuss Round 0 (Interviewer)

> 사용자 요청: CI를 서비스별 fan-out 구조로 재설계 + 빌드·게이트 위생 4건 흡수.
> 사전 브리핑: `docs/topics/CI-PIPELINE-REDESIGN.md`.
> 본 라운드는 4트랙 ambiguity ledger 초기화 + 사전 브리핑 결정 1~5의 잔여 모호점 정리.

## 환경 메모

- 이 환경에 `AskUserQuestion` 툴이 없어 Path 2(user) 항목은 채팅에 직접 제시한다(프로토콜의 의도 = 사용자 판단 필요 항목 표면화). 사용자 답변을 받으면 RESOLVED 로 이관한다.

---

## 사용자 확정 사항 (RESOLVED — 재질문 금지)

| # | 결정 | 근거 |
|---|---|---|
| R1 | 변경 감지 미채택 — 매 PR 6서비스 전부 수행 | 결정성·머지게이트 단순성. 서비스 독립(공통 모듈 0, project 의존 0)이라 paths-filter 불필요 |
| R2 | 핵심 축 = B(서비스별 fan-out 막대) / C(Gradle 캐시 + Testcontainers 재사용 + flaky 자동 retry) / D(PR 단일 통합 리포트) | 사용자 지정 |
| R3 | Discord 알림 제거 | 사용자 지정 |
| R4 | 위생 4건 흡수: Node 24 액션 업그레이드 / Groovy `=` 문법 / 커버리지 게이트 전 모듈 실측 상향 / user Flyway Testcontainers 추가 | TODOS [CLEANUP-BATCH-B 후속] / [FLYWAY-USER-SEED-GAP] |
| R5 | 커버리지 게이트는 보류 아닌 실측 기반 상향, 전 모듈(user/product/gateway/eureka) | 사용자 지정 |
| R6 | reviewdog 인라인 유지 | 사전 브리핑 결정 4 |

---

## 코드 사실 (검증됨 — Path 1)

- 현재 `ci.yml`: job 2개.
  - `Test & Coverage`: 전 서비스 `./gradlew test` + JaCoCo + JUnit 리포트.
  - `Lint`: 전 서비스 checkstyle/spotbugs + reviewdog 인라인 + lint gate.
- 사용 액션: `checkout@v4`, `setup-java@v4`, `setup-gradle@v3`, `upload-artifact@v4`, `action-junit-report@v4`, `jacoco-report@v1.7.2`, `reviewdog/action-setup@v1`, `github-script@v7`.
- 6서비스 독립(`settings.gradle`: payment/gateway/eureka/pg/product/user), 공통 모듈·project 의존 0. 공통 영향원: 루트 `build.gradle` / `settings.gradle` / `gradle/`(wrapper) / `.github`.
- `integrationTest` task(`@Tag("integration")`, Testcontainers): payment/pg/product 有, user/gateway/eureka 無.
- JaCoCo minimum: payment 0.89, pg 0.91, product 0.40, user/gateway/eureka 0.0(루트 기본).
- Groovy `exceptionFormat "full"` 4곳. `test-retry` 플러그인 無. reusable workflow 無.

---

## Ambiguity Ledger — 4트랙

### Track 1: SCOPE (무엇을 바꾸나)

| ID | 항목 | 상태 | Path | 비고 |
|---|---|---|---|---|
| S1 | 6서비스 전수 수행 범위 | RESOLVED | — | R1 |
| S2 | 위생 4건 흡수 범위 | RESOLVED | — | R4 |
| S3 | Discord 제거 / reviewdog 유지 | RESOLVED | — | R3, R6 |
| S4 | 결정 1: fan-out 메커니즘 = **재사용 워크플로우**(`_service-ci.yml` 추출 후 6서비스 호출) | RESOLVED | Path 2 | 사용자 확정. matrix 미채택 |
| S5 | 결정 2: 단계 분리 = **서비스당 1막대 + 통합테스트만 별도 막대**(빌드·단위·커버리지·lint 한 job step, 통합 별도 job) | RESOLVED | Path 2 | 사용자 확정 |

### Track 2: CONSTRAINTS (지켜야 할 제약)

| ID | 항목 | 상태 | Path | 비고 |
|---|---|---|---|---|
| C1 | 매 PR 결정성 / 머지게이트 단순성 유지 | RESOLVED | — | R1 |
| C2 | 공통 영향원(루트 build/settings/gradle/.github) 변경 시 전 서비스 영향 | RESOLVED(사실) | Path 1 | 전수 수행이라 자동 커버 |
| C3 | **CI 액션 Node 24 타깃 버전 + breaking change** (checkout v5? setup-java v5? setup-gradle v4? upload-artifact 메이저? junit-report v5? jacoco-report 최신?) | **OPEN(격리)** | **Path 4** | Round 1 Architect WebSearch/changelog 확정. **추측 금지** |
| C4 | 결정 3: C축 범위 = **test-retry 통합테스트 한정** + Testcontainers `reuse.enable` + setup-gradle 기본 캐싱(build cache 적극 활용 미채택) | RESOLVED | Path 2 | 사용자 확정 |

### Track 3: OUTPUTS (산출물 형태)

| ID | 항목 | 상태 | Path | 비고 |
|---|---|---|---|---|
| O1 | 6서비스 독립 막대로 GitHub Actions UI 렌더 | RESOLVED | — | R2-B |
| O2 | PR 단일 통합 리포트(서비스별 표 + 커버리지 델타 + 테스트수), github-script 조립 | RESOLVED(형태) | — | R2-D |
| O3 | 결정 4: 커버리지 델타 = **jacoco-report(Madrapps) 액션 내장 base 비교** 활용 | RESOLVED | Path 2 | 사용자 확정. 서비스별 동작 여부는 Round 1 Architect 확인 |
| O4 | 단일 코멘트 조립 시 아티팩트 수집 → github-script 단일 코멘트 | RESOLVED(형태) | — | 메모리 feedback: PR 리포트 단일 코멘트 |

### Track 4: VERIFICATION (검증 방법)

| ID | 항목 | 상태 | Path | 비고 |
|---|---|---|---|---|
| V1 | **커버리지 실측 minimum 수치** (user/product/gateway/eureka 각 모듈) | **OPEN(격리)** | **Path 4** | Round 1 Architect 가 실측 후 제안. **측정 대기** |
| V2 | user Flyway Testcontainers 추가로 seed 차단 회귀 방어 (product `FlywayDockerProfileTest` 패턴 차용) | RESOLVED(목표) | — | R4. 구체 구현은 plan |
| V3 | 재설계 후 6서비스 전부 green + 위생 4건 반영 확인 | RESOLVED(목표) | — | verify 단계 |
| V4 | 결제 도메인 리스크: CI/빌드 인프라라 0에 근접 | RESOLVED(가정) | — | 사전 브리핑 |

---

## 사용자 확정 결정 (RESOLVED — Round 1 Architect 인계)

### S4 — 결정 1: fan-out 메커니즘 = 재사용 워크플로우 (RESOLVED)

- `_service-ci.yml` 을 추출하고 6서비스가 입력 파라미터로 호출한다. `strategy.matrix.service` 미채택.
- 근거: 서비스별 integrationTest 유무·커버리지 minimum 차이를 입력 파라미터로 깔끔히 표현, 단계별 job 분리가 자연스러움(결정 2와 정합).

### S5 — 결정 2: 단계 분리 = 서비스당 1막대 + 통합테스트만 별도 (RESOLVED)

- 빌드·단위·커버리지·lint 는 서비스당 한 job 의 step 으로 묶고, integrationTest 만 별도 job(별도 막대)으로 분리한다.
- 효과: 느린 통합 실패를 단위·lint 실패와 막대 레벨에서 구분. 통합 없는 서비스(user/gateway/eureka)는 통합 막대 미생성.

### C4 — 결정 3: C축 범위 = 통합 한정 retry + reuse + 기본 캐싱 (RESOLVED)

- test-retry: integrationTest 에만 적용(전 모듈 미적용). cold-start flaky 이력이 통합 쪽이므로 범위 한정.
- Testcontainers `reuse.enable`: 활성화.
- Gradle cache: `setup-gradle` 기본 의존성 캐싱만. build cache(태스크 출력) 적극 활용 미채택.

### O3 — 결정 4: 커버리지 델타 = jacoco-report 액션 내장 비교 (RESOLVED)

- Madrapps `jacoco-report` 액션 내장 base 비교 기능을 활용한다(별도 base run·캐시 보존 미채택).
- 잔여 확인: 서비스별로 액션 내장 base 비교가 정상 동작하는지 Round 1 Architect 가 확인.

---

## OPEN 항목 상세 (Path 4 격리 — 외부 확인/측정 대기)

### C3 — CI 액션 Node 24 버전 (Path 4 격리, 추측 금지)

- Round 1 Architect 가 각 액션 changelog/릴리스로 Node 24 런타임 버전 + breaking change 확정. 본 라운드에서 버전 추측하지 않는다.

### V1 — 커버리지 실측 minimum (Path 4 격리, 측정 대기)

- Round 1 Architect 가 user/product/gateway/eureka 실측 커버리지 측정 후 현실적 상향 수치 제안.

---

## Dialectic Rhythm 메모

- Path 1(코드 사실) 다수 + Path 4 격리 2건. Path 2(사용자) 항목 S4/S5/C4/O3 4건을 채팅에 일괄 표면화하여 Rhythm Guard(1/4 3연속 후 2) 충족.
- 사용자 답변 수령(2026-06): S4/S5/C4/O3 4건 RESOLVED 이관 완료.

## Round 0 종료 판정 (마감)

- 4트랙(scope/constraints/outputs/verification) 모두 최소 1회 커버 — 충족.
- RESOLVED 20 / OPEN 2 (Path 4 격리만 잔존: C3 액션 Node 24 버전·breaking change / V1 모듈별 커버리지 실측 minimum).
- 사용자 판단 4건 확정 완료. Path 4 잔여 2건은 Round 1 Architect 가 WebSearch/측정으로 확정.
- **Round 0 마감.** Architect 에게 인계.
