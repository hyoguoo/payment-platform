# review-domain-1

**Topic**: PAYMENT-EOS-TRANSITION
**Round**: 1
**Persona**: Domain Expert

## Reasoning

PET-1~14 변경분을 6개 결제 도메인 축(상태 전이, 멱등성, 결정성, 발행 시맨틱, race window, 회복 비대칭)으로 코드와 교차 검증했다. DR-1/DR-3/DR-4/DR-5/DR-7 회귀 가드는 모두 **소스에서 보존 + PET-12 통합 5 시나리오 + PET-3/5/8 단위 회귀로 다중 보호**됨을 확인했다. SUT 수정 2건(JpaConfig @Primary + commandsConfirmProducerFactory 명시 등록)은 모두 의도된 격리 효과를 가져 별도 도메인 위험을 만들지 않는다. 다만 (1) `JdbcPaymentEventDedupeStore` 의 `received_at = Timestamp.from(Instant.now())` 시간 소스 직접 호출(PITFALLS #6 위반)과 (2) `@Transactional(timeout=5)` 의 PlatformTransactionManager 선택 정확성 + EOS 원자성 의미 미명시 2건이 도메인 관점 추가 finding으로 남는다.

## Domain risk checklist

| 항목 | 결과 | 근거 |
|---|---|---|
| 상태 전이 SSOT 보존 (`isCompensatableByFailureHandler`) | OK | `PaymentEventStatus.java:52-57` enum 값 변경 없음, exhaustive switch, QUARANTINED false |
| 멱등성 (idempotencyKey 결정성) | OK | `StockEventUuidDeriver.derive(orderId, productId, "stock-commit")` 보존, multi-product for-loop (`PaymentConfirmResultUseCase.java:195-212`) |
| 멱등성 (메시지 dedupe) | OK | `JdbcPaymentEventDedupeStore` INSERT IGNORE → affected rows 0/1 시맨틱 정확, `payment_event_dedupe` PK=event_uuid |
| 보상 호출 순서 (SCR L7 흡수) | OK | `handleFailed:265 → 268`, `handleQuarantined:287 → 289` 보상 먼저 → RDB 나중 보존 |
| D7 진입 가드 (QUARANTINED 늦은 APPROVED) | OK | `handle:105-111` 가드 + PET-12 #5 + `PaymentEventStatusEosGuardTest` |
| 위키 line 141 보장 (중복 시 발행 항상 진행) | OK | `handle:126-136` APPROVED 분기에서 `sendStockCommittedEvents` 호출, PET-12 #3 검증 |
| PII / 시각 표현 안전성 | partial | `JdbcPaymentEventDedupeStore:43` 시간 소스 직접 호출 — PITFALLS #6 |
| Deploy 순서 명시 (DR-4) | OK | `product-service/application.yml:42` 주석 + PITFALLS #23 + CONCERNS L1 |
| PG amount 양방향 방어 보존 | OK | `handleApproved:163-174` AMOUNT_MISMATCH 분기 보존, redis 보상 미수행 격리 정책 유지 |
| race window 신규 도입 | low | `@Transactional(timeout=5)` 와 EOS tx 의 상호작용 — 후술 finding RD1-2 |
| 회복 비대칭 (EOS abort 시 Redis 보상) | OK | CONCERNS L-5 명시 등재 |
| 다중 인스턴스 fencing | OK | CONCERNS L-3 / L-6 명시 등재, TC-13-FOLLOW-1 후속 |
| Kafka tx coordinator 의존 약화 | OK | CONCERNS L-1 등재, TC-13-FOLLOW-3 후속 |
| TTL 정리 부재 | OK | CONCERNS L-2 등재, TC-13-FOLLOW-2 후속 |

## 도메인 관점 추가 검토

### 1. `JdbcPaymentEventDedupeStore` 의 `received_at` 시간 소스 직접 호출 (minor)

`JdbcPaymentEventDedupeStore.java:43` 에서 `Timestamp.from(Instant.now())` 로 직접 호출한다. 같은 use case의 caller `PaymentConfirmResultUseCase.handle:118` 에서는 `localDateTimeProvider.nowInstant().plus(STOCK_COMMITTED_TTL)` 로 주입된 Provider 를 통해 `expires_at` 을 계산하는데, store 어댑터 내부의 `received_at` 만 fall through 되었다. `LocalDateTimeProvider.java:22` 가 명시적으로 "Instant.now() 직접 호출 대신 이 메서드를 통해 시간 소스를 주입받는다" 라고 가이드한다 (PITFALLS #6 SSOT).

**도메인 영향**: `received_at` 은 결제 결과 메시지 처리 시각 영구 트레일 — 감사/사고 재구성/dedupe TTL 만료 검증 시 사용된다. fixed clock 으로 위조 불가하면 단위 테스트로 `received_at + P8D = expires_at` 동치를 단정할 수 없다. silent loss 위험은 없으나 시간 결정성 SSOT 일관성 결함.

### 2. `@Transactional(timeout=5)` 와 EOS tx 의 PlatformTransactionManager 선택 (major)

`PaymentConfirmResultUseCase.handle:98` 의 `@Transactional(timeout = 5)` 가 한정자 없이 선언되어 `@Primary JpaTransactionManager` (`JpaConfig.java:30`) 를 선택한다. Kafka container 가 시작한 EOS tx (`KafkaTransactionManager`) 와는 **다른 PlatformTransactionManager** 이므로 두 tx 는 별개 트랜잭션으로 진행된다.

실제 동작: container의 KafkaTransactionManager 가 EOS tx를 시작 → consume() 진입 → handle() 진입에서 JPA TX 시작 (PROPAGATION_REQUIRED, JpaTransactionManager) → `stockCommittedKafkaTemplate.send()` 가 `TransactionSynchronizationManager` 의 active KafkaResourceHolder 를 발견해 producer tx buffer 에 적재 → handle() 종료에서 JPA commit → consume() 종료에서 Kafka tx commit (offset + producer 양쪽).

**도메인 위험**: 이 모델은 "true 2PC atomic" 이 **아니다**. RDB commit 직후 + Kafka tx commit 직전 crash 시 RDB 는 박혔는데 producer 미가시화 + offset 미커밋. 재배달 시 D5 dedupe 0 row → 비즈니스 skip + `sendStockCommittedEvents` 진행 (위키 line 141 룰이 이를 정확히 보호). 즉, **실안전성은 위키 line 141 룰이 SSOT 이며 EOS atomicity 자체가 아니다.** 

PET-12 #1 (정상 commit) + #2 (abort invisibility) 가 GREEN 이므로 정상/abort 경로는 검증됨. 그러나 운영자가 "EOS 트랜잭션이 RDB + Kafka 원자 commit 을 보장한다"고 오해하면 위키 line 141 룰 폐기 시 silent loss 가 부활한다. 위키 line 141 룰 영구화 + EOS 정합 모델 한계 명시가 필요하다 (CONFIRM-FLOW.md §5 또는 CONCERNS L-1 보강).

다른 옵션: `@Transactional("kafkaTransactionManager")` 한정자 명시 또는 `ChainedKafkaTransactionManager` 도입 검토 — 단, 본 PR 범위 밖이라 후속 토픽 권장.

### 3. multi-partition EOS atomicity 미검증 (minor)

`PaymentEosIntegrationTest` line 88 `@EmbeddedKafka(partitions = 1)` — multi-product 시나리오 #4 의 2 productId 메시지가 같은 단일 파티션으로 발행된다. 실 운영에서는 productId 가 partition key (`PaymentConfirmResultUseCase.java:209`) 라 multi-partition 분산이 정상이다. Kafka EOS 는 multi-partition atomic 보장하나, 통합 테스트가 1 파티션이라 multi-partition abort 시 모든 파티션이 abort 되는지 직접 검증되지는 않는다.

**도메인 영향**: low — Kafka EOS 자체의 multi-partition atomicity 는 broker 책임이고 PET-12 의 1 파티션 검증으로 application 측 로직 (멱등 마킹 + send loop) 정합성은 확인됨. 운영 환경에서 broker EOS 동작 가정만 추가 명시 권장.

### 4. `payment_event_dedupe.order_id` 컬럼 타입 mismatch 검토 (n/a)

`V2__payment_event_dedupe.sql:11` 의 `order_id BIGINT`, `JdbcPaymentEventDedupeStore.java:38` 시그니처 `long orderId`. `PaymentConfirmResultUseCase.java:121` 에서 `paymentEvent.getId()` 전달 — payment_event 의 PK 인 `id (BIGINT)` 와 매핑 일치. payment 의 도메인 orderId (String, "order-eos1-...") 가 아닌 RDB PK 를 사용하고 있어 도메인 의미상 약간 혼란스러우나 PK 인덱싱 효율 + JOIN 용이성으로 합당. 사고 시 운영자가 `event_uuid` 로 검색하면 되므로 도메인 위험 없음.

## Findings

| ID | Severity | Location | Issue | Suggestion |
|---|---|---|---|---|
| RD1-1 | minor | `payment-service/.../infrastructure/dedupe/JdbcPaymentEventDedupeStore.java:43` | `received_at` 컬럼이 `Timestamp.from(Instant.now())` 직접 호출 — PITFALLS #6 (시간 소스 추상화 SSOT) 위반. LocalDateTimeProvider 가 명시적으로 직접 호출 금지 가이드. caller 의 `expiresAt` 은 Provider 주입, store 의 `receivedAt` 만 fall through | 생성자에 `LocalDateTimeProvider` 주입 → `Timestamp.from(provider.nowInstant())` 로 교체. 단위 테스트에서 fixed clock 으로 `received_at + P8D = expires_at` 동치 검증 가능해짐 |
| RD1-2 | major | `payment-service/.../application/usecase/PaymentConfirmResultUseCase.java:98` | `@Transactional(timeout=5)` 한정자 없음 → `@Primary JpaTransactionManager` 선택. KafkaTransactionManager(EOS) 와 별개 TX 로 동작. 실 안전성은 "위키 line 141 룰 (중복 시 발행 항상 진행)" 이 SSOT 이지 EOS atomic 자체가 아님. 운영자/후속 변경자가 EOS atomicity 로 오해 시 line 141 룰 폐기 → silent loss 부활 위험 | (a) CONFIRM-FLOW.md §5 또는 CONCERNS L-1 에 "EOS atomicity 는 두 TM 협업이며 위키 line 141 룰이 안전성 SSOT" 명시 보강. (b) 후속 토픽: `@Transactional("kafkaTransactionManager")` 한정자 명시 또는 ChainedKafkaTransactionManager 도입 검토 |
| RD1-3 | minor | `payment-service/.../integration/PaymentEosIntegrationTest.java:88` | `@EmbeddedKafka(partitions = 1)` — multi-product (시나리오 #4) 가 단일 파티션으로만 검증. 실 운영은 productId partition key 분산. multi-partition abort atomicity 는 broker 책임이라 application 측 검증으로는 직접 입증되지 않음 | CONFIRM-FLOW.md §16 "범위 밖 알려진 한계" 에 "multi-partition EOS atomicity 는 Kafka broker 책임 가정" 한 줄 추가 |

## JSON

```json
{
  "round": 1,
  "persona": "domain-expert",
  "topic": "PAYMENT-EOS-TRANSITION",
  "stage": "review",
  "decision": "revise",
  "findings": [
    {
      "id": "RD1-1",
      "severity": "minor",
      "location": "payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/dedupe/JdbcPaymentEventDedupeStore.java:43",
      "issue": "received_at 컬럼 INSERT 시 Timestamp.from(Instant.now()) 직접 호출 — PITFALLS #6 (LocalDateTimeProvider SSOT) 위반",
      "suggestion": "LocalDateTimeProvider 주입 → Timestamp.from(provider.nowInstant()) 로 교체"
    },
    {
      "id": "RD1-2",
      "severity": "major",
      "location": "payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/application/usecase/PaymentConfirmResultUseCase.java:98",
      "issue": "@Transactional(timeout=5) 한정자 미명시 → @Primary JpaTransactionManager 선택. Kafka EOS tx 와 별개 PlatformTransactionManager 로 동작. 실 안전성은 위키 line 141 룰(중복 시 발행 항상 진행)에 의존, EOS atomicity 자체가 아님. 후속 변경자가 line 141 룰을 폐기하면 silent loss 부활",
      "suggestion": "CONFIRM-FLOW.md §5 또는 CONCERNS L-1 에 'EOS atomicity 는 두 TM 협업이며 위키 line 141 룰이 안전성 SSOT' 명시 보강. (후속) @Transactional(\"kafkaTransactionManager\") 한정자 명시 또는 ChainedKafkaTransactionManager 도입 토픽화"
    },
    {
      "id": "RD1-3",
      "severity": "minor",
      "location": "payment-service/src/test/java/com/hyoguoo/paymentplatform/payment/integration/PaymentEosIntegrationTest.java:88",
      "issue": "@EmbeddedKafka(partitions=1) — multi-product 시나리오 #4 가 1 파티션으로만 검증되어 multi-partition EOS atomicity 는 application 측에서 직접 검증되지 않음",
      "suggestion": "CONFIRM-FLOW.md §16 '범위 밖 알려진 한계' 에 multi-partition EOS atomicity 는 broker 책임 가정 한 줄 추가"
    }
  ],
  "dr_regression_check": [
    {"dr_id": "DR-1", "status": "protected", "evidence": "StockEventUuidDeriver.derive 보존 (PaymentConfirmResultUseCase.java:195-212) + PET-12 #4 + PaymentEosIntegrationTest.java:339-348 distinct key 단정"},
    {"dr_id": "DR-3", "status": "protected", "evidence": "PaymentEventStatus.isCompensatableByFailureHandler enum 값 변경 없음 (PaymentEventStatus.java:52-57) + handle() 진입 가드 (PaymentConfirmResultUseCase.java:105-111) + PaymentEventStatusEosGuardTest 3건 + PET-12 #5"},
    {"dr_id": "DR-4", "status": "protected", "evidence": "product-service/application.yml:42-43 isolation.level=read_committed 적용 + PITFALLS #23 deploy 순서 명시 + PET-12 #2 abort invisibility 검증 (interval 200ms × 5 = 1s DLQ 단정)"},
    {"dr_id": "DR-5", "status": "protected", "evidence": "JdbcPaymentEventDedupeStore INSERT IGNORE 시맨틱 (0/1 affected rows) + handle:126-136 비즈니스 skip / 발행 항상 진행 룰 구현 + JdbcPaymentEventDedupeStoreTest 4건 + PET-12 #3"},
    {"dr_id": "DR-7", "status": "protected", "evidence": "handleFailed:265→268 + handleQuarantined:287→289 보상 먼저 → RDB 나중 호출 순서 보존 + PaymentConfirmResultUseCaseHandleFailedTest InOrder 단정"}
  ],
  "auxiliary_scores": {
    "state_transition_consistency": "pass",
    "idempotency_layers": "pass",
    "publisher_atomicity_semantics": "partial (RD1-2)",
    "time_source_ssot": "partial (RD1-1)",
    "pg_failure_modes_coverage": "pass",
    "compensation_order_invariants": "pass",
    "isolation_level_alignment": "pass",
    "multi_instance_fencing": "deferred (CONCERNS L-3/L-6)"
  }
}
```
