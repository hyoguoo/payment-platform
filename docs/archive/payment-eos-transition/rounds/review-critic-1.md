# review-critic-1

**Topic**: PAYMENT-EOS-TRANSITION
**Round**: 1
**Persona**: Critic
**Stage**: review

## Reasoning

14 PET 가 한 PR 봉인된 EOS 전환 빅뱅 변경. 결정적 백본 `./gradlew test` 는 PASS, `PaymentEosIntegrationTest` 5 시나리오도 PASS. 그러나 `./gradlew :payment-service:integrationTest` 실행 시 18/23 통합 테스트가 FAIL — 그중 4건은 본 PR 에서 신설된 `JdbcPaymentEventDedupeStoreTest` (PET-5 단위 4건) 자체가 Flyway baseline 충돌로 ApplicationContext 로딩 실패. 나머지 14건은 인프라 의존성 (mysql/redis/kafka docker-compose) 미가용으로 분리 판단이 어려우나, 그중 `StockCompensationRecoveryIntegrationTest` 5건은 SCR 토픽 회귀 가드라 PET-8 재작성 영향 가능성이 있어 인프라 정상 환경에서 재실행이 반드시 필요. 추가로 미세 정합 흠 (KafkaProducerConfig L90 stale 주석 / PaymentEventDedupeStore Javadoc 의 잘못된 클래스명 `JdbcEventDedupeStore` / ObjectMapper FQN 사용) 3건 minor 가 보임. critical 0, major 2, minor 3 — **revise** 판정.

## Checklist judgement

### task execution
- RED/GREEN/REFACTOR 커밋 분리: yes (PET-3/5/8/12 모두 test→feat 페어 존재)
- 커밋 메시지 포맷: yes (`feat:` / `test:` / `refactor:` / `docs:` 일관)
- STATE.md 갱신: yes (PET-14 봉인 commit 안에 review 전환 포함)

### test gate
- 전체 `./gradlew test` 통과: **yes** (`BUILD SUCCESSFUL`, 결정적 백본)
- 신규 business logic 테스트 커버리지: yes (PaymentConfirmResultUseCaseTest + PaymentConfirmResultUseCaseHandle*Test 4종 + PaymentEosIntegrationTest 5 시나리오)
- `@ParameterizedTest @EnumSource` 신규 전이 커버: yes (PaymentEventStatusEosGuardTest)
- 통합 회귀 가드 `./gradlew :payment-service:integrationTest`: **no** — 18/23 FAIL (JdbcPaymentEventDedupeStoreTest 4건 신설 자체 RED 포함). code-ready 체크리스트는 `./gradlew test` 만 명시하나 review 단계에서는 회귀 검출 책임 있음.

### convention
- Lombok 패턴 (`@RequiredArgsConstructor` / `@Getter`): yes
- LogFmt 사용 (신규 로깅): yes (PaymentConfirmResultUseCase 내 4건)
- `null` 반환 / `catch (Exception e)`: yes (main 코드 grep 결과 0). 단 통합 테스트 `PaymentEosIntegrationTest.deserializeStockCommittedEvent` 에 `catch (Exception e)` 1건 — 테스트 헬퍼로 minor.
- `var` 키워드 0: yes (수정 main 파일 grep 결과 0)

### execution discipline
- 범위 밖 코드 수정: 일부 — `JpaConfig.java @Primary transactionManager` + `KafkaProducerConfig.commandsConfirmProducerFactory` 명시 등록 2건. Javadoc 에 PET-12 SUT 수정으로 명시 + 회귀 근거 인용 (PET-6 ambiguous wiring) → **수용**. Rule 1 "현재 태스크 범위 밖 코드는 수정하지 않는다" 예외로서 통합 RED→GREEN 가는 정당 사유 인용됨.

### domain risk
- `paymentKey` plaintext 로그 노출: no (orderId / eventUuid 만 로깅, paymentKey 노출 없음)
- 보상/취소 멱등성 가드: yes (D7 진입 가드 + D5 INSERT IGNORE + Redis dedup token 두 layer)
- 상태 전이 불변식: yes (`isCompensatableByFailureHandler` SSOT 로 DONE→FAIL/QUARANTINED→DONE 차단)
- race window 격리: yes (EOS 트랜잭션 + INSERT IGNORE atomic + Redis Lua atomic dedup)

### diff 일관성 / dead code
- `StockOutbox` 묶음 잔재: 거의 0 — V1 DDL 의 `stock_outbox` 테이블 정의 (history 보존) + V3 DROP + `StockEventUuidDeriver` (idempotencyKey 도출 — DR-1 보존) 외에는 잔재 없음. **단** `KafkaProducerConfig.java:90` Javadoc 에 "기존 stockOutboxKafkaTemplate 과 다른 빈 — outbox 묶음은 PET-9 에서 삭제 예정" 미래형 문장 잔재 → minor.

### PD1-1 (`PaymentEventDedupeStore` 분리 명명) 일관성
- 신설 port / impl / Fake / 테스트 / Flyway / Javadoc 모두 `PaymentEventDedupeStore` / `JdbcPaymentEventDedupeStore` 로 정합. **단** `PaymentEventDedupeStore.java:9` Javadoc 본문에 "PET-5 `JdbcEventDedupeStore`" 라고 잘못 적힘 (실제 구현체 명은 `JdbcPaymentEventDedupeStore`) → minor.

### Java 코드 스타일
- `var` 키워드 사용 0건 — 정합.
- `PaymentConfirmResultUseCase.java:88-89` `new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()` fully qualified name 사용 (import 없음). 동일 클래스가 PaymentEosIntegrationTest 에서는 정상 import. 단순 흠. → minor.

## Findings

### RC1-1 (major) — JdbcPaymentEventDedupeStoreTest 4건 ApplicationContext 로드 실패
- **location**: `payment-service/src/test/java/com/hyoguoo/paymentplatform/payment/infrastructure/dedupe/JdbcPaymentEventDedupeStoreTest.java` (전체 4 테스트)
- **evidence**: `./gradlew :payment-service:integrationTest` 결과 XML — `Caused by: ... Found non-empty schema(s) 'payment-test' but no schema history table. Use baseline() or set baselineOnMigrate to true`. testsuite tests="4" failures="4" errors="0".
- **problem**: PET-5 PLAN 명세 "JdbcPaymentEventDedupeStoreTest 단위 4건 신설" 항목이 실제로는 통합 환경에서 회귀. Testcontainers `withReuse(true)` + Flyway baseline 정책 충돌로 데이터베이스 schema history table 누락 인식. PaymentEosIntegrationTest 와 같은 mysql 컨테이너를 reuse 시 schema 가 dirty 상태로 남아 후속 테스트가 baseline 실패.
- **suggestion**: `spring.flyway.baseline-on-migrate=true` 추가 (DynamicPropertySource) 또는 `JdbcPaymentEventDedupeStoreTest` 의 `MYSQL_CONTAINER.withDatabaseName` 을 `payment-dedupe-test` 등 고유 이름으로 분리. 또는 `withReuse(false)` 로 컨테이너 격리.

### RC1-2 (major) — 통합 테스트 18/23 RED — 회귀 vs 인프라 의존 분리 미확인
- **location**: `payment-service/build/test-results/integrationTest/*.xml`
- **evidence**: 5개 testsuite (`PaymentControllerTest` 4 / `StockCompensationRecoveryIntegrationTest` 5 / `PaymentSchedulerTest` 2 / `PaymentCheckoutConcurrencyIntegrationTest` 3 / `JdbcPaymentEventDedupeStoreTest` 4) 모두 failures>=tests. `docker ps` 결과 payment-platform 인프라 컨테이너 미가용 (crawl-alert-bot 만 살아있음).
- **problem**: 18건 중 14건은 인프라 미가용 (`Could not open JPA EntityManager for transaction`) 으로 인한 실패로 추정되지만, **`StockCompensationRecoveryIntegrationTest` 5건 중 일부는 `expected: FAILED but was: IN_PROGRESS` / `compensateAtomic Wanted but not invoked` 같은 PET-8 재작성 영향 가능성이 있는 어설션 실패**. 인프라 미가용 환경에서 분리 판별 불가. PR 머지 전에 docker-compose 가동 후 재실행 필요.
- **suggestion**: verify 단계에서 `bash scripts/docker-up.sh && ./gradlew check` 결과를 평가. SCR 회귀 5건이 docker 가동 후에도 FAIL 이면 PET-8 의 보상 호출 순서 변경 (보상 → markPaymentAsFail) 이 SCR 가드 시나리오 어설션과 충돌 했는지 검증 필요.

### RC1-3 (minor) — KafkaProducerConfig stale Javadoc
- **location**: `payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/config/KafkaProducerConfig.java:90`
- **evidence**: `<p>기존 stockOutboxKafkaTemplate 과 다른 빈 — outbox 묶음은 PET-9 에서 삭제 예정.` PET-9 가 이미 완료된 상태에서 미래형 문장 잔재.
- **problem**: PET-6 GREEN 시점 Javadoc 이 PET-9 완료 후에도 그대로 남아 시간 일관성 깨짐.
- **suggestion**: "outbox 묶음은 PET-9 에서 폐기 완료" 또는 문장 삭제.

### RC1-4 (minor) — PaymentEventDedupeStore Javadoc 의 구현체 클래스명 오기
- **location**: `payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/application/port/out/PaymentEventDedupeStore.java:9`
- **evidence**: `affected row 수를 반환한다 (PET-5 {@code JdbcEventDedupeStore}).` — 실제 구현체 이름은 `JdbcPaymentEventDedupeStore`. PD1-1 분리 명명 결정 위반.
- **problem**: PD1-1 forward-fix (`PaymentEventDedupeStore` / `JdbcPaymentEventDedupeStore` 분리) 의도와 Javadoc 텍스트가 어긋남. product-service `JdbcEventDedupeStore` 와 의도 충돌 가능.
- **suggestion**: `JdbcEventDedupeStore` → `JdbcPaymentEventDedupeStore` 로 정정.

### RC1-5 (minor) — PaymentConfirmResultUseCase ObjectMapper FQN 사용
- **location**: `payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/application/usecase/PaymentConfirmResultUseCase.java:88-89`
- **evidence**: `this.objectMapper = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());` — `JavaTimeModule` import 없이 fully qualified name 직접 사용. 동일 클래스가 PaymentEosIntegrationTest 에서는 정상 import.
- **problem**: 스타일 일관성 흠. 또한 `ObjectMapper` 를 DI 없이 use-case 생성자에서 직접 인스턴스화 — `KafkaMessageConverterConfig` 가 등록하는 공유 `ObjectMapper` 와 결 충돌 가능성 (직렬화 정책 분기).
- **suggestion**: import 추가 + Spring 컨텍스트의 공유 `ObjectMapper` DI 검토. 별도 인스턴스 의도라면 Javadoc 으로 이유 명시.

## JSON

```json
{
  "stage": "review",
  "persona": "critic",
  "round": 1,
  "task_id": null,
  "topic": "PAYMENT-EOS-TRANSITION",
  "decision": "revise",
  "reason_summary": "결정적 백본(./gradlew test) PASS + PaymentEosIntegrationTest 5 시나리오 PASS. 그러나 통합 백본(./gradlew :payment-service:integrationTest) 에서 PET-5 신설 JdbcPaymentEventDedupeStoreTest 4건 자체가 Flyway baseline 충돌로 RED (RC1-1) + 다른 14건 RED 가 인프라 의존인지 PET-8 회귀인지 분리 미확인 (RC1-2). minor 3건은 stale Javadoc / 클래스명 오기 / FQN 사용.",
  "checklist": {
    "source": "_shared/checklists/code-ready.md",
    "items": [
      {"section": "test gate", "item": "전체 ./gradlew test 통과", "status": "yes", "evidence": "BUILD SUCCESSFUL UP-TO-DATE 34 actionable tasks"},
      {"section": "test gate", "item": "신규 business logic 테스트 커버리지", "status": "yes", "evidence": "PaymentConfirmResultUseCaseTest + Handle*Test 4종 + PaymentEosIntegrationTest 5 시나리오 PASS"},
      {"section": "test gate", "item": "통합 회귀 가드", "status": "no", "evidence": "./gradlew :payment-service:integrationTest 18/23 FAIL — JdbcPaymentEventDedupeStoreTest 4건 신설 자체 RED 포함 (RC1-1)"},
      {"section": "execution discipline", "item": "범위 밖 코드 수정 없음", "status": "yes", "evidence": "JpaConfig + KafkaProducerConfig 2건 PET-12 SUT 수정 + PET-6 ambiguous wiring 회복으로 정당화 (Rule 1 예외 인용)"},
      {"section": "convention", "item": "Java 스타일 (var 0)", "status": "yes", "evidence": "main 수정 파일 grep var 0건"},
      {"section": "domain risk", "item": "보상 멱등성 가드", "status": "yes", "evidence": "D7 진입 가드 + D5 INSERT IGNORE + Redis Lua atomic dedup 3-layer"},
      {"section": "diff 일관성", "item": "StockOutbox 잔재 0 (보존 대상 제외)", "status": "yes", "evidence": "grep StockOutbox 결과 — V1 DDL history + V3 DROP + StockEventUuidDeriver(보존) 외 0건. KafkaProducerConfig Javadoc 1줄 미래형 stale (RC1-3)"},
      {"section": "PD1-1 일관성", "item": "PaymentEventDedupeStore 분리 명명", "status": "no", "evidence": "Javadoc 본문에 JdbcEventDedupeStore 잘못된 클래스명 1건 (RC1-4)"}
    ],
    "total": 8,
    "passed": 6,
    "failed": 2,
    "not_applicable": 0
  },
  "scores": {
    "correctness": 0.75,
    "conventions": 0.85,
    "discipline": 0.80,
    "test_coverage": 0.70,
    "domain": 0.90,
    "mean": 0.80
  },
  "findings": [
    {
      "id": "RC1-1",
      "severity": "major",
      "checklist_item": "통합 회귀 가드",
      "location": "payment-service/src/test/java/com/hyoguoo/paymentplatform/payment/infrastructure/dedupe/JdbcPaymentEventDedupeStoreTest.java",
      "problem": "PET-5 신설 4 단위 테스트가 모두 ApplicationContext 로드 실패. Flyway baseline-on-migrate 미설정 + Testcontainers withReuse(true) 로 schema dirty 상태에서 baseline 충돌.",
      "evidence": "build/test-results/integrationTest/TEST-com.hyoguoo.paymentplatform.payment.infrastructure.dedupe.JdbcPaymentEventDedupeStoreTest.xml 의 'Found non-empty schema(s) `payment-test` but no schema history table'. tests=4 failures=4.",
      "suggestion": "DynamicPropertySource 에 spring.flyway.baseline-on-migrate=true 추가, 또는 withDatabaseName 을 PaymentEosIntegrationTest 의 payment-eos-test 와 분리되도록 payment-dedupe-test 등 고유 이름 부여, 또는 withReuse(false) 로 컨테이너 격리."
    },
    {
      "id": "RC1-2",
      "severity": "major",
      "checklist_item": "통합 회귀 가드",
      "location": "payment-service/build/test-results/integrationTest/*.xml (PaymentControllerTest, StockCompensationRecoveryIntegrationTest, PaymentSchedulerTest, PaymentCheckoutConcurrencyIntegrationTest)",
      "problem": "통합 테스트 14건 RED. docker-compose 인프라 미가용 가능성 + StockCompensationRecoveryIntegrationTest 5건 안에 'expected: FAILED but was: IN_PROGRESS' / 'compensateAtomic Wanted but not invoked' 같은 PET-8 호출 순서 재작성 (보상 → markPaymentAsFail) 영향 가능성 있는 assertion 실패가 보임. 인프라 의존성 vs PET-8 회귀 분리 미확인.",
      "evidence": "docker ps 결과 payment-platform 컨테이너 0개 (crawl-alert-bot 만 살아있음). StockCompensationRecoveryIntegrationTest.java:216, :281, :306 라인의 어설션 실패 — TimeoutException + WantedButNotInvoked.",
      "suggestion": "verify 단계에서 docker-compose 가동 후 ./gradlew :payment-service:integrationTest 재실행. SCR 5건이 인프라 가동 후에도 RED 면 PET-8 의 handleFailed 호출 순서 변경이 SCR-6 가드 시나리오와 호환되는지 단위 차원에서 재검증 (StockCompensationRecoveryIntegrationTest 가 옛 RDB→보상 순서를 기대하고 있을 가능성)."
    },
    {
      "id": "RC1-3",
      "severity": "minor",
      "checklist_item": "diff 일관성 (Javadoc 시간 정합)",
      "location": "payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/config/KafkaProducerConfig.java:90",
      "problem": "PET-9 가 이미 완료된 상태에서 '기존 stockOutboxKafkaTemplate 과 다른 빈 — outbox 묶음은 PET-9 에서 삭제 예정.' 미래형 문장 잔재. PET-6 GREEN 시점 Javadoc 이 PR 봉인 시점에 갱신 안 됨.",
      "evidence": "git show HEAD:payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/config/KafkaProducerConfig.java line 90.",
      "suggestion": "문장 삭제 또는 'outbox 묶음은 PET-9 에서 폐기 완료' 로 정정."
    },
    {
      "id": "RC1-4",
      "severity": "minor",
      "checklist_item": "PD1-1 일관성",
      "location": "payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/application/port/out/PaymentEventDedupeStore.java:9",
      "problem": "Javadoc 본문에 'PET-5 {@code JdbcEventDedupeStore}' 라고 잘못 적힘. 실제 구현체 이름은 JdbcPaymentEventDedupeStore (PD1-1 분리 명명 결정). product-service 의 JdbcEventDedupeStore 와 클래스명 충돌 가능.",
      "evidence": "PaymentEventDedupeStore.java line 9 Javadoc — 'affected row 수를 반환한다 (PET-5 {@code JdbcEventDedupeStore}).'",
      "suggestion": "{@code JdbcEventDedupeStore} → {@code JdbcPaymentEventDedupeStore}."
    },
    {
      "id": "RC1-5",
      "severity": "minor",
      "checklist_item": "convention (스타일)",
      "location": "payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/application/usecase/PaymentConfirmResultUseCase.java:88-89",
      "problem": "JavaTimeModule 을 import 하지 않고 fully qualified name 으로 인스턴스화. 동일 클래스 내 다른 import 패턴과 일관성 깨짐. ObjectMapper 를 use-case 생성자에서 직접 new — Spring 공유 ObjectMapper 와 직렬화 정책 분기 가능성.",
      "evidence": "PaymentConfirmResultUseCase.java line 88-89: `new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()`. PaymentEosIntegrationTest.java line 12 에서는 정상 import.",
      "suggestion": "JavaTimeModule import 추가 + Spring DI 검토 (KafkaMessageConverterConfig 의 공유 ObjectMapper 활용 가능성). 별도 인스턴스 의도라면 Javadoc 으로 이유 명시."
    }
  ],
  "previous_round_ref": null,
  "delta": null,
  "unstuck_suggestion": null
}
```
