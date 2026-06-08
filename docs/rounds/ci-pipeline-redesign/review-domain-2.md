# review-domain-2

**Topic**: CI-PIPELINE-REDESIGN
**Round**: 2
**Persona**: Domain Expert

## Reasoning
1라운드 F1(payment 게이트가 build job 에서 통합 exec 미합산으로 0.89<0.90 영구 실패 → 게이트 완화 압력) 함정은 실측으로 해소됐다. 게이트를 단위 test.exec 기준으로 재정의(payment 0.86)한 결과 `:payment-service:build -x integrationTest --rerun-tasks` 가 EXIT=0, 단위 라인 89.23%(530/594)로 통과한다. 정합성 보호는 게이트가 아닌 integration-test job 의 pass/fail 로 이전됐고, 이 job 은 `:svc:integrationTest` 를 직접 실행하며 실패 시 job fail → 머지 차단되는 구조다(report job always() 는 코멘트 전용). 단위 정합성 회귀 가드(D7/EOS/AMOUNT_MISMATCH 단위 테스트)는 여전히 단위 test.exec 안에 포함돼 게이트 측정 대상이며, 정합성 오케스트레이터(PaymentConfirmResultUseCase)는 단위 94.7% 로 게이트 보호를 받는다. 잔여는 minor 1건(트레이드오프 명시 부족).

## Domain risk checklist
- 상태 전이 정합성: CI 변경, 런타임 상태머신 무변경 — n/a. PaymentEventStatus(domain/enums) 자체는 게이트 excludePatterns(`**/enums/**`)로 게이트 측정 밖이나, 이는 변경 전후 동일한 기존 구조 — 본 변경의 신규 리스크 아님. canApplyConfirmResult(D7) 로직은 SplitMethodTest/CrossInvariantTest 단위 pass/fail 로 가드.
- 멱등성/dedupe 가드: 단위 test{}에 retry 미적용(루트 `excludeTags 'integration'` + retry 는 task integrationTest 한정) 확인 → D5 INSERT IGNORE / D7 / AMOUNT_MISMATCH 단위 회귀 결정성 유지. maxFailures=3 가드로 통합 다발 실패 시 진짜 결함 노출 — 양호.
- PG 실패 모드: 게이트는 이제 명시적으로 단위 커버리지만 평가. pg confirm 정합성 통합테스트는 pg integration-test job pass/fail 로 보호 — 머지 차단 유효.
- race window: 해당 없음(CI).
- PII: 해당 없음.
- 금전 정확성/돈 새는 경로: 직접 경로 없음. 1라운드 F1 의 간접 리스크(깨진 게이트 → 완화 압력 → 정합성 통합테스트 보호 상실)는 두 축으로 해소됨 — (a) 게이트가 통과해 완화 압력 제거, (b) 정합성 통합테스트가 별도 필수 check 로 머지 차단.
- 머지 게이트 헐거움: report job `if: always() && pull_request` 는 PR 단일 코멘트 조립 전용. 각 서비스 build-test-lint + integration-test job 의 실제 task 실행 결과가 check 로 머지 차단 — 양호.

## 도메인 관점 추가 검토

1. **[해소 확인] 1라운드 F1 — payment 게이트 영구 실패 + 완화 압력**
   - 수정: 루트 `build.gradle` afterEvaluate 에서 `jacocoTestCoverageVerification.executionData(integrationTest.exec)` 라인 제거 → 게이트는 `test.exec` 단위 exec 만 평가. payment minimum 0.90→0.86(payment-service/build.gradle:13).
   - 실증: `:payment-service:build -x integrationTest --rerun-tasks` → EXIT=0, jacocoTestCoverageVerification 통과(Rule violated 없음). JaCoCo XML 단위 라인 530/594 = 89.23% > 0.86, 헤드룸 3.23%p. 커밋 주장(89.23%)과 일치.
   - 완화 압력 제거: 게이트가 단위 실측 위에서 통과하므로 "깨진 게이트를 풀려고 minimum 을 더 낮추거나 무력화" 하는 동기가 사라짐.

2. **[해소 확인] 정합성 통합테스트 보호 — 게이트→job pass/fail 이전이 유효**
   - `_service-ci.yml:188-217` integration-test job 은 `if: ${{ inputs.has-integration }}` 로 payment/pg/product/user 에서 실행, `./gradlew :svc:integrationTest`(line 206)를 **continue-on-error 없이** 직접 호출. 실패 시 step fail → job fail.
   - `PaymentEosIntegrationTest`(@Tag("integration")) 5시나리오(EOS commit/abort invisibility/INSERT IGNORE dedupe/multi-product DR-1/D7 가드)는 `integrationTest` 의 `includeTags 'integration'`(payment-service/build.gradle:101) 에 포함 → integration-test job 의 pass/fail 로 보호.
   - 머지 차단 구조: report job(ci.yml:65-68) always() 는 코멘트 조립만, 각 서비스 job(payment.integration-test 포함)이 required check 면 통합테스트 실패 시 머지 차단. 트레이드오프(게이트 단위化 ↔ 통합 pass/fail 필수)는 정합성을 약화시키지 않음. 단, 이 보호는 **branch protection 의 required checks 등록에 의존** — 워크플로우 코드만으로는 보장 못 함(아래 F2).

3. **[해소 확인] 단위 정합성 회귀 가드 보호 유지 — 0.86 이 과도하게 헐겁지 않음**
   - D7/EOS/AMOUNT_MISMATCH 단위 회귀 테스트(PaymentEventStatusSplitMethodTest / PaymentEventStatusCrossInvariantTest, @Tag 없음=default=단위)는 루트 `test{}`(`excludeTags 'integration'`)에 포함 → 단위 test.exec 에 합산 → 게이트 측정 대상 유지.
   - 정합성 오케스트레이터 `PaymentConfirmResultUseCase`(handleApproved D8/AMOUNT_MISMATCH, handleFailed SCR-6, handleQuarantined) 단위 라인 90/95 = 94.7% — 게이트(application 레이어, excludePatterns 미해당)가 실제로 이 라인을 보호. 0.86 헤드룸은 단위 회귀로 라인이 빠지면 게이트가 걸리는 수준.
   - 단, `PaymentEventStatus`(domain/enums)는 게이트 excludePatterns `**/enums/**` 로 측정 밖 — canApplyConfirmResult(D7)/isTerminal 의 커버리지는 게이트가 강제하지 않는다. **그러나 이는 변경 전(0.90)에도 동일했던 기존 구조이며 본 변경이 도입한 신규 리스크가 아니다.** 해당 enum 로직은 단위 테스트 pass/fail + CrossInvariantTest 불변식으로 가드된다.

4. **[minor] 게이트 단위화 트레이드오프가 빌드 주석에는 명시됐으나, "통합테스트 정합성 = integration-test job 필수 check" 라는 전제가 branch protection 설정에 의존함이 운영 문서에 미고정**
   - build.gradle 주석은 "통합테스트 정합성은 integration-test job 통과(pass/fail)로 보호"라 명시하나, integration-test job 이 실제로 머지를 차단하려면 GitHub branch protection 의 required status checks 에 각 서비스 build-test-lint + integration-test 가 등록돼야 한다. 이 등록이 누락되면 통합테스트가 빨개도 머지 가능 → 정합성 보호가 코멘트 수준으로 전락.
   - 1라운드 F3 recommendation 과 동일 축. 게이트를 단위로 낮춘 이번 결정은 이 required-check 등록 의존도를 더 키운다(게이트가 더 이상 통합 라인을 안 보므로 통합 보호의 유일한 backstop 이 job 차단뿐).

## Findings
- **F1 [해소]** 1라운드 major F1 해소. payment 게이트 통합 exec 합산 제거 + minimum 0.86 재정의로 build job(`-x integrationTest`)에서 단위 89.23% 통과(실증 EXIT=0). 게이트 완화 압력 제거. 정합성 통합테스트는 integration-test job pass/fail 로 보호 이전, 단위 회귀 가드는 단위 test.exec 에 잔존해 게이트 측정 유지.
- **F2 [minor]** 통합테스트 정합성 보호가 branch protection required-checks 등록에 의존(워크플로우 코드만으로 미보장). 게이트 단위화로 통합 보호의 유일 backstop 이 job 차단뿐이라 의존도 상승. 근거: `_service-ci.yml:188-206`(integration-test job, continue-on-error 없음 — job 차단 자체는 정상), `ci.yml:65-68`(report always() 코멘트 전용). 처방: 각 서비스 `build-test-lint` + `integration-test` job 을 required checks 로 등록하고 운영 문서/PR 체크리스트에 명시.
- **F3 [n/a]** PaymentEventStatus(domain/enums) 게이트 excludePatterns 제외는 변경 전후 동일한 기존 구조 — 본 변경 신규 리스크 아님. D7/isTerminal 로직은 단위 pass/fail + CrossInvariantTest 불변식 가드.
- **F4 [n/a]** retry 통합 task 한정, 단위 결정성 유지(루트 test{} excludeTags integration), maxFailures=3 가드 유효 — 단위 정합성 회귀 은폐 없음.

## JSON
```json
{
  "stage": "review",
  "topic": "CI-PIPELINE-REDESIGN",
  "round": 2,
  "persona": "domain-expert",
  "task_id": null,
  "decision": "pass",
  "reason_summary": "1라운드 F1(payment 게이트 영구 실패 + 완화 압력) 실측 해소(build -x integrationTest EXIT=0, 단위 89.23%>0.86). 정합성 통합테스트는 integration-test job pass/fail 로 머지 차단 보호, 단위 회귀 가드는 단위 exec 에 잔존. 잔여는 minor(required-check 등록 의존 명시) 1건뿐 → pass.",
  "checklist": {
    "source": "_shared/checklists/code.md (domain risk 섹션)",
    "items": [
      {
        "section": "domain-risk",
        "item": "결제 정합성 통합테스트가 머지 게이트로 보호됨",
        "status": "yes",
        "evidence": "_service-ci.yml:188-206 integration-test job 이 :svc:integrationTest 를 continue-on-error 없이 실행, 실패 시 job fail. PaymentEosIntegrationTest @Tag(integration) 포함(payment-service/build.gradle:101). 단 머지 차단은 branch protection required-checks 등록 의존(F2)"
      },
      {
        "section": "domain-risk",
        "item": "단위 정합성 회귀 가드가 커버리지 게이트에 잔존",
        "status": "yes",
        "evidence": "루트 build.gradle:66-67 test{} excludeTags 'integration' → D7/EOS/AMOUNT_MISMATCH 단위 테스트(@Tag 없음)는 단위 test.exec 포함. PaymentConfirmResultUseCase 단위 94.7%(90/95) 게이트 측정 대상"
      },
      {
        "section": "domain-risk",
        "item": "게이트 완화 압력 제거(영구 실패 해소)",
        "status": "yes",
        "evidence": "실증 :payment-service:build -x integrationTest --rerun-tasks EXIT=0, JaCoCo XML 단위 라인 530/594=89.23% > 0.86 헤드룸 3.23%p"
      },
      {
        "section": "domain-risk",
        "item": "0.86 이 단위 정합성 회귀를 못 막을 만큼 헐겁지 않음",
        "status": "yes",
        "evidence": "정합성 오케스트레이터 application 레이어가 게이트 측정 대상이고 단위 94.7% — 단위 회귀로 라인 빠지면 게이트 작동. enums 제외는 기존 구조(F3)"
      }
    ],
    "total": 4,
    "passed": 4,
    "failed": 0,
    "not_applicable": 0
  },
  "scores": {
    "correctness": 0.92,
    "conventions": 0.90,
    "discipline": 0.88,
    "test-coverage": 0.85,
    "domain": 0.90,
    "mean": 0.89
  },
  "findings": [
    {
      "severity": "minor",
      "checklist_item": "결제 정합성 통합테스트가 머지 게이트로 보호됨",
      "location": "_service-ci.yml:188-206; ci.yml:65-68; payment-service/build.gradle:11-13",
      "problem": "게이트를 단위 기준으로 낮춘 트레이드오프로 통합 정합성(EOS/dedupe/abort invisibility/D7) 보호의 유일 backstop 이 integration-test job 차단이 됐다. 이 job 의 실제 머지 차단력은 GitHub branch protection 의 required status checks 등록에 의존하며, 워크플로우 코드만으로는 보장되지 않는다. 등록 누락 시 통합테스트 실패가 머지를 막지 못해 정합성 보호가 PR 코멘트 수준으로 전락한다.",
      "evidence": "_service-ci.yml integration-test job 은 ./gradlew :svc:integrationTest 를 continue-on-error 없이 실행(job 차단 자체는 정상). report job 은 if: always() && pull_request 로 코멘트 조립 전용. build.gradle 주석은 'integration-test job 통과로 보호'라 명시하나 required-check 등록 요구는 코드/문서에 미고정",
      "suggestion": "각 서비스 build-test-lint + integration-test job 을 branch protection required status checks 로 등록하고, PLAN/운영 배포 체크리스트 또는 PR 본문에 'required checks 등록 = 통합 정합성 보호 전제'를 명시한다. (1라운드 F3 recommendation 과 동일 축, 본 결정으로 의존도 상승)"
    }
  ],
  "previous_round_ref": "review-domain-1.md",
  "delta": {
    "newly_passed": [
      "payment 게이트 영구 실패 해소(build -x integrationTest 통과)",
      "게이트 완화 압력 제거",
      "단위 정합성 회귀 가드 게이트 측정 잔존 확인"
    ],
    "newly_failed": [],
    "still_failing": []
  },
  "unstuck_suggestion": null
}
```
