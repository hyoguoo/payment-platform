# discuss-domain-1

**Topic**: CI-PIPELINE-REDESIGN
**Round**: 1
**Persona**: Domain Expert

## Reasoning
이 토픽은 CI/빌드 인프라 한정으로 결제 도메인 코드 변경이 0이며, 내가 봐야 할 유일한 축은 "CI 재설계가 결제 정합성 검증(EOS/멱등/보상/seed 차단)의 신뢰도를 떨어뜨리는가"다. 산출물의 핵심 도메인 주장(D3 retry 통합 한정, Testcontainers reuse 한계, D2 통합 분리, D8 동형 seed 가드)을 실제 build.gradle·EOS 통합테스트·FlywayDockerProfileTest로 교차 검증한 결과, 정합성 검증 커버리지를 약화시키는 경로를 찾지 못했다. 오히려 기존 EOS 테스트가 이미 `withReuse(true)` + per-test UUID orderId/eventUuid + 명시 cleanup으로 reuse 오염에 면역이라 D3 reuse 도입이 신규 리스크를 만들지 않음을 확인했다.

## Domain risk checklist
discuss-ready.md "domain risk" 섹션 — 이 토픽은 결제 런타임 변경이 없어 대부분 N/A. 본 토픽 맥락으로 재해석해 점검:
- [x] 멱등성 전략 결정 — N/A(신규 멱등 경로 도입 없음). 단, **기존 멱등 검증의 CI 실행 보존**은 확인 대상이었고 충족됨: `integrationTest` task가 `includeTags 'integration'`로 EOS/dedupe/보상 시나리오를 모두 끌어오며(payment-service/build.gradle:101-103), D2는 이 task를 별도 job으로 옮길 뿐 필터를 바꾸지 않음 → `PaymentEosIntegrationTest` 5시나리오(EOS commit/abort/중복IGNORE/multi-product DR-1/QUARANTINED D7) 전수 보존.
- [x] 장애 시나리오 3개+ — N/A(런타임 장애 모드 무변경). CI 관점 실패 모드는 §4·§5 적신호로 식별됨.
- [x] 재시도 정책 — **해당**: D3 test-retry. 아래 finding 1·2에서 별도 점검.
- [x] PII/민감정보 — N/A(신규 PII 도입 0, secrets: inherit는 기존 벤더 키 전달 경로 유지).

## 도메인 관점 추가 검토
1. **D3 test-retry 통합 한정 — "재시도로 가려지는 진짜 결함" 차단 적절**. retry를 `integrationTest` task에만 걸고 단위 `test`에는 미적용(D3, §5 트레이드오프). 결제 정합성의 진짜 결함(멱등 깨짐, race window, 상태 전이 오류)은 결정적이라 retry로 가려지면 안 되는데, 이들은 단위 테스트(`@ParameterizedTest @EnumSource` 상태 전이, Mockito use-case)에 위치하고 retry 대상에서 제외된다. 통합 retry는 Testcontainers cold-start 같은 인프라 flaky만 흡수. 방향 정확. 단 `maxRetries=2`/`maxFailures` 가드는 "예"로 제시만 됐고 plan에서 확정 필요(major 아님 — 통합 한정이라 결정성 손실 범위가 제한적).
2. **D3 Testcontainers reuse — 상태 오염으로 멱등/dedupe 검증을 오탐시키지 않음(코드로 확인)**. `PaymentEosIntegrationTest.java:112,118`이 **이미 `withReuse(true)`** 로 운영 중이고, (a) `@BeforeEach`/`@AfterEach`에서 `payment_event_dedupe` DELETE + `deleteAllInBatch`로 RDB 상태를 명시 정리(line 197-213), (b) 모든 시나리오가 orderId/eventUuid를 `UUID.randomUUID()`로 매 테스트 생성(line 222-223,256-257,295-296,335-336,406-407)하여 Redis dedup token(`decrement:done:{orderId}`/`compensation:done:{orderId}` P8D)·dedupe row 키가 run 간 충돌 불가. 즉 reuse는 컨테이너 기동만 재사용하고 정합성 검증은 키 유일성에 의존 → D3의 reuse 전역 활성화가 dedupe/멱등 검증을 약화시키는 경로 없음. 산출물 D3의 "job 내 한정 효과" 한계 서술도 정확.
3. **D2 통합 분리 — EOS/멱등/보상 검증 커버리지 동일 유지**. `build -x integrationTest`(D2)는 단위 job에서 통합만 제외하고, 통합 job이 `:<svc>:integrationTest`로 동일 task를 그대로 실행(ci.yml 스케치 line 140). `check.dependsOn integrationTest`가 실재함을 build.gradle(payment:120, pg:107, product:85)에서 확인 — 제외 누락 시 단위 job이 통합을 끌어오는 위험은 산출물이 §5 plan 확인 항목으로 이미 추적. 분리로 인한 시나리오 누락 없음.
4. **D7 커버리지 게이트 상향 — 결제 정합 로직 커버 수준 정당**. payment 0.89→0.90(실측 92.93%), pg 0.91→0.93(실측 96.21%)은 confirm 결과 처리·AMOUNT_MISMATCH 양방향 방어·상태 전이·보상 경로가 application/usecase/domain 라인에 충분히 잡혀 측정된 결과(§3 표). 안전마진 ~3%p로 회귀 게이트를 실효화하되 정상 변경을 막지 않는 균형. product 0.40→0.43은 측정 라인 46개로 마진 보수적 — 정합성 핵심(StockCommit dedupe)이 product 통합테스트에 있으므로 라인 게이트보다 통합 시나리오가 실질 가드. 정당.
5. **D8 user FlywayDockerProfileTest — seed 차단 회귀를 product와 동형으로 가드(교차 검증 완료)**. product 원본(`FlywayDockerProfileTest.java`)이 `@SpringBootTest(NONE)+@Testcontainers+@Tag("integration")+@ActiveProfiles("docker")`로 V2 seed 차단(flyway_schema_history V1 only + row count 0)을 검증하고, user에 실제 `db/seed/V2__seed_user.sql`이 존재함을 확인 → user 동형 테스트가 가드할 실제 회귀 대상이 있음. 결제 정합성 직접 관련은 아니나(user는 돈 흐름 밖) seed 오적용 회귀 방어로 유효. **부수효과 점검**: D8로 user `has-integration` false→true 전환 + Testcontainers 의존 신규는 결제 정합성에 부수효과 없음(user는 confirm/EOS 경로에 무관, payment→user Feign은 GET 단건 조회 전용 — INTEGRATIONS.md). seed 차단이 풀리면 user 테이블에 임의 row가 생기지만 결제 금전 정확성과 무관.

## Findings
- (minor) F1 — D3 test-retry `maxRetries`/`maxFailures` 구체값이 "예: maxRetries=2"로 예시 수준. 통합 한정이라 결정성 손실 범위는 작지만, `maxFailures` 가드 없이 retry만 켜면 다수 통합 동시 flaky 시 retry 폭주 가능 — plan에서 `maxFailures` 확정 권고. 결제 정합성 직접 위협 아님.
- (n/a) 결제 상태 전이/멱등/race window/PG 실패 모드/PII/금전 정확성 관련 신규 리스크 없음 — 런타임 도메인 코드 무변경, 기존 정합성 검증(EOS 5시나리오·dedupe·보상)이 CI 재배치 후에도 동일 task로 전수 실행됨을 코드로 확인.

## JSON
```json
{
  "stage": "discuss",
  "topic": "CI-PIPELINE-REDESIGN",
  "round": 1,
  "persona": "domain-expert",
  "decision": "pass",
  "findings": [
    {
      "id": "F1",
      "severity": "minor",
      "area": "verification-plan",
      "summary": "D3 test-retry maxFailures 가드 구체값 미확정 — 통합 한정이라 결정성 손실 범위는 제한적이나 retry 폭주 방지 위해 plan에서 maxFailures 확정 권고",
      "evidence": "docs/topics/CI-PIPELINE-REDESIGN.md:58 (maxRetries=2 예시), payment-service/build.gradle:97-118 (현 integrationTest task에 retry 미설정)",
      "domain_vector": "재시도가 진짜 결함을 가리는 위험은 단위 test 제외로 차단됨 — 통합 한정 retry는 인프라 flaky만 대상, 정합성 결함은 결정적 단위테스트에 위치"
    }
  ],
  "domain_risk_checked": {
    "idempotency_preserved": "EOS/dedupe 멱등 검증이 includeTags 'integration' task로 D2 분리 후에도 전수 실행 (PaymentEosIntegrationTest 5시나리오 보존)",
    "testcontainers_reuse_pollution": "없음 — 기존 EOS 테스트가 이미 withReuse(true) + per-test UUID orderId/eventUuid + 명시 cleanup으로 reuse 오염 면역 (PaymentEosIntegrationTest.java:112,118,197-213,222-223)",
    "coverage_gate": "payment 0.90/pg 0.93 상향은 실측(92.93%/96.21%) 기반 정합 로직 충분 커버를 반영 — 정당",
    "seed_block_regression": "D8 user 테스트가 product 동형 + 실제 V2__seed_user.sql 존재로 유효 가드 (FlywayDockerProfileTest 교차 검증)",
    "money_correctness": "런타임 도메인 무변경 — 금전 정확성 영향 0"
  }
}
```
