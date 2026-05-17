# review-critic-2

**Topic**: PAYMENT-EOS-TRANSITION
**Round**: 2
**Persona**: Critic
**Stage**: review

## Reasoning

Round 1 의 major 2건 (RC1-1 dedupe baseline 충돌 / RC1-2 SCR + 통합 회귀 분리 미확인) 및 minor 3건 (RC1-3 KafkaProducerConfig stale Javadoc / RC1-4 PaymentEventDedupeStore Javadoc 클래스명 오기 / RC1-5 PaymentConfirmResultUseCase JavaTimeModule FQN) 이 8a60b79e + c6ce6368 두 fix 커밋으로 전부 흡수됨. 결정적 백본 `./gradlew :payment-service:test` 385/385 PASS + `./gradlew :payment-service:integrationTest` 23/23 PASS — 6개 통합 testsuite (JdbcPaymentEventDedupeStoreTest 4 / PaymentSchedulerTest 2 / PaymentCheckoutConcurrencyIntegrationTest 3 / PaymentControllerTest 4 / StockCompensationRecoveryIntegrationTest 5 / PaymentEosIntegrationTest 5) 의 build/test-results XML 직접 검증으로 failures=0 errors=0. fix 과정에서 노출된 hidden bug 3건 (gatewayType=null NOT NULL / CheckoutResult Jackson is-prefix 직렬화 비대칭 / IdempotencyStoreRedisAdapter race window) 도 동일 c6ce6368 커밋에서 모두 fix 됨. 새 critical 0, 새 major 0 — 단 IdempotencyStoreRedisAdapter race fix 의 IN_PROGRESS_TTL(10s) 과 polling timeout(2s) 비대칭 (RC2-1 minor) 1건만 확인. **pass** 판정.

## Checklist judgement

### task execution
- RED/GREEN/REFACTOR + fix 커밋 분리: yes (PET-3/5/8/12 TDD 페어 + 8a60b79e/bef3d033/c6ce6368 fix 3분할)
- 커밋 메시지 포맷: yes (`fix:` / `docs:` / `feat:` / `test:` / `refactor:` 정합)
- STATE.md 갱신: yes (STATE.md:3 "최종 수정: 2026-05-18 — review R1 fix 완료 (integrationTest 23/23 PASS)" 갱신)
- fix 커밋 분할 합리성: yes — 8a60b79e (직전 implementer 변경 묶음 — Testcontainer 패턴 전환 + JPA wiring + Javadoc) / bef3d033 (영구 SSOT 문서 3개 단일 docs 커밋) / c6ce6368 (checkout 500 회귀 단일 fix) 모두 의미 단위로 분리. STATE.md 단독 커밋 금지 규칙도 준수 (c6ce6368 안에 STATE.md + PLAN.md 묶음).

### test gate
- 전체 `./gradlew :payment-service:test` 통과: **yes** (build/test-results/test/*.xml 집계 tests=385 failures=0 errors=0)
- 통합 회귀 가드 `./gradlew :payment-service:integrationTest`: **yes** (6 testsuite tests=23 failures=0 errors=0 모두 검증)
- 신규 business logic 테스트 커버리지: yes (PaymentConfirmResultUseCaseTest + Handle*Test 4종 + PaymentEosIntegrationTest 5 시나리오 + JdbcPaymentEventDedupeStoreTest 4건 + IdempotencyStoreRedisAdapterTest 3건 갱신)

### convention
- Lombok 패턴 / LogFmt: yes (변경 없음)
- `var` 키워드 0: yes (grep IdempotencyStoreRedisAdapter / CheckoutResult 결과 0)
- `catch (Exception e)`: yes (신규 catch 는 `JsonProcessingException` / `InterruptedException` 구체 타입만)

### execution discipline
- 범위 밖 코드 수정: **부분** — c6ce6368 의 checkout 500 fix 가 PET 태스크 범위 밖 (CheckoutResult @JsonProperty / IdempotencyStoreRedisAdapter race). 단 R1 critic 본인이 RC1-1/RC1-2 회귀 분리 가드 요구 → integrationTest 23/23 회복 필요 → fix 정당화됨. PLAN.md "Review Round 1 Fix (RD1)" 섹션에 회귀 원인 + 처방 명시 → Rule 1 예외 인용 OK.

### domain risk
- `paymentKey` plaintext 로그 노출: yes (변경 없음)
- 보상/취소 멱등성 가드: yes (변경 없음 — 3-layer 보존)
- 상태 전이 불변식: yes (변경 없음)
- race window 격리: **개선** — IdempotencyStoreRedisAdapter race window (R1 발견 hidden bug) 가 IN_PROGRESS 마커 lock + loser polling 패턴으로 fix. PaymentCheckoutConcurrencyIntegrationTest 3/3 PASS 로 GREEN 보증. 단 IN_PROGRESS_TTL(10s) vs polling timeout(2s) 비대칭은 minor 보완 (RC2-1).

### diff 일관성 / R1 finding 흡수
- RC1-1 (dedupe baseline 충돌): **resolved** — JdbcPaymentEventDedupeStoreTest 4/4 PASS. fix 처방은 `@Testcontainers` → 수동 start 패턴 전환 (8a60b79e). 별도 schema 분리 / baseline-on-migrate 대신 컨테이너 lifecycle 명시 제어로 회피.
- RC1-2 (SCR 5건 회귀 vs 인프라 분리): **resolved** — StockCompensationRecoveryIntegrationTest 5/5 PASS 확인. 동일 패턴 (수동 start) + BaseIntegrationTest Redis/ProductPort/UserPort fake bean 추가 (8a60b79e).
- RC1-3 (KafkaProducerConfig stale Javadoc): **resolved** — line 86~89 "outbox 묶음은 PET-9 에서 삭제 예정" → "PET-9 에서 StockOutbox 묶음이 제거되어 이 템플릿이 stock-committed 발행의 단일 경로다" (8a60b79e).
- RC1-4 (PaymentEventDedupeStore Javadoc 클래스명 오기): **resolved** — `JdbcEventDedupeStore` → `JdbcPaymentEventDedupeStore` (8a60b79e).
- RC1-5 (PaymentConfirmResultUseCase JavaTimeModule FQN): **resolved** — import 추가 (8a60b79e). ObjectMapper DI 검토는 후속으로 보류 (PLAN 명시 없음 — minor).

## Findings

### RC2-1 (minor) — IdempotencyStoreRedisAdapter IN_PROGRESS_TTL vs polling timeout 비대칭
- **location**: `payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/idempotency/IdempotencyStoreRedisAdapter.java:37-40`
- **evidence**: `IN_PROGRESS_TTL_SECONDS = 10L` / `POLL_INTERVAL_MS = 50L` / `MAX_POLL_COUNT = 40` (= 2초 polling). winner 가 creator 호출에 2초 이상 걸리면 loser 는 IllegalStateException("멱등성 결과 대기 시간 초과") 를 던지지만 IN_PROGRESS_MARKER 는 8초 더 유지되어 후속 신규 요청도 동일 timeout 경로로 직진.
- **problem**: 정상 동작 환경에서는 creator 가 보통 ms 단위 (checkout 단순 INSERT) 라 문제가 보이지 않지만, PG 호출 지연 / DB lock 경쟁 / GC pause 등으로 creator 가 2초+ 걸리는 corner case 에서 loser side 만 빠르게 500 으로 surface 됨. PaymentCheckoutConcurrencyIntegrationTest 는 in-memory creator 라 0ms 수준이라 가드 못 함.
- **suggestion**: (a) `MAX_POLL_COUNT` 를 `IN_PROGRESS_TTL_SECONDS * 1000 / POLL_INTERVAL_MS` (=200) 로 동기화하거나 (b) `IN_PROGRESS_TTL_SECONDS` 를 polling timeout 과 동일 2초로 낮춰 fresh 요청 진입을 가능하게 하거나 (c) `TODOS.md` 후속 항목으로 등재. 본 PR 흡수 강제 아님 (minor).

### RC2-2 (minor) — `c6ce6368` 범위 밖 fix 4건이 단일 커밋 묶음
- **location**: `c6ce6368` (CheckoutResult @JsonProperty / IdempotencyStoreRedisAdapter race / PAYMENT_EVENT_INSERT_SQL gateway_type / BigDecimal assertion)
- **evidence**: 단일 commit 에 4개 독립 회귀 fix + PLAN.md "Review Round 1 Fix (RD1)" 섹션 추가 + STATE.md 갱신.
- **problem**: 회귀 사유 4건이 모두 PET 범위 밖 코드 (checkout 경로) 라서 의미 단위 분할 시 각 fix 가 추적 가능했을 것. 본 묶음은 review R1 fix 의 한 묶음으로 봐도 무방하나, future bisect 시 어느 fix 가 어느 회귀를 잡았는지 grep 으로 분리하기 어려움. PLAN.md "Review Round 1 Fix" 섹션이 4건 모두 enumerate 해서 mitigation 됨.
- **suggestion**: review 후속 fix 라면 (a) `fix(payment-eos-transition): checkout DB gateway_type NOT NULL 보완` / (b) `fix(payment-eos-transition): CheckoutResult Jackson key 명시` / (c) `fix(payment-eos-transition): IdempotencyStoreRedisAdapter race 회복` 3개로 분할이 이상적. 본 PR 단계에서는 PLAN 섹션이 충분히 enumerate 함 → 본 PR 흡수 강제 아님.

## JSON

```json
{
  "stage": "review",
  "persona": "critic",
  "round": 2,
  "task_id": null,
  "topic": "PAYMENT-EOS-TRANSITION",
  "decision": "pass",
  "reason_summary": "Round 1 major 2 + minor 3 전부 흡수. 결정적 백본(./gradlew test) 385/385 + 통합(integrationTest) 23/23 PASS — build/test-results XML 직접 검증. 새 critical 0, 새 major 0. minor 2건 (RC2-1 IN_PROGRESS_TTL 비대칭 / RC2-2 fix 커밋 묶음) 만 잔존. pass.",
  "r1_resolution": [
    {"id": "RC1-1", "resolved": true, "evidence": "JdbcPaymentEventDedupeStoreTest 4/4 PASS (build/test-results/integrationTest/TEST-...JdbcPaymentEventDedupeStoreTest.xml tests=4 failures=0). @Testcontainers → 수동 start 패턴 전환 (8a60b79e)."},
    {"id": "RC1-2", "resolved": true, "evidence": "StockCompensationRecoveryIntegrationTest 5/5 PASS (TEST-...StockCompensationRecoveryIntegrationTest.xml tests=5 failures=0). 동일 fix 패턴 + BaseIntegrationTest Redis/ProductPort/UserPort fake bean 추가 (8a60b79e)."},
    {"id": "RC1-3", "resolved": true, "evidence": "KafkaProducerConfig.java line 86~89 — 'PET-9 에서 StockOutbox 묶음이 제거되어 이 템플릿이 stock-committed 발행의 단일 경로다' (과거형 + 사실 진술) 으로 정정 (8a60b79e)."},
    {"id": "RC1-4", "resolved": true, "evidence": "PaymentEventDedupeStore.java line 9 — '{@code JdbcEventDedupeStore}' → '{@code JdbcPaymentEventDedupeStore}' (8a60b79e). PD1-1 분리 명명 결정 정합."},
    {"id": "RC1-5", "resolved": true, "evidence": "PaymentConfirmResultUseCase.java line 5 + line 90 — `import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;` 추가 + FQN 제거 (8a60b79e)."}
  ],
  "checklist": {
    "source": "_shared/checklists/code-ready.md",
    "items": [
      {"section": "test gate", "item": "전체 ./gradlew test 통과", "status": "yes", "evidence": "build/test-results/test/*.xml 집계 tests=385 failures=0 errors=0"},
      {"section": "test gate", "item": "통합 회귀 가드 ./gradlew :payment-service:integrationTest", "status": "yes", "evidence": "6 testsuite 합계 tests=23 failures=0 errors=0 (JdbcPaymentEventDedupeStoreTest 4 / PaymentSchedulerTest 2 / PaymentCheckoutConcurrencyIntegrationTest 3 / PaymentControllerTest 4 / StockCompensationRecoveryIntegrationTest 5 / PaymentEosIntegrationTest 5)"},
      {"section": "test gate", "item": "신규 business logic 테스트 커버리지", "status": "yes", "evidence": "IdempotencyStoreRedisAdapterTest 3건 갱신 + R1 회귀 가드 + 기존 5종 보존"},
      {"section": "task execution", "item": "fix 커밋 분할", "status": "yes", "evidence": "8a60b79e (implementer 변경 묶음) / bef3d033 (SSOT 단일 docs) / c6ce6368 (checkout 회귀 fix 묶음) — STATE.md 단독 커밋 금지 준수, docs 단일 커밋 규칙 준수"},
      {"section": "execution discipline", "item": "범위 밖 코드 수정 정당화", "status": "yes", "evidence": "c6ce6368 의 checkout 500 fix 가 PET 범위 밖이나 R1 RC1-2 회귀 분리 가드 요구로 정당화됨. PLAN.md 'Review Round 1 Fix (RD1)' 섹션에 회귀 원인 + 처방 enumerate. Rule 1 예외 인용 OK."},
      {"section": "domain risk", "item": "race window 격리 (IdempotencyStoreRedisAdapter)", "status": "yes", "evidence": "IN_PROGRESS 마커 lock + loser polling 패턴 도입 + PaymentCheckoutConcurrencyIntegrationTest 3/3 PASS. 단 IN_PROGRESS_TTL 비대칭 minor (RC2-1)."},
      {"section": "R1 흡수", "item": "RC1-1 dedupe baseline 충돌", "status": "yes", "evidence": "JdbcPaymentEventDedupeStoreTest 4/4 PASS"},
      {"section": "R1 흡수", "item": "RC1-2 SCR 회귀 분리", "status": "yes", "evidence": "StockCompensationRecoveryIntegrationTest 5/5 PASS"},
      {"section": "R1 흡수", "item": "RC1-3/4/5 minor 3건", "status": "yes", "evidence": "8a60b79e 안에서 3건 모두 fix 확인"}
    ],
    "total": 9,
    "passed": 9,
    "failed": 0,
    "not_applicable": 0
  },
  "scores": {
    "correctness": 0.92,
    "conventions": 0.93,
    "discipline": 0.88,
    "test_coverage": 0.94,
    "domain": 0.92,
    "mean": 0.92
  },
  "findings": [
    {
      "id": "RC2-1",
      "severity": "minor",
      "checklist_item": "domain risk (race window)",
      "location": "payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/idempotency/IdempotencyStoreRedisAdapter.java:37-40",
      "problem": "IN_PROGRESS_TTL_SECONDS(10) vs polling timeout(MAX_POLL_COUNT*POLL_INTERVAL_MS = 2초) 비대칭. winner creator 가 2초 이상 걸리면 loser 만 IllegalStateException 으로 surface 되고 IN_PROGRESS_MARKER 는 8초 더 유지되어 fresh 후속 요청도 timeout 직진. 정상 환경에서는 creator(checkout INSERT) 가 ms 단위라 노출 안 되지만 PG 지연 / GC pause corner case 에서 loser side 만 500 응답.",
      "evidence": "IdempotencyStoreRedisAdapter.java line 37 IN_PROGRESS_TTL_SECONDS=10L vs line 39-40 POLL_INTERVAL_MS=50L * MAX_POLL_COUNT=40 = 2초. PaymentCheckoutConcurrencyIntegrationTest 는 in-memory creator 라 가드 못 함.",
      "suggestion": "(a) MAX_POLL_COUNT 를 200 (= IN_PROGRESS_TTL * 1000 / POLL_INTERVAL) 으로 동기화, (b) IN_PROGRESS_TTL_SECONDS 를 2 로 낮춰 fresh 요청이 lock 만료 후 즉시 진입, 또는 (c) docs/context/TODOS.md 후속 등재. minor — 본 PR 흡수 강제 아님."
    },
    {
      "id": "RC2-2",
      "severity": "minor",
      "checklist_item": "task execution (커밋 분할)",
      "location": "c6ce6368 commit (CheckoutResult / IdempotencyStoreRedisAdapter / PAYMENT_EVENT_INSERT_SQL / BigDecimal assertion)",
      "problem": "단일 commit 에 4개 독립 회귀 fix 묶음. future bisect 시 어느 fix 가 어느 회귀를 잡았는지 추적이 어려움. PLAN.md 'Review Round 1 Fix (RD1)' 섹션이 4건 모두 enumerate 해서 mitigation 됨.",
      "evidence": "c6ce6368 diff — CheckoutResult.java + IdempotencyStoreRedisAdapter.java + PaymentControllerTest.java + PaymentCheckoutConcurrencyIntegrationTest.java + IdempotencyStoreRedisAdapterTest.java + STATE.md + PLAN.md 7 파일 일괄 변경.",
      "suggestion": "review 후속 fix 라면 의미 단위 3분할 ((a) gateway_type DB / (b) Jackson key / (c) Redis race) 이 이상적. 본 PR 단계에서는 PLAN 섹션 enumerate 로 충분 → 강제 분할 요구 없음 (minor)."
    }
  ],
  "previous_round_ref": "review-critic-1.md",
  "delta": {
    "newly_passed": [
      "통합 회귀 가드 ./gradlew :payment-service:integrationTest (R1 no → R2 yes)",
      "RC1-1 JdbcPaymentEventDedupeStore baseline 충돌 (R1 fail → R2 pass)",
      "RC1-2 SCR 회귀 vs 인프라 분리 미확인 (R1 fail → R2 pass)",
      "RC1-3 KafkaProducerConfig stale Javadoc (R1 minor → R2 resolved)",
      "RC1-4 PaymentEventDedupeStore Javadoc 클래스명 오기 (R1 minor → R2 resolved)",
      "RC1-5 PaymentConfirmResultUseCase JavaTimeModule FQN (R1 minor → R2 resolved)"
    ],
    "newly_failed": [],
    "still_failing": []
  },
  "unstuck_suggestion": null
}
```
