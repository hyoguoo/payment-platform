# plan-domain-1

**Topic**: MSA-TRANSITION
**Round**: 1 (plan 전면 재작성 후 재판정 — 이전 시도 rate-limit로 중단)
**Persona**: Domain Expert

## Reasoning

plan은 discuss Round 5 pass에서 확정된 7개 돈 경로 리스크 중 **6개는 구체 태스크·테스트·산출물 수준으로 매핑됐으나**, 1개(FAILED 경로에서 `stock.restore` 보상 이벤트를 실제로 **발행하는 주체**)가 태스크로 누락됐다. T2d-01 목적 문단이 "FAILED → FAILED + stock.restore 보상"을 한 줄 서술하지만, (a) 해당 태스크의 테스트 메서드 목록에 보상 발행 검증이 없고, (b) 산출물 목록에 `StockRestoreEventPublisher`/outbox row INSERT 코드가 없으며, (c) Phase 3 태스크(T3-05)는 consumer dedupe만 다루고 발행 publisher 태스크는 별도로 존재하지 않는다. Phase 1 원칙(PLAN:134)은 "이벤트화는 Phase 3과 동시"라고 하나 Phase 3의 태스크 목록은 발행 측을 설계하지 않는다. 이는 **결제 FAILED 시 재고가 영원히 잠기는 경로**로 직결되는 plan 구멍이므로 major. 나머지 6개 리스크는 태스크 번호까지 명시적으로 매핑됐고 불변식·테스트 keyword가 runtime 관철 장치로 걸려 있다. **판정: revise** — major 1건 해소만 요구.

## Domain risk checklist

| 리스크 | 태스크 매핑 | 판정 | 근거 |
|---|---|---|---|
| 1. 중복 승인 응답 2자 amount 대조 (ADR-05 pg DB 존재 경로) | T2b-05 | **pass** | PLAN:680-697. 테스트 `pg_duplicate_approval_WhenPgDbExists_WhenAmountMatch_ShouldReemitStoredStatus`·`pg_duplicate_approval_WhenPgDbExists_WhenAmountMismatch_ShouldQuarantine`·`NicepayStrategy_WhenCode2201_ShouldDelegateToDuplicateHandler` 모두 명시. Toss `ALREADY_PROCESSED_PAYMENT` + NicePay `2201` 대칭화 산출물(`TossPaymentGatewayStrategy`·`NicepayPaymentGatewayStrategy` 분기) 포함. |
| 2. pg DB 부재 경로 amount 일치 검증 + 불일치 QUARANTINED+AMOUNT_MISMATCH (Round 4 M1) | T2b-05 | **pass** | PLAN:681 목적 문단 "(2) pg DB 레코드 부재 시(ADR-05 보강 6번): 벤더 재조회 amount == command payload amount 검증 → 일치 시 APPROVED+운영 알림(관측만), 불일치 시 QUARANTINED+AMOUNT_MISMATCH". 테스트 `pg_duplicate_approval_WhenPgDbAbsent_WhenAmountMatch_ShouldAlertAndApprove` · `pg_duplicate_approval_WhenPgDbAbsent_WhenAmountMismatch_ShouldQuarantine` · `pg_duplicate_approval_WhenVendorRetrievalFails_ShouldQuarantine` 명시. topic.md:792의 6번 절차와 정확히 일치. |
| 3. business inbox `amount` 컬럼 + 3경로 저장 시점 규약 (Round 4 M2) | T2a-04, T2b-04 | **pass** | PLAN:528-536에서 `pg_inbox`에 `amount BIGINT NOT NULL`(원화 최소 단위 정수, payload BigDecimal → DB BIGINT 변환 규약: scale=0, 음수·소수 거부) 명시. T2b-04(PLAN:659-675)에서 3경로 저장 규약 (a)(b)(c) 모두 테스트 메서드로 구현(`storeInbox_WhenNoneToInProgress_ShouldRecordPayloadAmount` / `storeInbox_WhenApproved_ShouldPassTwoWayAmountCheck` / `storeInbox_WhenApproved_WhenAmountMismatch_ShouldQuarantine` / `storeInbox_WhenBigDecimalScaleNotZero_ShouldReject`). discuss Round 5 minor(BigDecimal↔BIGINT 변환 규약)까지 흡수. topic.md:893 스키마 및 수락 기준 (ix)(:911)와 일관. |
| 4. 보상 dedupe (UUID 키 + consumer dedupe 테이블) | T3-05 (consumer), **발행 측 부재** | **major** | T3-05(PLAN:910-927)는 `stock.restore` **consumer** 쪽 UUID dedupe + TTL(Kafka retention+1일)만 다룬다. 그러나 **발행 측**(payment-service가 FAILED 시 `stock.restore` 이벤트를 `payment_outbox`에 row INSERT + 이벤트 UUID 부여 + 페이로드 구성)이 태스크로 존재하지 않는다. T2d-01 목적 문단(PLAN:764) 한 줄 서술이 유일하며, 동 태스크 테스트 메서드 `consume_WhenFailed_ShouldTransitionToFailed`는 상태 전이만 검증, 보상 이벤트 발행 검증 없음. 산출물에도 Publisher 없음. Phase 1 원칙(PLAN:134) "이벤트화는 Phase 3과 동시" 서술이 있으나 Phase 3 태스크 목록 어디에도 payment-service 측 `StockRestoreEventPublisher`가 산출물로 없다. **돈 경로 직결**: FAILED 결제에서 재고 복원 이벤트가 발행되지 않으면 상품 서비스 재고는 영구히 복원되지 않는다(Reconciler가 대체 보상해줄 수 있는지는 plan에서 불명확 — T1-14 Reconciler는 QUARANTINED DECR 복원만 담당하며 FAILED 경로 복원은 범위 밖). |
| 5. 재시도 안전성 (ADR-30 — available_at 지연 + DLQ 전용 consumer + QUARANTINED 전이) | T2b-01, T2b-02, T2a-05 | **pass** | T2b-01(PLAN:602-618) `callVendor_WhenRetryableErrorAndAttemptNotExceeded_ShouldInsertRetryOutboxRow`·`retry_WhenAttemptExceeded_ShouldWriteDlqOutboxRow` + RetryPolicy 상수 명시. T2b-02(PLAN:622-636) `dlq_consumer_WhenConsumerItself_ShouldBeDifferentBeanFromNormalConsumer`로 물리 분리 검증 + `dlq_consumer_WhenAlreadyTerminal_ShouldBeNoOp`로 중복 흡수(불변식 6c). T2a-05(PLAN:542-561) 4구성 파이프라인 pg-service 독립 복제. topic.md ADR-30 수락 기준 5항 모두 태스크 테스트에 반영. |
| 6. FCG 불변 (ADR-15 × ADR-21 — 재시도 소진 후 1회 최종 확인 + QUARANTINED 격리 불변 + raw state) | T2b-03, T1-13, T4-01 | **pass** | T2b-03(PLAN:640-654) `fcg_WhenVendorTimesOut_ShouldQuarantine_NoRetry`·`fcg_WhenVendor5xx_ShouldQuarantine`·목적 "재시도 래핑 금지, FCG 불변" 명시. T1-13(Phase 1 한정 payment-service 내부 FCG) `process_WhenFcgPgCallTimesOut_ShouldQuarantine` + `process_RetryExhausted_CallsFcgOnce` + `process_WhenQuarantined_ShouldNotRollbackStockCacheImmediately`(불변식 7). T4-01 `fcg-pg-timeout.sh` 장애 주입 scenario 명시. Phase 2 이후 FCG 주체가 pg-service로 이관되며 payment-service는 FCG 존재를 모르는 구조(T2c-02 PgStatusPort 삭제 계약 테스트로 박제). |
| 7. 감사 원자성 (ADR-13 — `@PublishDomainEvent` AOP + 같은 TX 리스너) | T1-05, T1-06, T1-07 | **pass** | T1-05(PLAN:206-219) `PaymentHistoryEventListenerTest#onPaymentStatusChange_InsertsHistoryBeforeCommit` — BEFORE_COMMIT 단계 insert 1회 검증. T1-06(PLAN:223-234) `@PublishDomainEvent`·`@PaymentStatusChange` + Aspect 결제 서비스 복제(cross-service 공유 금지). T1-07(PLAN:238-247) payment_history DDL 결제 서비스 DB 잔류 확정(ADR-13 대안 a). topic.md:798-808 대안 a 그대로 구현. |

## 부가 점검

### A. `domain_risk=true` 38건 과도 라벨링 검사

PLAN:1168 나열된 38개 태스크를 돈 경로·상태 전이·멱등성·관측성 4축으로 분류:

| 태스크 | 돈·상태·멱등·관측 연결 | 판정 |
|---|---|---|
| T0-02 (Idempotency Redis 이관) | 멱등성 저장소 horizontal 스케일, 중복 confirm 방어선 | **정당** |
| T0-Gate | Redis SETNX 원자성·Kafka 토픽 파티션 수 검증 (불변식 6b) | **정당** |
| T1-04~T1-13 | 도메인 상태 전이·TX 경계·감사·재고 캐시·FCG — 모두 돈 경로 | **정당** |
| T1-14~T1-17 | Reconciler·warmup·관측 메트릭 — 재고 불일치·stall 감지 | **정당** |
| T1-18 (Gateway 라우팅 교체) | 모놀리스 confirm 경로 이중 발행 방지(dual-write 차단) | **정당 (경계선이지만 Strangler Fig 이중 발행 방지는 돈 경로)** |
| T1-Gate, T2a-Gate, T2b-Gate, T2c-Gate, Phase-2-Gate, T3-Gate, T4-Gate, T5-Gate | E2E 정합성 검증 | **정당** |
| T2a-04~T2a-06 | inbox 5상태·Outbox·consumer dedupe | **정당** |
| T2b-01~T2b-05 | 벤더 호출 재시도·DLQ·FCG·amount 저장 규약·중복 승인 방어 | **정당** |
| T2c-01, T2c-02 | 스위치·잔존 코드 삭제 — Kafka only 불변 관철 | **정당** |
| T2d-01 | ConfirmedEventConsumer — 상태 전이 진입점 | **정당** |
| T3-04, T3-05 | StockCommitConsumer·StockRestoreConsumer dedupe | **정당** |
| T4-01 | Toxiproxy 8종 — 최종 정합성 복원 | **정당** |

과도 라벨링 0건. 38건 전부가 돈 경로·상태 전이·멱등성·관측성 중 하나에 실제로 연결된다.

### B. `amount` 저장 시점 규약 3경로 Phase 2 태스크 반영 검증

- (a) NONE→IN_PROGRESS 전이 시 payload amount 기록 → T2b-04 테스트 `storeInbox_WhenNoneToInProgress_ShouldRecordPayloadAmount` ✓
- (b) IN_PROGRESS→APPROVED 전이 시 벤더 재조회 amount == inbox.amount 일치 검증 후 저장 → T2b-04 테스트 `storeInbox_WhenApproved_ShouldPassTwoWayAmountCheck` + `storeInbox_WhenApproved_WhenAmountMismatch_ShouldQuarantine` ✓
- (c) pg DB 부재 경로 NONE→APPROVED 직접 전이 시 벤더 재조회 amount == payload amount 검증 후 저장 → T2b-04 목적 "(c) ... 검증 통과값만 기록"으로 명시되나 **테스트 메서드에서 (c) 경로 전용 검증이 T2b-05로 분산**되어 있다. T2b-05의 `pg_duplicate_approval_WhenPgDbAbsent_WhenAmountMatch_ShouldAlertAndApprove`가 (c) 경로를 다루지만, **"inbox에 검증 통과 값만 기록"을 직접 assert하는 테스트 메서드**는 T2b-04에도 T2b-05에도 명시적으로 없다. (c) 경로의 저장 규약 검증은 T2b-05 테스트가 APPROVED 전이 결과를 본다는 점에서 **간접적으로 커버**되나, scale=0 검증(`storeInbox_WhenBigDecimalScaleNotZero_ShouldReject`)이 (a)(b) 전이만 직접 assert하므로 (c) 경로에서 `longValueExact()` 예외가 올라가는지 확인되지 않는다. **결론**: 돈 경로는 T2b-05의 APPROVED 결과 검증으로 봉쇄되므로 돈 사고 리스크 아님. plan 단계 minor 수준 흡수 가능(T2b-04 또는 T2b-05에 `storeInbox_WhenPgDbAbsentPath_ShouldRecordValidatedAmount` 한 메서드 추가로 해소).

## 도메인 관점 추가 검토

### 1. major — FAILED 경로 `stock.restore` 발행 publisher 태스크 부재

**증거**:
- PLAN:764 T2d-01 목적 문단: "FAILED → FAILED + stock.restore 보상"
- PLAN:771 테스트 메서드: `consume_WhenFailed_ShouldTransitionToFailed` — 상태 전이만 assert, 보상 이벤트 발행 assert 없음
- PLAN:775-777 산출물: `ConfirmedEventConsumer.java` + `PaymentConfirmResultUseCase.java` 둘뿐, `StockRestoreEventPublisher`·outbox INSERT 코드 없음
- PLAN:134 Phase 1 원칙: "`stock.restore` 보상은 결제 서비스 내부 동기 호출 유지(`InternalProductAdapter` 승계). 이벤트화는 Phase 3과 동시"
- PLAN:823-825 Phase 3 목적: "`stock.restore` 보상 이벤트화 + consumer dedupe"
- PLAN:910-927 T3-05 산출물: `StockRestoreConsumer.java` + `JdbcEventDedupeStore.java` + `V2__add_event_dedupe_table.sql` — **consumer만**
- **payment-service 측 `stock.restore` publisher 신설 태스크 전무**

**돈 경로 영향**:
at-least-once 전제에서 payment FAILED 전이 시 재고 복원 이벤트가 **발행조차 되지 않으면** 소비 측 dedupe는 의미가 없다. 상품 서비스 Redis/RDB 재고는 결제 진입 시 Lua DECR된 상태 그대로 **영구 잠김** → 다른 고객이 같은 상품을 구매 불가 + Reconciler는 FAILED 결제의 DECR 복원을 범위 밖으로 둠(T1-14는 QUARANTINED만 복원, PLAN:378-386 `scan_WhenQuarantinedPaymentExists_ShouldRollbackDecrForEach`). 즉 "결제 FAILED → 재고 영구 잠금 → 매출 손실"이라는 돈 경로가 현 plan에서 닫히지 않는다.

**해소 방법** (plan 수정 범위):
- Phase 3 에 신규 태스크(가칭 T3-04b) 또는 T2d-01 확장:
  - 산출물: `payment-service/src/main/java/.../payment/application/usecase/PaymentFailureCompensationUseCase.java` (FAILED 전이 시 `payment_outbox`에 `topic=stock.events.restore`, `key=orderId`, payload에 eventUUID·productId·qty 삽입)
  - 테스트 메서드: `handleFailed_ShouldInsertStockRestoreOutboxRow_WithEventUuid` + `handleFailed_IdempotentWhenCalledTwice`
- 대안: Phase 1 원칙을 "Phase 1에서도 `stock.restore` outbox 발행만 도입하고 소비는 InternalProductAdapter 동기 경로 유지"로 뒤집어 T1 단계에 묶을 수도 있음. 이 경우 T1-xx 신규 태스크 필요.

본 major는 **돈 경로 구멍**이지 단순 문서 누락이 아니다. plan 단계에서 태스크가 없으면 execute 단계에서 구현자가 추론으로 채워 넣거나 건너뛸 수 있어, **FAILED 결제의 재고 복원이 체계적으로 구현되지 않는 리스크**를 만든다.

### 2. minor — T2b-04 (c) 경로 저장 규약 직접 assert 테스트 없음

위 "부가 점검 B" 참조. T2b-05가 APPROVED 결과를 보는 것으로 간접 커버되고 돈 경로는 막히나, inbox 쓰기 규약의 대칭성을 위해 `storeInbox_WhenPgDbAbsentPath_ShouldRecordValidatedAmount` 한 메서드 추가를 권장. **revise 블로커 아님**.

### 3. 기존 결정과의 충돌 여부 재확인

- Q7/Q8/Q9 토픽·파이프라인·재시도 상한 소유권: T2d-02(토픽 네이밍 확정) + T2b-01(pg-service consumer가 `attempt` 소유) + T2a-05(4구성 파이프라인) 모두 불변
- ADR-02/21 불변(payment-service가 벤더·FCG·amount 대조를 모름): T1-01 `PaymentGatewayPort` (getStatus 메서드 없음, PLAN:148) + T1-Gate `PgStatusPort` 부재 계약 테스트 + T2c-02 삭제 확정 — 세 지점 모두 runtime 관철 장치 보유
- §7 크래시 매트릭스: 행 #8(pg-service 크래시·FCG 최종 확인) → T2b-03. 행 #13(PaymentConfirmConsumer 크래시) → T2a-06. 행 #14(DLQ consumer 크래시·terminal 체크) → T2b-02 `dlq_consumer_WhenAlreadyTerminal_ShouldBeNoOp`. 세 경로 모두 plan 테스트로 박제
- §8 관측성 (알림 4종): T2d-02 산출물에 "DLQ 유입률>0, future_pending_count>N 지속, oldest_pending_age_seconds>300s, invariant 불일치" 4종 모두 명시. T1-16에 `payment.outbox.pending_age_seconds` + `payment.stock_cache.divergence_count` 메트릭 태스크 별도 분리

### 4. discuss Round 5 minor(BigDecimal↔BIGINT 변환 규약) 흡수 확인

PLAN:529 T2a-04 스키마 행에 "`amount BIGINT NOT NULL`(원화 최소 단위 정수, payload BigDecimal → DB BIGINT 변환 규약: scale=0, 음수·소수 거부)" 명시 + T2b-04 테스트 `storeInbox_WhenBigDecimalScaleNotZero_ShouldReject`(`BigDecimal.longValueExact()` 사용) 명시. Round 5 minor 해소 확인.

## Findings

- **[major]** PLAN T2d-01(PLAN:761-777) 목적 문단에 "FAILED → FAILED + stock.restore 보상" 한 줄이 있으나 (1) 테스트 메서드에 보상 발행 검증 없음, (2) 산출물에 publisher 없음, (3) T3-05(PLAN:910-927)는 consumer dedupe만 다루고 payment-service 측 `stock.restore` 발행 태스크가 Phase 1·Phase 3 어디에도 없다. FAILED 결제 시 재고 복원 이벤트가 발행되지 않으면 상품 서비스 재고가 영구 잠겨 매출 손실로 직결. Reconciler(T1-14)는 QUARANTINED DECR 복원만 담당하므로 FAILED 경로 백업도 없다. **해소 요구**: Phase 3에 `StockRestoreEventPublisher` 신설 태스크 추가 또는 T2d-01을 확장해 outbox INSERT 경로 + eventUUID 부여 + 테스트(`handleFailed_ShouldInsertStockRestoreOutboxRow_WithEventUuid`·`handleFailed_IdempotentWhenCalledTwice`) 명시.

- **[minor]** PLAN T2b-04(PLAN:659-675) 목적 문단은 (a)(b)(c) 3경로 저장 규약을 전부 서술하나 테스트 메서드 4개가 (a)(b) + scale 검증만 직접 assert하고 (c) pg DB 부재 경로의 "inbox에 검증 통과 값만 기록"은 T2b-05 APPROVED 결과 검증으로 간접 커버. 돈 경로는 닫혀 있으나 대칭성 확보를 위해 `storeInbox_WhenPgDbAbsentPath_ShouldRecordValidatedAmount` 테스트 메서드 한 건 추가 권장. **revise 블로커 아님**(major 해소 시 함께 흡수하면 충분).

- **[n/a]** PII·보안은 본 토픽 비목표(§1-3). plan 태스크에 PII 처리 경계 변경 없음.

## JSON

```json
{
  "topic": "MSA-TRANSITION",
  "stage": "plan",
  "round": 1,
  "persona": "domain-expert",
  "artifact_ref": "docs/MSA-TRANSITION-PLAN.md",
  "previous_round_ref": "docs/rounds/msa-transition/discuss-domain-5.md",
  "decision": "revise",
  "money_path_risk_mapping": {
    "R1_duplicate_approval_two_way_amount_check": {"tasks": ["T2b-05"], "status": "mapped"},
    "R2_pg_db_absent_amount_mismatch_quarantine": {"tasks": ["T2b-05"], "status": "mapped"},
    "R3_business_inbox_amount_column_and_three_path_storage": {"tasks": ["T2a-04", "T2b-04"], "status": "mapped"},
    "R4_compensation_dedupe_uuid_consumer_table": {"tasks": ["T3-05"], "status": "partial_publisher_missing"},
    "R5_retry_safety_available_at_dlq_consumer_quarantine": {"tasks": ["T2b-01", "T2b-02", "T2a-05"], "status": "mapped"},
    "R6_fcg_invariant_raw_state_quarantine_on_timeout": {"tasks": ["T2b-03", "T1-13", "T4-01"], "status": "mapped"},
    "R7_audit_atomicity_aop_same_tx_listener": {"tasks": ["T1-05", "T1-06", "T1-07"], "status": "mapped"}
  },
  "findings": [
    {
      "severity": "major",
      "domain_risk": "보상 이벤트 발행 누락 → FAILED 결제 재고 영구 잠금",
      "section": "T2d-01 (PLAN:761-777) + Phase 3 (PLAN:823-925)",
      "line": 764,
      "description": "payment-service FAILED 전이 시 stock.restore 이벤트를 outbox로 발행하는 태스크가 plan에 없다. T2d-01은 목적 문단 한 줄만 있고 테스트·산출물 미반영. T3-05는 consumer dedupe만 다룸. FAILED 결제의 재고 영구 잠금 리스크. Reconciler(T1-14)는 QUARANTINED만 복원하므로 백업 없음. 해소: 신규 StockRestoreEventPublisher 태스크 신설 또는 T2d-01 확장(outbox INSERT + eventUUID 부여 + 보상 발행 테스트 2건)."
    },
    {
      "severity": "minor",
      "domain_risk": "inbox amount (c) 경로 저장 규약 직접 assert 테스트 부재",
      "section": "T2b-04 (PLAN:659-675)",
      "line": 667,
      "description": "(a)(b) + scale 검증 테스트는 있으나 pg DB 부재 경로 (c)에서 'inbox에 검증 통과 값만 기록'을 직접 assert하는 테스트 없음. T2b-05의 APPROVED 결과 검증으로 돈 경로는 간접 커버되므로 revise 블로커 아님. 대칭성 확보를 위해 storeInbox_WhenPgDbAbsentPath_ShouldRecordValidatedAmount 한 건 추가 권장."
    },
    {
      "severity": "n/a",
      "domain_risk": "PII",
      "section": "§1-3 비목표",
      "line": 520,
      "description": "PII·보안은 본 토픽 비목표. plan 태스크에 PII 처리 경계 변경 없음."
    }
  ],
  "counts": {
    "critical": 0,
    "major": 1,
    "minor": 1,
    "n/a": 1
  },
  "domain_risk_label_audit": {
    "claimed_count": 38,
    "justified_count": 38,
    "over_labeled_tasks": []
  },
  "notes": "R1=T2b-05 / R2=T2b-05 / R3=T2a-04+T2b-04 / R4=T3-05(consumer only, publisher task missing — major) / R5=T2b-01+T2b-02+T2a-05 / R6=T2b-03+T1-13+T4-01 / R7=T1-05+T1-06+T1-07. 7개 리스크 중 6개 pass, 1개(보상 발행 측) major. 38개 domain_risk 라벨 과도 분류 0건. discuss Round 5 minor(BigDecimal↔BIGINT 변환) T2a-04/T2b-04에 흡수 확인.",
  "summary": "돈 경로 7개 중 6개는 태스크·테스트·산출물 수준으로 매핑 완료. FAILED 결제 재고 복원 이벤트 발행 태스크 1개 누락 — 재고 영구 잠금 리스크. revise."
}
```
