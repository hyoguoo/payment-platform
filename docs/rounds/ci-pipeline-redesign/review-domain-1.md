# review-domain-1

**Topic**: CI-PIPELINE-REDESIGN
**Round**: 1
**Persona**: Domain Expert

## Reasoning
이 토픽은 런타임 결제 코드를 건드리지 않으므로 새 도메인 로직 결함은 없다. 그러나 CI 게이트 구조를 실측으로 검증한 결과, payment-service의 커버리지 게이트가 build job에서 통합 exec 없이 평가되어 결제 정합성 통합테스트(EOS/dedupe/보상)가 보호한다고 믿는 라인이 실제로는 게이트 산정에서 빠지고, 그 결과 게이트가 영구 실패해 게이트 완화 압력을 만든다. retry·always() 등 나머지 신호 약화 경로는 실측상 무해했다.

## Domain risk checklist
- 상태 전이 정합성: CI 변경, 런타임 상태머신 무변경 — n/a
- 멱등성/dedupe 가드: 단위 test에 retry 미적용 확인(루트 test{} 블록 retry 없음, 통합 task에만) → EOS dedupe/D7 단위 회귀 테스트는 결정성 유지. 통합 retry maxFailures=3 가드로 다발 실패 시 진짜 결함 노출 — 양호
- PG 실패 모드: pg-service 게이트 0.93은 단위 exec만으로 통과(실증) — pg confirm 정합성 라인 보호 유지
- race window: 해당 없음(CI)
- PII: 해당 없음
- 금전 정확성/돈 새는 경로: 직접 경로 없음. 단, 게이트 신뢰성 훼손이 간접 리스크(아래 F1)
- 머지 게이트 헐거움: report job always()는 코멘트 전용, 머지 차단은 각 서비스 job(integration-test 포함)의 실제 task 실행 결과로 결정 — always()는 머지 게이트 무력화 아님(양호, F3)

## 도메인 관점 추가 검토

1. **[major] payment-service 커버리지 게이트가 build job에서 통합 exec 미합산으로 영구 실패 → 정합성 게이트 완화 압력**
   - `_service-ci.yml`의 `build-test-lint` job은 `./gradlew :payment-service:build -x integrationTest`로 호출(`_service-ci.yml`).
   - 루트 `build.gradle:124,139` 에서 `jacocoTestCoverageVerification`이 `check`에 묶여 build job에서 실행되고, `build.gradle:142`가 게이트 executionData에 `integrationTest.exec`를 합산하도록 wiring.
   - 실증: `integrationTest.exec` 없는 상태(=CI build job과 동일 조건)로 `:payment-service:build -x integrationTest --rerun-tasks` → `Rule violated for bundle payment-service: lines covered ratio is 0.89, but expected minimum is 0.90` → BUILD FAILED.
   - 즉 payment 게이트 0.90(`payment-service/build.gradle`)은 통합테스트 합산(92.93%) 전제로 산정됐는데, build job에는 통합 exec가 없어 단위 exec만(0.89)으로 평가 → 항상 실패. 통합테스트는 별도 `integration-test` job(`:svc:integrationTest`만 호출, verification 미실행)에서 돌아 그 exec가 게이트에 합산되지 않는다(ephemeral runner 분리).
   - **도메인 리스크**: 운영자가 깨진 게이트를 풀려고 minimum을 단위-only 기준(~0.85)으로 낮추거나 게이트를 무력화하면, PaymentEosIntegrationTest가 커버하는 EOS commit/abort·INSERT IGNORE dedupe(D5)·보상(SCR-6)·D7 가드 라인이 커버리지 보호에서 영구히 빠진다. 게이트가 "지킨다고 표시되지만 실제로 측정하지 않는" 함정. 결제 정합성 회귀가 게이트를 우회해 통과할 경로가 생긴다.
   - pg(0.93)/product(0.43)/user(0.97)은 단위 exec만으로 게이트 통과 실증 → 통합 exec 합산이 게이트에 무의미. 결국 게이트는 단위 커버리지만 강제하며, payment만 그 모순이 build 실패로 드러난다.

2. **[minor] user 0.97 게이트는 flaky 통합테스트와 무관 — 과긴장 위험 낮음**
   - 실증: `:user-service:build -x integrationTest --rerun-tasks`(단위 exec only) → 0.97 통과. UserQueryUseCase는 단위 test(`UserQueryUseCaseTest`)만으로 100%, FlywayDockerProfileTest는 excludePatterns(infrastructure) 대상이라 게이트 미기여.
   - 따라서 FlywayDockerProfileTest가 Testcontainers cold-start로 flaky하게 깨져도 build job 게이트(0.97)는 단위 exec로 통과하고, 통합 실패는 별도 integration-test job에서 격리 검출 — 게이트 과긴장으로 인한 false block 위험은 낮다. (단, F1과 동일 구조라 user 게이트도 통합 라인을 실제로 보호하지는 않음.)

3. **[n/a] test-retry가 결제 정합성 단위 회귀를 가리는 위험 — 없음**
   - grep 확인: retry 블록은 4개 서비스 모두 `task integrationTest` 안에만 존재, 루트 `test {}`(build.gradle:65)에는 없음. EOS/D7/AMOUNT_MISMATCH 단위 회귀(PaymentEventStatusSplitMethodTest 등)는 retry 무적용 → 결정성 유지. maxFailures=3 가드가 통합테스트 다발 실패 시 재시도 중단해 진짜 결함 노출.

4. **[n/a] FlywayDockerProfileTest seed 차단 가드 — 결제 경로 영향 없음**
   - user docker profile의 V2 seed 미적용 검증(`FlywayDockerProfileTest`). MySQL Testcontainers 단독, Kafka/Eureka 비활성. 다른 서비스 빌드/CI와 격리(user-service 모듈 한정 의존 추가). 결제 플로우 무영향.

## Findings
- **F1 [major]** payment-service 커버리지 게이트가 CI build job에서 통합 exec 미합산으로 0.89<0.90 영구 실패. 게이트 완화 시 EOS/dedupe/보상 정합성 통합테스트 커버 라인이 보호에서 빠지는 구조적 함정. 근거: `_service-ci.yml`(build -x integrationTest), `build.gradle:124,139-143`, `payment-service/build.gradle`(0.90), 실증 BUILD FAILED.
- **F2 [minor]** user 0.97 게이트는 단위 test만으로 충족, 통합 flaky 무관(과긴장 위험 낮음). 단 F1과 동일하게 통합 라인 실보호는 없음. 근거: 실증 build -x integrationTest 통과, excludePatterns.
- **F3 [n/a]** report job always()는 코멘트 전용, 머지 게이트는 각 서비스 job 실제 task 결과로 차단 — 머지 헐거워짐 없음.
- **F4 [n/a]** retry 통합 한정 확인, 단위 결정성 유지, maxFailures=3 가드 유효.

## JSON
```json
{
  "stage": "review",
  "topic": "CI-PIPELINE-REDESIGN",
  "round": 1,
  "persona": "domain-expert",
  "decision": "revise",
  "findings": [
    {
      "id": "F1",
      "severity": "major",
      "summary": "payment 커버리지 게이트가 CI build job에서 통합 exec 미합산으로 0.89<0.90 영구 실패 → 게이트 완화 시 EOS/dedupe/보상 정합성 통합테스트 커버 라인이 보호에서 빠지는 구조적 함정",
      "evidence": "_service-ci.yml build-test-lint: ./gradlew :payment-service:build -x integrationTest; build.gradle:124,139-143 (verification에 integrationTest.exec 합산 wiring, check에 묶임); payment-service/build.gradle jacoco.lineCoverageMinimum=0.90; 실증: integrationTest.exec 제거 후 build -x integrationTest --rerun-tasks → 'lines covered ratio is 0.89, but expected minimum is 0.90' BUILD FAILED. pg/product/user는 단위 exec만으로 통과 → 게이트가 통합 라인을 실보호하지 않음",
      "recommendation": "build job에서 게이트를 평가하려면 통합 exec를 같은 runner에서 합산하거나(통합테스트를 build job에 포함), 게이트를 integration-test job(통합 exec 보유)으로 이동. 게이트 minimum을 단위-only로 낮추는 방향은 정합성 통합테스트 보호 상실이므로 금지. payment 게이트 0.90이 통합 합산 전제임을 명시"
    },
    {
      "id": "F2",
      "severity": "minor",
      "summary": "user 0.97 게이트는 단위 test만으로 충족되어 flaky 통합테스트로 인한 false block 위험은 낮으나, F1과 동일 구조로 통합 라인 실보호는 없음",
      "evidence": "실증 :user-service:build -x integrationTest --rerun-tasks 통과; FlywayDockerProfileTest는 infrastructure excludePatterns 대상이라 게이트 미기여",
      "recommendation": "user 게이트가 사실상 단위 커버리지만 강제함을 인지. F1 해소 시 함께 일관 처리"
    },
    {
      "id": "F3",
      "severity": "n/a",
      "summary": "report job always()는 PR 코멘트 전용, 머지 게이트는 각 서비스 job 실제 task 결과로 차단 — 머지 헐거워짐 없음",
      "evidence": "ci.yml report job if: always() && pull_request, 코멘트 조립만; integration-test job은 :svc:integrationTest 직접 실행해 실패 시 job fail",
      "recommendation": "branch protection required checks에 각 서비스 build-test-lint + integration-test job을 등록해 머지 차단 유지 확인"
    },
    {
      "id": "F4",
      "severity": "n/a",
      "summary": "test-retry는 통합 task 한정, 단위 결정성 유지, maxFailures=3 가드 유효 — 결제 정합성 단위 회귀 은폐 없음",
      "evidence": "grep: retry 블록 4서비스 모두 task integrationTest 내부, 루트 test{}(build.gradle:65) 미적용; maxRetries=2 maxFailures=3",
      "recommendation": "없음"
    }
  ]
}
```
