# plan-domain-2

**Topic**: MSA-TRANSITION
**Round**: 2 (D-1 해소 재판정)
**Persona**: Domain Expert

## Reasoning

Round 1 단일 major(D-1: FAILED 경로 `stock.restore` 보상 publisher 태스크 부재 → 재고 영구 잠금)가 Round 2에서 **T3-04b 신설**로 해소됐다. PLAN:952-965에 태스크가 신규 추가됐고, `tdd=true`·`domain_risk=true`·depends=[T2d-01, T1-11c]·테스트 2건(`whenFailed_ShouldEnqueueStockRestoreCompensation` + `whenFailed_IdempotentWhenCalledTwice`)·산출물 2건(`FailureCompensationService.java` + `StockRestoreEventPayload.java`)·목적 문단에 "같은 TX 내 INSERT" + "UUID v4 랜덤 생성(ADR-16 수락 기준)" 명시. T3-05 depends에 T3-04b가 추가돼(PLAN:975) 발행↔소비 체인이 닫혔다. 커버리지 표 ADR-04/16(PLAN:1189, 1201, 1161, 1162)에도 T3-04b가 편입됐고, 반환 지표(PLAN:1228)에서 domain_risk=true 43건·Round 2 증가분 주석·변경 로그 D-1 행(PLAN:1248) 모두 일관. Round 1에서 pass했던 나머지 6개 리스크(중복 승인 2자 대조, pg DB 부재 경로, business inbox amount, 재시도 안전성, FCG 불변, 감사 원자성)는 T2a-05/T1-11 분해 이후에도 테스트 키워드·불변식 hook·태스크 문단이 그대로 유지돼 regress 없음 확인. 남은 관찰 사항은 "같은 TX 원자성 assert 테스트 부재"와 "eventUUID/eventUuid 표기 불일치" 2건의 minor로, 돈 경로를 막지 않는다. **판정: pass**.

## Domain risk checklist

### D-1 해소 확인

| 요구 사항 | 충족 | 근거 |
|---|---|---|
| payment-service가 FAILED 전이 시 stock.restore 보상 이벤트를 payment-service outbox로 발행 | pass | PLAN:955 목적 문단 "payment_outbox에 topic=stock.events.restore, key=orderId, payload row를 같은 TX 내 INSERT" + PLAN:964 산출물 `FailureCompensationService.java` |
| UUID 멱등 키 포함 (ADR-16) | pass | PLAN:955 "eventUUID는 UUID v4 랜덤 생성(ADR-16 수락 기준)" + PLAN:965 `StockRestoreEventPayload.java` 필드에 `eventUUID` 포함 + PLAN:962 `whenFailed_IdempotentWhenCalledTwice` — 동일 orderId 2회 → row 1건 |
| tdd=true, domain_risk=true | pass | PLAN:956-957 |
| 테스트 스펙 2건 (whenFailed_ShouldEnqueueStockRestoreCompensation + whenFailed_IdempotentWhenCalledTwice) | pass | PLAN:961-962 — Round 1 제안 테스트명과 1:1 일치 |
| 산출물 FailureCompensationService.java + payload DTO | pass | PLAN:964-965 — `FailureCompensationService.java` + `StockRestoreEventPayload.java`(eventUUID, orderId, productId, qty) |
| T3-05 depends에 T3-04b 추가 | pass | PLAN:975 `depends: [T3-03, T3-04b]` — 발행↔소비 선후 체인 성립 |

### 기존 7개 리스크 regress 체크

| 리스크 | Round 1 매핑 | Round 2 매핑 | 상태 |
|---|---|---|---|
| R1 중복 승인 2자 대조 (pg DB 존재) | T2b-05 | T2b-05 (PLAN:721-739 변경 없음) | no-regress |
| R2 pg DB 부재 경로 amount 일치 검증 | T2b-05 | T2b-05 (PLAN:724 목적 문단 (2)절 유지 + PLAN:730-735 테스트 5건 유지) | no-regress |
| R3 business inbox amount 컬럼 + 3경로 규약 | T2a-04 + T2b-04 | T2a-04 (PLAN:550-558) + T2b-04 (PLAN:702-717) — 스키마·테스트 4건·`AmountConverter` 산출물 유지 | no-regress |
| R4 재시도 안전성 (available_at + DLQ + QUARANTINED) | T2b-01 + T2b-02 + T2a-05 | T2b-01 (PLAN:646-662) + T2b-02 (PLAN:666-680) + **T2a-05a/b/c 분해** (PLAN:562-606) — Publisher/Channel/Worker 3분할 후에도 `callVendor_WhenRetryableErrorAndAttemptNotExceeded_ShouldInsertRetryOutboxRow`·`retry_WhenAttemptExceeded_ShouldWriteDlqOutboxRow`·`dlq_consumer_WhenConsumerItself_ShouldBeDifferentBeanFromNormalConsumer`·`dlq_consumer_WhenAlreadyTerminal_ShouldBeNoOp` 4개 핵심 테스트 그대로. `outbox_publish_WhenImmediateAndPollingRace_ShouldEmitOnce` 불변식 11 테스트가 T2a-05c(PLAN:603)에 이식됨 | no-regress |
| R5 FCG 불변 (QUARANTINED on timeout, raw state) | T2b-03 + T1-13 + T4-01 | T2b-03 (PLAN:684-698) + T1-13 (PLAN:374-387) + T4-01 (PLAN:1040~) — 테스트 `fcg_WhenVendorTimesOut_ShouldQuarantine_NoRetry`·`process_WhenQuarantined_ShouldNotRollbackStockCacheImmediately` 유지 | no-regress |
| R6 감사 원자성 (AOP + 같은 TX 리스너) | T1-05 + T1-06 + T1-07 | T1-05 (PLAN:205-218) + T1-06 (PLAN:222-233) + T1-07 (PLAN:237-246) — `PaymentHistoryEventListenerTest#onPaymentStatusChange_InsertsHistoryBeforeCommit` 유지 | no-regress |
| R7 Strangler Fig 이중 발행 방지 | T1-18 | T1-18 (PLAN:465-474) — `@ConditionalOnProperty("payment.monolith.confirm.enabled")` + migrate-pending-outbox.sh 유지 | no-regress |

### T1-11 분해(T1-11a/b/c) 후 Outbox 4구성 파이프라인 돈 경로 검증

- T1-11a(PLAN:306-320) Publisher + RelayService → `KafkaMessagePublisher`가 `MessagePublisherPort.publish`의 유일 구현, Worker는 port 경유만 — KafkaTemplate 직접 호출 금지 명문화(PLAN:309)
- T1-11b(PLAN:324-333) Channel + EventHandler → `LinkedBlockingQueue<Long> capacity=1024`, offer 실패 시 Polling fallback(이벤트 드롭 금지)
- T1-11c(PLAN:337-350) ImmediateWorker + PollingWorker → `outbox_publish_WhenImmediateAndPollingRace_ShouldEmitOnce` 불변식 11 테스트 보존
- 분해 전후 돈 경로 불변(같은 TX Outbox INSERT → AFTER_COMMIT → Immediate/Polling 이중 소비 안전) 유지. Worker의 port 경유 강제는 오히려 "pg 이벤트와 payment 이벤트가 같은 KafkaTemplate을 공유해 크로스 토픽 오염"하는 risk를 차단한다.

### T2a-05 분해(T2a-05a/b/c) 후 DLQ 전용 consumer 경로 검증

- T2b-02 depends가 T2a-05c로 갱신(PLAN:672) — DLQ 전용 consumer가 Worker Lifecycle에 결속
- `dlq_consumer_WhenAlreadyTerminal_ShouldBeNoOp`(PLAN:676) 보존 → DLQ 메시지 재전달 시 이미 QUARANTINED/FAILED/APPROVED면 no-op로 흡수(불변식 6c)
- 재시도 → DLQ → QUARANTINED 전이 체인이 T2b-01 테스트 `retry_WhenAttemptExceeded_ShouldWriteDlqOutboxRow`(PLAN:659) + T2b-02 `dlq_consumer_WhenNormalMessage_ShouldQuarantine`(PLAN:675)로 양단 박제

### 신규 돈 경로 리스크 탐지 (T3-04b 관련)

1. **payload 필드 완결성**: `eventUUID·orderId·productId·qty` 4필드(PLAN:965) — 역차감에 필요한 최소 필드는 productId·qty, 멱등 dedupe 키는 eventUUID, 감사 상관키는 orderId. 모두 명시. consumer 측 T3-05 `StockRestoreUseCase#restore_DuplicateEventUuid_ShouldNoOp`이 eventUUID 키로 dedupe하고 재고 복원은 productId·qty로 수행하므로 필드 **의미 수준**에서 호환.
2. **dedupe 키 표기 일관성**: T3-04b payload 필드명 `eventUUID`(PLAN:961, 965) vs T3-01 `EventDedupeStore` 계약 `boolean recordIfAbsent(String eventUuid, ...)`(PLAN:887) — 대소문자 불일치는 JSON payload → DTO deserialize 시점에 해소되므로 돈 경로 차단 아님. 단 plan 문서 일관성 측면 **minor** 관찰.
3. **같은 TX INSERT 원자성 assert 부재**: 목적 문단(PLAN:955)은 "같은 TX 내 INSERT"를 명시하지만 테스트 메서드 2건(PLAN:961-962)은 (a) row 1건 INSERT와 (b) 중복 호출 시 row 1건만을 assert할 뿐, "FAILED 전이 TX가 롤백되면 outbox row도 함께 롤백"을 직접 assert하지 않는다. 호출지(T2d-01 `PaymentConfirmResultUseCase` 또는 T1-05 `PaymentTransactionCoordinator`)가 `@Transactional` 안에서 `FailureCompensationService`를 호출하는 한 Spring 기본 전파로 같은 TX에 편입되므로 돈 경로는 막힌다. 그러나 execute 단계에서 서비스 호출이 `REQUIRES_NEW` 또는 TX 밖에서 일어날 경우를 계약 테스트로 박제하지 않으면 후속 리팩터링에서 원자성이 깨질 수 있다 — **minor** (revise 블로커 아님, code 라운드에서 `enqueue_WhenCalledWithinFailedTransaction_ShouldRollbackWhenOuterTxRollsBack` 추가 권고).

## 도메인 관점 추가 검토

### 1. D-1 해소 강도 평가

Round 1에서 제안한 해소 방법(산출물 `PaymentFailureCompensationUseCase.java` + 테스트 2건)을 Planner가 **태스크 번호 T3-04b 독립 신설**로 반영. 클래스명은 `FailureCompensationService`로 조정됐으나 성격(application/service 계층, FAILED 전이 트리거, outbox row INSERT)은 동일. Round 1 제안 테스트명 `handleFailed_ShouldInsertStockRestoreOutboxRow_WithEventUuid` + `handleFailed_IdempotentWhenCalledTwice`와 Round 2 채택 테스트명 `whenFailed_ShouldEnqueueStockRestoreCompensation` + `whenFailed_IdempotentWhenCalledTwice`는 검증 의도(row INSERT + 멱등성) 동일. **재고 영구 잠금 리스크 해소 완료**.

### 2. Phase 1 이행 구간 보상 경로 일관성

PLAN:134 "Phase 1에서는 `stock.restore` 보상은 결제 서비스 내부 동기 호출 유지(`InternalProductAdapter` 승계). 이벤트화는 Phase 3과 동시"가 유지. T3-04b가 Phase 3에 위치(depends=[T2d-01, T1-11c])하여 이행 구간 공백이 닫혔다 — Phase 1/2 동안은 동기 호출, Phase 3 전환 시 이벤트 발행으로 전환. Round 1 D-1은 "이벤트화 전환 후 발행 측 누락" 지적이었고 T3-04b가 정확히 이 지점에 배치됐다.

### 3. Reconciler 백업 경로 확인 (T1-14)

PLAN:403 `scan_WhenQuarantinedPaymentExists_ShouldRollbackDecrForEach` — QUARANTINED만 복원. FAILED는 T1-14 범위 밖이나, 이제 T3-04b가 FAILED 시 `stock.restore` 이벤트 발행을 보장하므로 Reconciler 백업 없이도 돈 경로가 닫힌다. 발행 실패(outbox row INSERT 성공 but Kafka publish 실패) 시에도 OutboxWorker/PollingWorker(T1-11c)가 `processed_at IS NULL AND available_at<=NOW()` row를 계속 재발행하므로 at-least-once 보장.

### 4. ADR 커버리지 표 정합

| ADR | Round 2 태스크 | 비고 |
|---|---|---|
| ADR-04 | T1-04, T1-11a/b/c, T2a-05a/b/c, T2a-06, T2d-01, T3-04b (PLAN:1189) | T3-04b 추가됨 |
| ADR-16 | T0-02, T1-03, T3-03, T3-04, T3-04b, T3-05 (PLAN:1201) | T3-04b 추가됨 |
| 추적 테이블 ADR-16 행 | "publisher: T3-04b, consumer: T3-05"(PLAN:1161) | 발행·소비 양단 명시 |
| 추적 테이블 ADR-04 행 | "FAILED 보상 발행" + "T3-04b"(PLAN:1162) | 명시 추가 |

### 5. 기존 `domain_risk=true` 라벨 변화

Round 1 38건 → Round 2 43건(PLAN:1228). 증가분 5건은 T1-11a/b/c(Round 1 T1-11 분할 결과, +2) + T2a-05a/b/c(Round 1 T2a-05 분할 결과, +2) + T3-04b 신설(+1). 과도 라벨링 없음 — 분할된 태스크 모두 Outbox 파이프라인의 돈 경로 구성요소이며 T3-04b는 보상 발행 원자성 담당.

## Findings

- **[minor]** PLAN:961-962 T3-04b 테스트 메서드는 "row 1건 INSERT" + "멱등 1건"만 assert하고 "FAILED 전이 TX 롤백 시 outbox row도 롤백"(같은 TX 원자성)을 직접 assert하는 테스트가 없다. 목적 문단(PLAN:955)은 "같은 TX 내 INSERT"를 명시하므로 Spring 기본 TX 전파 하에서 돈 경로는 닫히나, 후속 리팩터링 방어를 위해 `enqueue_WhenOuterTransactionRollsBack_ShouldRollbackOutboxRow` 테스트 1건 추가 권장. **revise 블로커 아님**.

- **[minor]** T3-04b payload 필드명 `eventUUID`(PLAN:961, 965)와 T3-01 EventDedupeStore 계약 파라미터 `eventUuid`(PLAN:887) 대소문자 불일치. JSON deserialize 경계에서 해소되나 plan 문서의 필드 표기 일관성을 위해 한쪽으로 정렬 권장. **revise 블로커 아님**.

- **[n/a]** PII·보안은 본 토픽 비목표. Round 2 신규 태스크 T3-04b도 PII 처리 경계 변경 없음.

## JSON

```json
{
  "topic": "MSA-TRANSITION",
  "stage": "plan",
  "round": 2,
  "persona": "domain-expert",
  "artifact_ref": "docs/MSA-TRANSITION-PLAN.md",
  "previous_round_ref": "docs/rounds/msa-transition/plan-domain-1.md",
  "decision": "pass",
  "d1_resolution": {
    "status": "resolved",
    "task_id": "T3-04b",
    "task_line_range": "PLAN:952-965",
    "evidence": {
      "outbox_publisher_in_payment_service": "PLAN:955 — payment_outbox topic=stock.events.restore, key=orderId, same TX INSERT",
      "uuid_idempotency_key": "PLAN:955 eventUUID v4 + PLAN:965 StockRestoreEventPayload.eventUUID field",
      "tdd_true_and_domain_risk_true": "PLAN:956-957",
      "two_required_tests": "PLAN:961 whenFailed_ShouldEnqueueStockRestoreCompensation + PLAN:962 whenFailed_IdempotentWhenCalledTwice",
      "artifacts": "PLAN:964 FailureCompensationService.java + PLAN:965 StockRestoreEventPayload.java",
      "t3_05_depends_updated": "PLAN:975 depends=[T3-03, T3-04b]"
    }
  },
  "regression_check": {
    "R1_duplicate_approval_two_way": {"tasks": ["T2b-05"], "status": "no-regress"},
    "R2_pg_db_absent_amount": {"tasks": ["T2b-05"], "status": "no-regress"},
    "R3_business_inbox_amount": {"tasks": ["T2a-04", "T2b-04"], "status": "no-regress"},
    "R4_retry_safety_after_t2a05_split": {"tasks": ["T2b-01", "T2b-02", "T2a-05a", "T2a-05b", "T2a-05c"], "status": "no-regress", "note": "T2a-05 3분할 후에도 DLQ 전용 consumer·terminal no-op·immediate/polling race 테스트 전부 보존"},
    "R5_fcg_invariant": {"tasks": ["T2b-03", "T1-13", "T4-01"], "status": "no-regress"},
    "R6_audit_atomicity": {"tasks": ["T1-05", "T1-06", "T1-07"], "status": "no-regress"},
    "R7_strangler_dual_write": {"tasks": ["T1-18"], "status": "no-regress"}
  },
  "t1_11_split_integrity": {
    "status": "ok",
    "note": "T1-11a Publisher+Relay / T1-11b Channel+EventHandler / T1-11c Immediate+PollingWorker 3분할. Worker의 MessagePublisherPort 경유 강제(PLAN:309, 340)로 KafkaTemplate 직접 호출 차단. 불변식 11 immediate/polling race 테스트 T1-11c에 이식됨(PLAN:347)"
  },
  "t2a_05_split_integrity": {
    "status": "ok",
    "note": "T2a-05a/b/c 3분할 후에도 T2b-02 depends=T2a-05c 유지(PLAN:672). DLQ consumer가 Worker Lifecycle에 결속. retry→DLQ→QUARANTINED 체인 T2b-01/T2b-02 테스트로 양단 박제"
  },
  "new_money_path_risks": [],
  "findings": [
    {
      "severity": "minor",
      "domain_risk": "T3-04b 같은 TX 원자성 계약 테스트 부재",
      "section": "T3-04b (PLAN:952-965)",
      "line": 961,
      "description": "목적 문단은 '같은 TX 내 INSERT'를 명시하나 테스트 메서드 2건은 row 1건 INSERT + 멱등성만 assert. FAILED 전이 TX 롤백 시 outbox row도 롤백됨을 직접 assert하는 테스트 없음. Spring 기본 TX 전파로 돈 경로는 닫히나 후속 리팩터링 방어를 위해 enqueue_WhenOuterTransactionRollsBack_ShouldRollbackOutboxRow 1건 추가 권장. revise 블로커 아님."
    },
    {
      "severity": "minor",
      "domain_risk": "dedupe 키 표기 불일치 (eventUUID vs eventUuid)",
      "section": "T3-04b payload(PLAN:961, 965) vs T3-01 EventDedupeStore 계약(PLAN:887)",
      "line": 965,
      "description": "T3-04b payload 필드 eventUUID와 T3-01 EventDedupeStore.recordIfAbsent(String eventUuid, ...) 파라미터 eventUuid 대소문자 불일치. JSON deserialize 경계에서 해소되므로 돈 경로 차단 아님. plan 문서 일관성 차원 minor."
    },
    {
      "severity": "n/a",
      "domain_risk": "PII",
      "section": "Round 2 전체",
      "line": 0,
      "description": "PII·보안은 본 토픽 비목표. Round 2 신규 태스크(T3-04b 포함)에 PII 처리 경계 변경 없음."
    }
  ],
  "counts": {
    "critical": 0,
    "major": 0,
    "minor": 2,
    "n/a": 1
  },
  "notes": "D-1 해소=T3-04b 신설로 확인(PLAN:952-965), 테스트·산출물·depends 모두 계약 만족 / R1 no-regress (T2b-05 불변) / R2 no-regress (T2b-05 불변) / R3 no-regress (T2a-04+T2b-04 불변) / R4 no-regress (T2a-05 3분할 후에도 retry→DLQ→QUARANTINED 체인 박제) / R5 no-regress (T2b-03+T1-13+T4-01 불변) / R6 no-regress (T1-05/06/07 불변) / R7 no-regress (T1-18 불변). 신규 돈 경로 리스크 0건. T3-04b payload 4필드(eventUUID·orderId·productId·qty) 모두 명시되어 T3-05 consumer 역차감·dedupe·상관키 요구 충족. 남은 minor 2건(같은 TX 원자성 테스트 부재, eventUUID/eventUuid 표기) 모두 돈 경로 차단 아님.",
  "summary": "Round 1 major D-1(FAILED stock.restore 발행 누락) 해소 확인. T3-04b 태스크 신설로 payment-service outbox row INSERT·UUID 멱등·테스트 2건·산출물 2건·T3-05 depends 갱신 모두 계약 만족. 기존 7개 리스크 regress 0건. 돈 경로 7+1개 전부 태스크·테스트 수준 봉쇄. pass."
}
```
