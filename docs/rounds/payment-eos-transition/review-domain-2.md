# review-domain-2

**Topic**: PAYMENT-EOS-TRANSITION
**Round**: 2
**Persona**: Domain Expert

## Reasoning

R1 finding 3건과 fix 과정에서 노출된 hidden bug 3건을 도메인 안전성(상태 전이, 멱등성, 보상 호출 순서, 결정성, race window, 회복 비대칭) 관점에서 교차 검증했다. RD1-1 / RD1-2 / RD1-3 모두 코드/문서에서 흡수 확인 — 특히 RD1-2 의 "위키 line 141 룰이 정합 SSOT" 명제가 CONFIRM-FLOW §5 / CONCERNS L-1 / TODOS TC-13-FOLLOW-6 3개소에 명시되어 후속 변경자 오독 위험 차단됐다. SCR L7 봉인(보상 → RDB 순서)과 D7/D5/D8 가드, 멀티 인스턴스/TTL 한계 명시 모두 보존. Hidden bug 3건은 (1) DB NOT NULL 도메인 무결성 회복 (2) API contract 안정화 (3) checkout 멱등성 race 강화로, 결제 안전성을 깨뜨리지 않고 오히려 개선했다. 새 critical 0건, 새 major 0건.

## Domain risk checklist

| 항목 | 결과 | 근거 |
|---|---|---|
| 상태 전이 SSOT 보존 (`isCompensatableByFailureHandler`) | OK | R1 동일 (PaymentEventStatus.java enum 미변경) + R1 fix 무영향 |
| 멱등성 (idempotencyKey 결정성, DR-1) | OK | `PaymentConfirmResultUseCase.java:196-213` for-loop + StockEventUuidDeriver.derive 보존 |
| 멱등성 (메시지 dedupe, D5) | OK | `JdbcPaymentEventDedupeStore.java:50` INSERT IGNORE + `received_at` 이 LocalDateTimeProvider 주입으로 SSOT 일치 (RD1-1 fix) |
| 보상 호출 순서 (SCR L7 봉인) | OK | `PaymentConfirmResultUseCase.java:264-269` handleFailed + `:286-289` handleQuarantined 보상 먼저 → RDB 나중 보존, Javadoc §47-48/§256-257 에 봉인 근거 명시 유지 |
| D7 진입 가드 (QUARANTINED 늦은 APPROVED) | OK | `PaymentConfirmResultUseCase.java:106-112` 가드 보존 |
| 위키 line 141 룰 (중복 시 발행 항상 진행) | OK | `PaymentConfirmResultUseCase.java:127-137` APPROVED 분기 sendStockCommittedEvents 호출 보존 + CONFIRM-FLOW §5 EOS atomicity SSOT 절(line 159-164)에 "정합성 SSOT 는 EOS atomicity 자체가 아니라 위키 line 141 룰" 명시 (RD1-2 fix) |
| PII / 시각 표현 안전성 | OK | `JdbcPaymentEventDedupeStore.java:50` `Timestamp.from(localDateTimeProvider.nowInstant())` — PITFALLS #6 준수 (RD1-1 fix) |
| Deploy 순서 명시 (DR-4) | OK | product-service `application.yml` isolation.level=read_committed + PITFALLS #23 + CONCERNS L-1 보강 |
| PG amount 양방향 방어 보존 | OK | `PaymentConfirmResultUseCase.java:164-175` AMOUNT_MISMATCH 격리 분기 보존 |
| race window 신규 도입 | low | `PaymentConfirmResultUseCase.handle` @Transactional(timeout=5) 의 두 TM 별개 동작은 RD1-2 SSOT 등재로 운영 오독 차단, TC-13-FOLLOW-6 후속 등재 |
| 회복 비대칭 (EOS abort 시 Redis 보상) | OK | CONCERNS L-5 그대로 등재 |
| 다중 인스턴스 fencing | OK | CONCERNS L-3 / L-6 등재, TC-13-FOLLOW-1 후속 |
| Kafka tx coordinator 의존 약화 | OK | CONCERNS L-1 + EOS atomicity SSOT 절 보강, TC-13-FOLLOW-3 후속 |
| TTL 정리 부재 | OK | CONCERNS L-2 등재, TC-13-FOLLOW-2 후속 |
| multi-partition EOS atomicity 검증 | OK | `PaymentEosIntegrationTest.java:84` `partitions=2` 로 보강 (RD1-3 fix) |
| checkout 멱등성 race (신규 fix) | OK | `IdempotencyStoreRedisAdapter` IN_PROGRESS marker + loser polling 으로 동시 creator 호출 1회 보장 — multi-thread 동시 confirm 시 PG 이중 호출 위험 차단 (hidden bug fix) |
| API contract 안정성 (신규 fix) | OK | `CheckoutResult` @JsonProperty("duplicate") 로 직렬화/역직렬화 key 일관 (hidden bug fix) |
| 도메인 무결성 (신규 fix) | OK | `payment_event.gateway_type NOT NULL` 제약에 테스트 데이터 정합 — PaymentEvent 도메인이 PG 라우팅 필수 결정값 |

## 도메인 관점 추가 검토

### 1. R1 흡수 — RD1-2 (major) EOS atomicity SSOT 명시 검증

bef3d033 커밋이 3개 문서에 위키 line 141 룰의 SSOT 지위를 명시:

- **CONFIRM-FLOW.md §5** (line 159-164): "EOS atomicity SSOT (RD1-2 명시)" 절 신설. `@Transactional(timeout=5)` 가 `@Primary JpaTransactionManager` 선택 → 두 TM 별개 → at-least-once 재배달 가능 → "정합성 SSOT 는 EOS atomicity 그 자체가 아니라 위키 line 141 룰" 단정. TC-13-FOLLOW-1 후속 토픽 참조 명시.
- **CONCERNS.md L-1** (line 80-85): "EOS atomicity 정합 SSOT (RD1-2 명시)" 절 추가. 동일 논지를 다른 시각(가용성 약화)에서 보강. "EOS 는 정상 경로 중복 발행 최소화 최적화이며, crash 내성은 위키 line 141 + product-service dedupe 조합" 단정.
- **TODOS.md TC-13-FOLLOW-6** (line 72-77): qualifier 명시 또는 ChainedKafkaTransactionManager 도입을 별 토픽 후속으로 승격. 트리거 조건("운영 환경에서 at-least-once 허용 불가 수준의 중복 발생 시") 명시.

→ R1 의 핵심 우려("후속 변경자가 line 141 룰을 폐기하면 silent loss 부활")는 **3개 문서에 SSOT 지위 못박기 + 후속 토픽 ID 등재** 로 완전 차단. 텍스트 흡수에 그치지 않고 SSOT 단정 + 후속 트리거 조건까지 명시한 점이 만족스럽다. **resolved**.

### 2. R1 흡수 — RD1-1 (minor) LocalDateTimeProvider 주입 검증

8a60b79e 커밋이 `JdbcPaymentEventDedupeStore`:
- `@RequiredArgsConstructor` 제거 + 명시 생성자에 `LocalDateTimeProvider` 추가 주입
- `markIfAbsent` 안의 `Timestamp.from(Instant.now())` → `Timestamp.from(localDateTimeProvider.nowInstant())` 교체
- Javadoc 에 "PITFALLS #6 (Instant.now() 직접 호출 금지) 를 준수" 명시

→ `received_at + P8D = expires_at` 동치를 fixed clock 으로 단위 검증 가능. `JdbcPaymentEventDedupeStoreTest` 가 새 생성자 시그니처에 맞춰 갱신됨. **resolved**.

### 3. R1 흡수 — RD1-3 (minor) multi-partition EOS atomicity 보강 검증

8a60b79e 커밋이 `PaymentEosIntegrationTest.java:84` `partitions = 1 → 2` 변경. multi-product 시나리오 #4 의 productId 키 분산이 실제로 2 파티션에 흩어지고, 시나리오 #2 abort invisibility 가 multi-partition 환경에서도 검증됨. 다만 partition 수가 2 라 multi-partition broker 동작은 여전히 broker 책임 가정 영역이나, application 측 코드가 multi-partition 분산 환경에서 깨지지 않음은 직접 검증됨. **acceptable resolved** — broker 자체 atomicity 는 본 PR 범위 밖이고 등록된 부담 (CONFIRM-FLOW §16 한계 명시 또는 후속) 은 acceptable deferred.

### 4. Hidden bug 1 — `gateway_type=null` DB NOT NULL 위반 fix 평가

c6ce6368 fix:
- `PaymentControllerTest.PAYMENT_EVENT_INSERT_SQL` 에 `gateway_type` 컬럼 추가 + `PaymentGatewayType.TOSS.name()` 명시
- `checkout_Success` 테스트의 `buildCheckoutRequest()` 에 `.gatewayType(PaymentGatewayType.TOSS)` 추가
- BigDecimal scale 비교를 `isEqualByComparingTo` 로 교체

**도메인 평가**: V1__payment_schema.sql:14 `gateway_type VARCHAR(50) NOT NULL` 은 도메인 불변식 — `PaymentEvent.gatewayType` 이 항상 PG 라우팅 결정값으로 필요. production 코드 (PaymentCreateUseCase, CheckoutCommand) 에 null 진입점이 없음을 grep 으로 확인. fix 는 **테스트 데이터 결함을 올바르게 메우는 방향**이지 production 도메인 모델 변경이 아니라 도메인 무결성에 영향 없음. **clean fix**.

### 5. Hidden bug 2 — `CheckoutResult` Jackson is-prefix fix 평가

c6ce6368 fix: `@JsonProperty("duplicate")` 로 직렬화/역직렬화 key 명시.

**API contract 영향**: Lombok `@Getter` 가 `isDuplicate()` getter 생성 → Jackson 기본 직렬화 시 "is" prefix 제거 → key="duplicate". Builder setter 는 `isDuplicate`. 기존 코드는 round-trip 깨짐 — Redis 저장 JSON 에서 역직렬화 시 isDuplicate field 가 항상 false 로 회복되어 **멱등성 중복 응답이 first call 응답으로 잘못 표시될 가능성**. fix 가 정확하게 이 경로 봉인. 

다만 위험 흔적: 다른 boolean field (다른 DTO/Entity) 도 동일 패턴 잠재 — 본 PR 범위 밖이므로 후속 grep audit 후속 토픽 등재 권장 가능하나, 본 라운드 finding 으로 잡지 않는다 (R1 이 잡지 못한 새 critical/major 가 아님). **clean fix, latent generalized risk 인지**.

### 6. Hidden bug 3 — `IdempotencyStoreRedisAdapter` race condition fix 평가

c6ce6368 fix: GET → SETNX → fallback GET 의 2단계 → GET → SETNX(IN_PROGRESS marker) → creator/polling 의 3단계로 재작성.

**race window 분석**:
- **이전 모델 위험**: 동시 GET=null → 모든 thread 가 `creator.get()` 호출 → PG/외부 API 다중 호출 (체크아웃 멱등성 깨짐). 결제 도메인에서 creator 가 외부 effect (PG 호출, DB INSERT, 락 점유) 를 일으키면 silent 이중 처리 위험.
- **새 모델**: winner 1명만 creator 호출, loser 들은 50ms polling × 40회 (2s max) 후 winner 의 완성 JSON 을 hit 반환. IN_PROGRESS TTL=10s 가 creator 정상 처리 마진.

**잔여 도메인 위험 (acceptable)**:
- creator 실행 시간 > polling MAX (2s) 시 loser timeout `IllegalStateException` 전파 → 클라이언트가 retry 시 winner 가 완성 값을 박은 후라면 hit 반환. acceptable degraded path.
- creator 가 throw 시 IN_PROGRESS marker 가 IN_PROGRESS_TTL(10s) 동안 남아 loser 들도 모두 timeout → 클라이언트 retry 시 IN_PROGRESS 만료 후 새 winner. acceptable, dead-lock 없음.
- Race remnant: winner 가 IN_PROGRESS marker set 직전에 다른 thread 가 GET → null → 자기도 SETNX 시도 → winner-vs-winner 1명만 선점. 정합.

**SCR/EOS 영향**: 본 fix 는 checkout 진입 멱등성(`IdempotencyStoreRedisAdapter`) 의 race 차단이며, confirm 경로(SCR 보상 순서 / EOS dedupe) 와는 봉인된 layer. 두 layer 간 간섭 없음.

다만 본 PR(EOS 전환) 범위 밖 변경이 commit 에 혼입된 점은 향후 git blame / revert 부담을 늘리나, 이미 fix + 23/23 PASS 이므로 도메인 위험 없음. **clean fix, scope creep 인지 — 그러나 finding 으로 격상하지 않는다 (Critic 영역)**.

### 7. SCR 회복 layer 봉인 보존 확인

8a60b79e + c6ce6368 fix 가 SCR 봉인 (보상 → RDB 순서 / dedup token / DefaultErrorHandler 위임) 을 깨뜨리지 않음:
- `PaymentConfirmResultUseCase.java:264-269` handleFailed: `compensateAtomic` 먼저 → `markPaymentAsFail` 나중. fix 무영향.
- `PaymentConfirmResultUseCase.java:286-289` handleQuarantined: 동일 순서 보존.
- Javadoc §47-48, §51-53, §256-257 에 봉인 근거 ("crash 후 재배달 시 compensateAtomic 의 dedup token 이 ALREADY_DONE 반환 → markPaymentAsFail 재진행 → 정합 보장") 그대로 유지.
- `KafkaErrorHandlerConfig` DefaultErrorHandler + FixedBackOff(1s×5) + DLQ 위임 모델 변경 없음.
- `StockCompensationRecoveryIntegrationTest` 5/5 PASS — SCR 회복 시나리오 회귀 무. (실제 fix 에서 backoff 200ms × 5 = 1s 단축 등록 — DLQ 검증 시간만 줄이고 시나리오 의미 보존).

## Findings

| ID | Severity | Location | Issue | Suggestion |
|---|---|---|---|---|

(없음 — R1 finding 3건 모두 resolved + 새 critical/major 0건)

## JSON

```json
{
  "round": 2,
  "persona": "domain-expert",
  "topic": "PAYMENT-EOS-TRANSITION",
  "stage": "review",
  "decision": "pass",
  "r1_resolution": [
    {
      "id": "RD1-1",
      "resolved": true,
      "evidence": "8a60b79e: JdbcPaymentEventDedupeStore 생성자에 LocalDateTimeProvider 주입 + line 50 Timestamp.from(localDateTimeProvider.nowInstant()) 교체 + Javadoc 'PITFALLS #6 준수' 명시. JdbcPaymentEventDedupeStoreTest 갱신 4/4 PASS."
    },
    {
      "id": "RD1-2",
      "resolved": true,
      "evidence": "bef3d033: (1) CONFIRM-FLOW.md §5 line 159-164 'EOS atomicity SSOT (RD1-2 명시)' 절 신설 — '정합성 SSOT 는 EOS atomicity 그 자체가 아니라 위키 line 141 룰' 단정. (2) CONCERNS.md L-1 line 80-85 EOS atomicity 정합 SSOT 절 추가. (3) TODOS.md TC-13-FOLLOW-6 후속 토픽 등재. 후속 변경자 오독 차단 + 트리거 조건 명시 완료."
    },
    {
      "id": "RD1-3",
      "resolved": true,
      "evidence": "8a60b79e: PaymentEosIntegrationTest.java:84 partitions=1→2 변경. multi-product 시나리오 #4 productId 키 분산 + 시나리오 #2 abort invisibility 가 multi-partition 환경에서 직접 검증됨. broker 자체 atomicity 는 본 PR 범위 밖 acceptable deferred."
    }
  ],
  "dr_regression_check_r2": [
    {
      "dr_id": "DR-1",
      "status": "protected",
      "evidence": "PaymentConfirmResultUseCase.java:196-213 sendStockCommittedEvents for-loop + StockEventUuidDeriver.derive 보존. PaymentEosIntegrationTest 시나리오 #4 distinct key 단정 + partitions=2 multi-partition 분산 검증."
    },
    {
      "dr_id": "DR-3",
      "status": "protected",
      "evidence": "PaymentEventStatus.isCompensatableByFailureHandler enum 값 변경 없음 + handle:106-112 D7 가드 + PaymentEosIntegrationTest 시나리오 #5 QUARANTINED 늦은 APPROVED noop 검증. R1 fix 무영향."
    },
    {
      "dr_id": "DR-4",
      "status": "protected",
      "evidence": "product-service/application.yml isolation.level=read_committed 보존. PITFALLS #23 + CONCERNS L-1 보강. CONFIRM-FLOW §16 PET-12 시나리오 #2 abort invisibility 검증 (backoff 200ms × 5 = 1s DLQ 단정)."
    },
    {
      "dr_id": "DR-5",
      "status": "protected",
      "evidence": "JdbcPaymentEventDedupeStore.java:24,50 INSERT IGNORE 시맨틱 (0/1 affected rows) 보존 + PaymentConfirmResultUseCase.java:127-137 '0 row 시 비즈니스 skip + APPROVED 발행 항상 진행' 위키 line 141 룰 구현 + CONFIRM-FLOW §5 EOS atomicity SSOT 절에서 line 141 룰의 SSOT 지위 명시."
    },
    {
      "dr_id": "DR-7",
      "status": "protected",
      "evidence": "PaymentConfirmResultUseCase.java:264-269 handleFailed compensateAtomic → markPaymentAsFail 보존 + :286-289 handleQuarantined 보상 → 격리 핸들러 순서 보존. Javadoc §47-48/§51-53/§256-257 봉인 근거 텍스트 유지. StockCompensationRecoveryIntegrationTest 5/5 PASS."
    }
  ],
  "hidden_bug_assessment": [
    {
      "bug": "gatewayType=null DB NOT NULL 위반",
      "fix_quality": "clean",
      "domain_impact": "V1 schema gateway_type VARCHAR(50) NOT NULL 은 PaymentEvent 도메인의 PG 라우팅 필수 결정값 — production 진입점 (PaymentCreateUseCase / CheckoutCommand) 은 항상 PaymentGatewayType 명시. fix 는 테스트 데이터 결함을 메우는 방향이지 도메인 모델 변경 아님. 도메인 무결성 영향 없음."
    },
    {
      "bug": "CheckoutResult Jackson is-prefix 직렬화 불일치",
      "fix_quality": "clean",
      "domain_impact": "@JsonProperty('duplicate') 추가로 직렬화/역직렬화 key 일관. 이전 코드는 Redis JSON round-trip 시 isDuplicate field 가 항상 false 로 회복 → 멱등성 중복 응답이 first call 응답으로 잘못 표시 잠재 — fix 가 정확히 봉인. 다른 boolean field 동일 패턴 잠재 (별 토픽 audit 권장, 본 라운드 finding 아님)."
    },
    {
      "bug": "IdempotencyStoreRedisAdapter 동시 GET null race",
      "fix_quality": "clean",
      "domain_impact": "동시 creator 호출 → PG/외부 API 다중 호출 silent 이중 처리 위험을 IN_PROGRESS marker + 50ms × 40 polling 으로 봉인. winner 1명만 creator 실행. creator 예외 시 IN_PROGRESS TTL 10s 후 자연 회복, dead-lock 없음. SCR/EOS 봉인 layer 와 무간섭. 본 PR 범위 밖 변경 혼입은 scope creep 인지하나 23/23 PASS 라 도메인 위험 없음."
    }
  ],
  "scr_seal_preservation": {
    "compensation_before_rdb_order": "protected",
    "evidence": "PaymentConfirmResultUseCase.java:264-269 handleFailed + :286-289 handleQuarantined 보상 먼저 → RDB 나중 보존. Javadoc §47-48/§51-53/§256-257 봉인 근거 유지. fix 무영향. StockCompensationRecoveryIntegrationTest 5/5 PASS."
  },
  "findings": [],
  "auxiliary_scores": {
    "state_transition_consistency": "pass",
    "idempotency_layers": "pass (checkout race fix + dedupe time-source SSOT 정렬)",
    "publisher_atomicity_semantics": "pass (RD1-2 SSOT 3개소 명시)",
    "time_source_ssot": "pass (RD1-1 fix)",
    "pg_failure_modes_coverage": "pass",
    "compensation_order_invariants": "pass",
    "isolation_level_alignment": "pass",
    "multi_instance_fencing": "deferred (CONCERNS L-3/L-6, TC-13-FOLLOW-1 후속)",
    "multi_partition_application_coverage": "pass (RD1-3 fix: partitions=2)",
    "api_contract_stability": "pass (CheckoutResult Jackson fix)",
    "checkout_idempotency_race_window": "pass (IdempotencyStoreRedisAdapter race fix)"
  }
}
```
