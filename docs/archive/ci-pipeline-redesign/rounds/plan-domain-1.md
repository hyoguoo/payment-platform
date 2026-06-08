# plan-domain-1

**Topic**: CI-PIPELINE-REDESIGN
**Round**: 1
**Persona**: Domain Expert

## Reasoning
이 토픽은 CI/빌드 인프라 재설계이며 런타임 결제 도메인(상태 전이·멱등성·정합성·PII·PG 연동·race window)을 직접 건드리지 않는다(전 태스크 domain_risk=false 타당). 단 결제 도메인 인접 리스크 3종(D7 게이트 상향, D3 test-retry 통합 한정, D8 user Flyway 가드)을 소스와 교차 검증한 결과, 모두 회귀 가드를 약화시키지 않는 방향으로 계획되었다. critical/major 결함 없음 → pass.

## Domain risk checklist
- [x] discuss에서 식별된 domain risk가 각각 대응 태스크를 가짐 — discuss V4("결제 도메인 리스크 ≈ 0")가 RESOLVED(가정)이고, 인접 리스크 3종은 T2/T4/T5에 매핑됨. n/a에 가까우나 인접 리스크는 대응 태스크 보유.
- [x] 중복 방지 체크가 필요한 경로에 계획됨 — 본 토픽은 새 결제 처리 경로를 만들지 않음. 기존 멱등 가드(payment_event_dedupe INSERT IGNORE, Lua dedup token, product stock_commit_dedupe)는 코드 무변경. n/a.
- [x] 재시도 안전성 검증 태스크 존재 — D3 test-retry 재시도 정책 신설. T2 완료 기준이 "retry가 integrationTest 블록 내에만, 단위 test 블록에는 없음"을 grep으로 강제(maxFailures=3 가드 P-DEFER-2). 재시도 안전성 검증 장치 존재.

## 도메인 관점 추가 검토

1. **[리스크 2 — test-retry가 결제 정합성 통합테스트에 적용됨, 검증 결과 안전]**
   `PaymentEosIntegrationTest`(EOS commit/abort/중복 INSERT IGNORE/multi-product dedupe 6시나리오), `StockCompensationRecoveryIntegrationTest`(보상 recovery), `JdbcPaymentEventDedupeStore*Test`(dedupe round-trip/cleanup)가 전부 `@Tag("integration")` (PaymentEosIntegrationTest.java:84 등)이므로 `integrationTest` task에 속한다. T2/D3가 retry를 `integrationTest`에만 적용하므로 이 결제 정합성 회귀 가드가 retry 대상에 포함된다. 이것이 "retry가 진짜 결함을 가리는" 위험의 실체. 그러나 P-DEFER-2(PLAN.md:27-38)가 `maxFailures=3` 가드를 명시 — 같은 suite에서 3건 이상 실패 시 retry 없이 즉시 중단해 다발 실패(=진짜 결함)를 retry가 흡수하지 못하게 한다. cold-start flaky(1~2건 산발)만 흡수. 도메인상 EOS abort invisibility(시나리오 #2)나 multi-product dedupe(#4) 같은 정합성 결함은 결정적으로 재현되므로 3건 이상 동반 실패 패턴이 되어 가드에 걸린다. 안전.

2. **[리스크 2 보강 — 단위 test에 retry 미적용 보장]**
   상태 머신 불변식 회귀 가드(`PaymentEventStatusSplitMethodTest`, `PaymentEventStatusCrossInvariantTest`)는 `@Tag` 없음 → 단위 `test`(루트 build.gradle:64-66 `excludeTags 'integration'`)로 분류된다. T2 완료 기준이 단위 test 블록 retry 0건을 grep으로 강제하므로 `canApplyConfirmResult` ↔ `canCompensateStock` 교차 불변식(DR-3) 검증이 retry로 가려지지 않는다. 안전.

3. **[리스크 1 — D7 게이트 상향 방향 검증]**
   payment 0.89→0.90, pg 0.91→0.93, product 0.40→0.43 (PLAN.md:155-157) 전부 상향(하향 아님). 루트 게이트(build.gradle:128-180)는 `jacocoTestCoverageVerification`이 `integrationTest.exec`를 합산(build.gradle:139-141)해 BUNDLE LINE COVEREDRATIO로 막는 구조라, EOS/dedupe/보상 통합테스트가 커버하는 application/usecase 라인이 게이트 분모·분자에 합산된다. 상향이므로 회귀 가드 커버 라인이 게이트에서 빠지는 일 없고 오히려 더 빡빡. 회귀 가드 무력화 없음.

4. **[리스크 3 — D8 user Flyway 가드의 기존 결제 경로 비침습성]**
   T4(PLAN.md:125-145)는 user-service에 FlywayDockerProfileTest + integrationTest task를 신설할 뿐 payment/pg/product 소스·build.gradle 결제 경로를 변경하지 않는다. product 49~56행 resolutionStrategy 동형 복제도 user build.gradle 한정. seed 차단 회귀 가드 추가일 뿐이라 결제 정합성 영향 0.

5. **[minor — payment 실측값 표기 불일치]**
   payment build.gradle:12 주석 "실측 92.87%" vs PLAN.md/topic "92.93%". 게이트 상향(0.90)은 두 값 모두에서 안전마진 내라 게이트 동작에는 무해하나, execute 시 실측 재확인 권장.

## Findings
- (minor) payment-service 실측 커버리지 표기 불일치(92.87% 주석 vs 92.93% PLAN). 게이트 0.90 상향은 두 값 모두 충족하므로 정합성 회귀 가드에 무해. execute에서 `jacocoTestReport` 재측정으로 확정 권장.

## JSON
```json
{
  "stage": "plan",
  "persona": "domain-expert",
  "round": 1,
  "task_id": null,

  "decision": "pass",
  "reason_summary": "CI/빌드 인프라 토픽으로 런타임 결제 도메인 무변경. 인접 리스크 3종(D7 게이트 상향·D3 test-retry 통합 한정·D8 user Flyway 가드)을 소스 교차 검증한 결과 모두 회귀 가드를 약화시키지 않는 방향. critical/major 없음.",

  "checklist": {
    "source": "_shared/checklists/plan-ready.md#domain-risk",
    "items": [
      {
        "section": "domain risk",
        "item": "discuss에서 식별된 domain risk가 각각 대응 태스크를 가짐",
        "status": "yes",
        "evidence": "discuss V4(결제 리스크≈0) RESOLVED; 인접 리스크 D7/D3/D8 → T5/T2,T4/T4 매핑 (PLAN.md 추적 테이블 209-218)"
      },
      {
        "section": "domain risk",
        "item": "중복 방지 체크가 필요한 경로에 계획됨",
        "status": "n/a",
        "evidence": "새 결제 처리 경로 없음. 기존 멱등 가드(payment_event_dedupe INSERT IGNORE, Lua dedup token, product stock_commit_dedupe) 코드 무변경 (CONFIRM-FLOW.md §13)"
      },
      {
        "section": "domain risk",
        "item": "재시도 안전성 검증 태스크 존재 (재시도 정책이 있는 경우만)",
        "status": "yes",
        "evidence": "D3 test-retry 신설. T2 완료기준이 retry를 integrationTest 블록 한정·단위 test 0건을 grep 강제, maxFailures=3 가드 (PLAN.md:99, 30)"
      },
      {
        "section": "domain risk (cross-check)",
        "item": "게이트 상향 방향이 맞는가(하향 아님), 회귀 가드 약화 없음",
        "status": "yes",
        "evidence": "payment 0.89→0.90, pg 0.91→0.93, product 0.40→0.43 전부 상향. 루트 게이트가 integrationTest.exec 합산 (build.gradle:139-141)"
      },
      {
        "section": "domain risk (cross-check)",
        "item": "결제 정합성 통합테스트에 retry 적용 시 maxFailures 가드가 다발 실패를 막는가",
        "status": "yes",
        "evidence": "PaymentEosIntegrationTest(@Tag integration, :84) 등 정합성 가드가 integrationTest에 포함되나 maxFailures=3이 정합성 결함(결정적 다발 실패) 흡수 차단 (PLAN.md:30)"
      }
    ],
    "total": 5,
    "passed": 4,
    "failed": 0,
    "not_applicable": 1
  },

  "scores": {
    "traceability": 0.95,
    "decomposition": 0.90,
    "ordering": 0.90,
    "specificity": 0.92,
    "risk_coverage": 0.93,
    "mean": 0.92
  },

  "findings": [
    {
      "severity": "minor",
      "checklist_item": "게이트 상향 방향이 맞는가, 회귀 가드 약화 없음",
      "location": "payment-service/build.gradle:12 vs docs/CI-PIPELINE-REDESIGN-PLAN.md:280",
      "problem": "payment 실측 커버리지 표기 불일치 — build.gradle 주석 92.87% vs PLAN/topic 92.93%.",
      "evidence": "payment-service/build.gradle:12 '실측 92.87%' 주석, PLAN.md 커버리지 표 92.93%",
      "suggestion": "게이트 0.90 상향은 두 값 모두에서 안전마진 내라 정합성 회귀 가드에 무해. execute에서 jacocoTestReport 재측정으로 실측값 확정."
    }
  ],

  "previous_round_ref": null,
  "delta": {
    "newly_passed": [],
    "newly_failed": [],
    "still_failing": []
  },

  "unstuck_suggestion": null
}
```
