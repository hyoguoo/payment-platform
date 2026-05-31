# CLEANUP-BATCH-B — Round 0 Interviewer

## Ambiguity Ledger (4트랙)

### scope (범위)
- **확정**: TOPIC = `CLEANUP-BATCH-B` (UPPER-KEBAB-CASE).
- **확정**: 세 항목 묶음 — (1) spotbugsTest 위반 5건 회복, (2) NET-RETRY Feign ErrorDecoder 5xx 분기, (3) JaCoCo 커버리지 게이트 실효화.
- **확정 (Path 2)**: jacoco 정책은 **4서비스 공통화** — 루트 `subprojects` 블록으로 끌어올려 payment/pg/product/user 일관 적용. 서비스별 baseline 편차는 수용.
- **non-goals**:
  - 커버리지 **측정 대상 확대 안 함** — `infrastructure` 통째 제외하는 기존 TESTING.md 정책 유지(아래 가정 G1).
  - spotbugs 위반을 exclude filter 억제로 덮지 않음 (코드 정정으로 해결).
  - gateway/eureka-server 는 비즈니스 로직 거의 없어 jacoco 게이트 대상 검토에서 후순위(plan 에서 포함 여부 판단).

### constraints (제약)
- **확정 (Path 1)**: `docs/context/TESTING.md` §JaCoCo 정책이 `infrastructure`/`presentation` 등 제외를 **의도된 정책**으로 명시("Testcontainers 통합테스트로 검증, JaCoCo 라인 커버리지 의미 약함. 도메인+use case가 본질").
- **확정 (Path 2)**: 위 정책을 **유지**하고 게이트만 실효화 — 측정 대상은 application/domain 그대로, `jacocoTestCoverageVerification` 에 `limit { minimum }` 추가.
- **확정 (Path 2)**: spotbugs 5건은 **전부 코드 정정** — 억제(exclude filter / `@SuppressFBWarnings`) 사용 안 함.
- **확정 (Path 3, 위임)**: 통합테스트 커버리지 **합산 채택** (베스트 프랙티스 위임받음). 근거 아래 D-COV2.

### outputs (산출물)
- 루트 `build.gradle` `subprojects` 블록에 jacoco 공통 설정(제외 목록 + `classDirectories` + verification limit + 통합테스트 exec 합산) 이전.
- payment-service `build.gradle` 의 개별 jacoco 블록 정리(공통화와 중복 제거).
- spotbugs 위반 5개 테스트 파일 코드 정정.
- Feign ErrorDecoder 2개(`ProductFeignConfig`/`UserFeignConfig`) + `PaymentExceptionHandler` 5xx 분기 보강.

### verification (검증)
- **확정 (Path 1)**: `test`(단위, integration 태그 제외) + `integrationTest`(Testcontainers) 두 태스크 분리. `jacocoTestReport` 는 현재 `dependsOn test` 만 → integrationTest exec data **미합산** 확인됨.
- 커버리지 게이트 baseline: 통합테스트 합산 후 실측 → 현재 수치에서 안전 마진 둔 보수적 `minimum`(line 기준). 구체 값은 execute 단계 실측으로 확정.
- spotbugs/NET-RETRY: `./gradlew build` 전체 GREEN 회복 + 단위테스트로 5xx 분기 매핑 검증.

## 확정된 가정 (사용자 확인 거침)
- **G1**: infra 제외 정책 유지를 택했으므로, 사용자가 처음 관찰한 두 증상 중 (a) "커버리지 수치가 좁게 나옴", (b) "PR 코멘트에 변경 infra 파일이 안 뜸" 은 **이번 토픽으로 해소되지 않는다**. 이번 토픽의 커버리지 목표는 "게이트 실효화 + 통합테스트 반영으로 application/domain 수치 정확화"로 한정. (측정 대상 확대는 별도 토픽 여지로 남김.)
- **G2**: 통합테스트 합산은 application/usecase 레이어 커버리지를 통합 경로까지 반영해 baseline 을 정확하게 만든다 (측정 대상이 좁아도 usecase 는 통합테스트에서 많이 실행됨).
- **G3**: spotbugs EI_EXPOSE_REP2(`FakeMessagePublisher`) 는 `Throwable` 저장이라 방어적 복사 불가 — "전부 코드 정정" 결정 하에서 저장 구조 자체를 바꾸는 정정이 필요(예: 예외 공급 방식 변경). plan 에서 구체 방법 확정.

## 핵심 결정 근거 (Architect 전달용)
- **D-COV1**: 측정 대상 정책 유지 + verification `limit` 추가 (게이트 실효화).
- **D-COV2**: integrationTest exec data 를 jacocoTestReport executionData 에 합산. JaCoCo 표준 베스트 프랙티스(단위+통합 통합 측정)이며, 합산 없이 limit 을 걸면 통합 경로로만 커버되는 usecase 분기가 미달로 잡혀 게이트가 거짓 실패.
- **D-COV3**: 루트 subprojects 공통화로 4서비스 정합.
- **D-SB1**: spotbugs 5건 코드 정정 (NPE 4 + EI_EXPOSE 1).
- **D-NR1**: 비-503 5xx(502/504) 매핑 정책 + 429/503 분리 여부는 Architect 가 설계안에서 제안 → Round 1 판정.

## Round 0 종료
4트랙 모두 커버 + 핵심 가정 사용자 확인 완료. Architect Round 1 로 인계.
