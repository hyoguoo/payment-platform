# code-domain-2

**Topic**: pre-phase-4-hardening
**Round**: 2
**Persona**: Domain Expert

## Reasoning

돈·재고 정합성 축 1: FAILED 경로 qty=0 플레이스홀더 구조적 결함(Round 1 critical-1)은 `PaymentConfirmResultUseCase.handleFailed` 가 `FailureCompensationService.compensate(orderId, productId, order.getQuantity())` 를 순회하도록 재작성되고 레거시 `publish(orderId, List<Long>)` 오버로드가 포트에서 철거되어 해소됐다. payment dedupe TTL 1h ↔ Kafka retention 7d 비대칭(Round 1 critical-2) 도 `EventDedupeStoreRedisAdapter` 기본 P8D + `application.yml` 명시 override 로 정렬됐다. 사고 재구성 축 2: HTTP 홉 traceparent 누락(Round 1 major-3)은 양 서비스 `HttpOperatorImpl` 이 Boot auto-config `WebClient.Builder`/`RestClient.Builder` 주입으로 전환되어 `observationRegistry` 가 자동 적용되고, VT/@Async 3 개 지점에 `ContextExecutorService.wrap` + `MdcTaskDecorator` 가 삽입됐다. Redis DECR↔TX 원자성(major-4)·QuarantineCompensationHandler 종결 상태 가드(major-5)·Dedupe Redis flap 영구 누락(major-6) 모두 소스 상 실제 보상 경로가 주입된 것을 확인했다. minor 3 건(eventUuid 오배치·outbox headers_json·QUARANTINED 복구 공백)도 해당 태스크에서 구조적 해결(파라미터 제거·parseHeaders 제거·runbook placeholder 등록) 으로 닫혔다. 남은 관찰: T-D2 AFTER_COMMIT 리스너가 stock Kafka publish 실패 시 swallow 하는 경로는 TX 분리 목적상 의도된 설계(PLAN D1)이며 listener 주석에 향후 DLQ 도입 가드가 명시되어 있어 수용 가능한 잔여 리스크로 판단한다 — critical/major 없음.

## Domain risk checklist (Round 2 재판정)

- [x] FAILED 경로 실제 재고 복원 동작 — `PaymentConfirmResultUseCase.handleFailed:253-259` 가 PaymentOrder 별 `failureCompensationService.compensate(orderId, productId, order.getQuantity())` 호출. `StockRestoreEventPublisherPort` 에 `publish(orderId, List<Long>)` 시그니처 부재 — grep 결과 호출 0건. (R1 critical-1 CLOSED)
- [x] 멱등성 TTL ↔ 메시지 보관 윈도우 정렬 — `EventDedupeStoreRedisAdapter:38` 기본 `P8D` + `payment-service/src/main/resources/application.yml:72` 에 `ttl: P8D` 명시. product-service `StockRestoreUseCase.DEDUPE_TTL=Duration.ofDays(8)` 과 대칭. (R1 critical-2 CLOSED)
- [x] 다중 홉 traceId 연속성 — payment `HttpOperatorImpl:26-37`, pg `HttpOperatorImpl:27-38` 모두 `WebClient.Builder`/`RestClient.Builder` 주입(Boot 3.2+ auto-config observationRegistry 자동 적용). VT executor 경계 `OutboxWorker:54`, `PgOutboxImmediateWorker:71` `ContextExecutorService.wrap`. `@Async` 경계 `AsyncConfig:27` `MdcTaskDecorator` 적용. (R1 major-3 CLOSED)
- [x] Redis DECR ↔ confirm TX 원자성 — `OutboxAsyncConfirmService:90-98` `executeConfirmTxWithStockCompensation` 가 TX catch 블록에서 `stockCachePort.increment(productId, quantity)` 보상. 보상 실패 시 ERROR 로그 + 원본 예외 전파. (R1 major-4 CLOSED)
- [x] 상태 전이 불변식 (QUARANTINED 역전이 차단) — `QuarantineCompensationHandler.handle:50-54` 사전 `isTerminal()` no-op 가드 + `PaymentEvent.quarantine:158-162` 도메인 `IllegalStateException` 이중 가드. (R1 major-5 CLOSED)
- [x] 멱등성 Redis flap 영구 누락 차단 — `PaymentConfirmResultUseCase:114-137` two-phase lease(markWithLease shortTtl=5m → extendLease longTtl=8d), `remove` 실패 시 `PaymentConfirmDlqPublisher.publishDlq` 로 복구 경로 보장. `EventDedupeStore` 포트에 `markWithLease`/`extendLease`/`remove` 분리. (R1 major-6 CLOSED)
- [x] APPROVED 계약 amount/approvedAt non-null 보장 — `ConfirmedEventPayload.approved:40-45` `Objects.requireNonNull(amount)`/`requireNonNull(approvedAt)` 강제. 4개 발행 경로(`PgFinalConfirmationGate.buildApprovedPayload:228-232`, `PgVendorCallService.buildApprovedPayload:220-232`, `DuplicateApprovalHandler.buildApprovedPayload:293-298`, `FakePgGatewayStrategy:82`) 모두 Clock fallback 포함 non-null 주입. 수신측 `PaymentConfirmResultUseCase.handleApproved:169-202` 는 amount 불일치 시 `QUARANTINED(AMOUNT_MISMATCH)` 전이(ADR-15 역방향 방어선). (신규 축 1 PASS)
- [x] DuplicateApprovalHandler eventUuid 오배치 제거 — `DuplicateApprovalHandler.handleDuplicateApproval:131` 은 `(String orderId, BigDecimal payloadAmount)` 2-arg 로 축소. Toss/Nicepay 호출 라인(`TossPaymentGatewayStrategy:148-149`, `NicepayPaymentGatewayStrategy:162, 188`) 모두 2-arg 만 전달. (R1 minor-7 CLOSED)
- [x] outbox relay headers_json 잔재 제거 — `PgOutboxRelayService.relay:75` 는 `Map.of()` 를 명시 전달하고 주석으로 "observation-enabled 가 publish 시점에 traceparent 자동 주입" 을 명기. `parseHeaders` 메서드는 T-E4 에서 제거됨. (R1 minor-8 CLOSED)
- [x] QUARANTINED 홀딩 자산 운영자 복구 문서 — `docs/context/ARCHITECTURE.md:265-274` "Quarantine Recovery (운영자 복구 경로)" 섹션 + `docs/context/TODOS.md:132-154` `QUARANTINED-ADMIN-RECOVERY` 토픽 placeholder 등록(진입 API/대시보드/SLA 임계 명세 포함). (R1 minor-9 CLOSED)

## 도메인 관점 추가 검토 (Round 2 신규)

1. **T-D2 AFTER_COMMIT 리스너 Kafka publish 실패 swallow — 잔여 리스크, 수용 가능**
   - `StockEventPublishingListener.onStockCommitRequested:44-62` / `onStockRestoreRequested:68-84` 는 `RuntimeException` 을 catch 후 ERROR 로그만 남기고 삼킨다(주석 `action=SWALLOW(TX already committed)`).
   - `KafkaMessagePublisher.sendTyped:86-110` 은 10s 타임아웃 → `IllegalStateException` throw. 브로커 장시간 중단 시 stock.events.commit / stock.events.restore 이벤트 손실 가능.
   - 그러나: (a) PaymentEvent TX 는 이미 commit 되어 롤백 불가 — TX 역진입 시 상태 불변식 위반이 더 큰 리스크, (b) 리스너 주석이 "DLQ 또는 재시도 정책 도입 시 이 리스너에서 확장" 을 명시, (c) Kafka producer `acks=all`/`retries` + broker retention 7d 조합으로 네트워크 일시 장애는 producer-level 재시도가 흡수, (d) PLAN D1 의 명시적 설계 결정.
   - 판정: 잔여 minor, Round 1 재고 qty=0 영구 고립과 비교하면 창구가 훨씬 좁음(브로커 10s+ 장애 ∧ producer retry 소진 시에만). critical/major 아님.

2. **T-C3 lease TTL 5m vs TX timeout 5s — 충분한 여유**
   - `PaymentConfirmResultUseCase:93` `@Transactional(timeout = 5)` = 5 seconds.
   - `DEFAULT_LEASE_TTL = Duration.ofMinutes(5)` = 300 seconds. 60 배 마진.
   - DB commit + AFTER_COMMIT Kafka publish(10s send-timeout 상한) 까지 합쳐도 최악 15s 수준. lease 만료로 인한 경쟁 consumer 재진입 가능성 ε (5m 동안 동일 eventUuid 가 만료된 채 재컨슘될 확률은 Kafka partition 재분배·pod 재기동 같은 특이 상황으로 한정).
   - 판정: race window 없음, 수용.

3. **AFTER_COMMIT publish 실패 ↔ 재고 cache 잔여 — 이중 장애 시나리오**
   - APPROVED 경로에서 이미 Redis DECR 은 TX 전에 수행됨(OutboxAsyncConfirmService). commit 후 stockCommit Kafka publish 실패 시 Redis 는 차감된 채, product-service RDB 반영은 누락. 그러나 RDB 상 재고는 product-service 가 최종 권위이고 Redis 는 성능 캐시이므로, 다음 Reconciler 사이클 또는 캐시 TTL 만료로 재정렬. 돈 관점에서는 사용자 결제는 DONE 이고 재고는 "Redis 기준 차감 / RDB 기준 미차감" 이라 **오버셀 방향** 임계점은 Redis 기준. RDB 재고 복원은 stock 누락으로 줄어들지 않으므로 정합성은 결국 회복 가능.
   - 판정: 단독 critical 로 격상할 근거 부족. T-D2 리스너 swallow 의 파생 특성으로 #1 과 동일 카테고리 수용.

4. **역방향 amount 방어선 도착 전 payment_event 재조회 race**
   - `handleApproved` 는 `paymentEventRepository.findByOrderId(orderId)` 로 fresh load 후 amount 대조 → done(). 동일 orderId 로 두 메시지가 동시에 도달할 가능성은 Kafka partitioning(key=orderId) 으로 차단됨. lease 가 선행 dedupe 를 수행하므로 순차성 보장.
   - 판정: OK.

## Findings

Round 2 에서 identify 된 critical/major finding **없음**. minor 1 건(아래) 은 잔여 관찰로 기록.

### Finding R2-1 — AFTER_COMMIT 리스너 publish 실패 DLQ 미구현 (minor, 잔여)

- **checklist_item**: 보상 경로 실패 시 복구 가능성
- **location**: `payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/listener/StockEventPublishingListener.java:55-61, 77-83`
- **problem**: stock commit/restore Kafka publish 실패 시 ERROR 로그 후 swallow — DLQ 또는 outbox persist 보상 경로 없음. Kafka broker 장시간 중단 시 이벤트 손실 가능.
- **evidence**: `catch (RuntimeException e) { LogFmt.error(...); }` 블록 + 주석 "SWALLOW(TX already committed)". PLAN D1 및 리스너 주석에서 후속 태스크로 명시적 지연.
- **suggestion**: Phase 4 이후 별도 토픽에서 (a) payment-service 내 stock outbox 테이블 도입, (b) `KafkaTemplate` send-callback 에서 실패 시 outbox persist 보상, 또는 (c) product-service 자체 reconciliation 주기로 대응 중 택 1. 현 시점에서는 metric/alert 로 감시만 유지.

## JSON

```json
{
  "stage": "code",
  "persona": "domain-expert",
  "round": 2,
  "task_id": null,

  "decision": "pass",
  "reason_summary": "Round 1 critical 2건·major 4건·minor 3건이 모두 소스 상 구조적으로 해소됨. APPROVED amount/approvedAt 계약은 factory에서 non-null 강제되고 4개 발행 경로 모두 통과. 잔여 minor 1건(AFTER_COMMIT 리스너 publish swallow)은 PLAN D1의 명시적 설계 결정으로 수용.",

  "checklist": {
    "source": "_shared/checklists/code-ready.md#domain-risk",
    "items": [
      {
        "section": "domain risk",
        "item": "보상/취소 로직에 멱등성 가드 존재 (FAILED 재고 실 복원)",
        "status": "yes",
        "evidence": "PaymentConfirmResultUseCase.handleFailed:253-259가 FailureCompensationService.compensate(orderId, productId, order.getQuantity()) 순회 호출. StockRestoreEventPublisherPort에서 publish(orderId, List<Long>) 오버로드 철거 확인(grep 호출 0건)."
      },
      {
        "section": "domain risk",
        "item": "race window에 락/트랜잭션 격리 고려됨 (Redis DECR ↔ TX 원자성)",
        "status": "yes",
        "evidence": "OutboxAsyncConfirmService:90-98 executeConfirmTxWithStockCompensation이 TX catch 블록에서 stockCachePort.increment(productId, quantity) 보상. PaymentConfirmResultUseCase:114-137 two-phase lease + DLQ publish 보장."
      },
      {
        "section": "domain risk",
        "item": "상태 전이 불변식 위반 없음 (QUARANTINED 역전이 차단)",
        "status": "yes",
        "evidence": "QuarantineCompensationHandler.handle:50-54 isTerminal no-op 가드 + PaymentEvent.quarantine:158-162 IllegalStateException 이중 가드."
      },
      {
        "section": "domain risk",
        "item": "PG ALREADY_PROCESSED 계열 특수 응답이 정당성 검증을 거침",
        "status": "yes",
        "evidence": "TossPaymentGatewayStrategy.handleErrorResponse → DuplicateApprovalHandler.handleDuplicateApproval(orderId, amount) 위임, vendor 재조회 + amount 2자 대조 + AMOUNT_MISMATCH 시 QUARANTINED."
      },
      {
        "section": "domain risk",
        "item": "PII/paymentKey plaintext 로그 노출 없음",
        "status": "yes",
        "evidence": "LogFmt에 orderId/paymentKey는 있으나 벤더 PII(카드번호 등)는 없음. R1 대비 변경 없음."
      },
      {
        "section": "domain risk (추가)",
        "item": "사고 재구성을 위한 traceId가 다중 홉에 연속 전파됨",
        "status": "yes",
        "evidence": "payment HttpOperatorImpl:26-37, pg HttpOperatorImpl:27-38 모두 Boot auto-config Builder 주입(observationRegistry 자동). OutboxWorker:54 + PgOutboxImmediateWorker:71 ContextExecutorService.wrap. AsyncConfig:27 MdcTaskDecorator 적용."
      },
      {
        "section": "domain risk (추가)",
        "item": "멱등성 키 TTL이 Kafka retention·DLQ 재처리 주기와 정렬됨",
        "status": "yes",
        "evidence": "EventDedupeStoreRedisAdapter:38 기본 P8D + application.yml:72 ttl: P8D 명시. product-service StockRestoreUseCase.DEDUPE_TTL = Duration.ofDays(8)과 대칭."
      },
      {
        "section": "domain risk (추가)",
        "item": "APPROVED 이벤트 계약 amount/approvedAt non-null 보장",
        "status": "yes",
        "evidence": "ConfirmedEventPayload.approved:40-45 Objects.requireNonNull 강제. 4개 발행 경로(PgVendorCallService:220-232, PgFinalConfirmationGate:228-232, DuplicateApprovalHandler:293-298, FakePgGatewayStrategy:82) Clock fallback 포함 non-null."
      },
      {
        "section": "domain risk (추가)",
        "item": "QUARANTINED 홀딩 자산 복구 경로 문서화",
        "status": "yes",
        "evidence": "ARCHITECTURE.md:265-274 Quarantine Recovery 섹션 + TODOS.md:132-154 QUARANTINED-ADMIN-RECOVERY 토픽 placeholder 등록."
      }
    ],
    "total": 9,
    "passed": 9,
    "failed": 0,
    "not_applicable": 0
  },

  "scores": {
    "correctness": 0.92,
    "conventions": 0.90,
    "discipline": 0.90,
    "test-coverage": 0.85,
    "domain": 0.92,
    "mean": 0.898
  },

  "findings": [
    {
      "severity": "minor",
      "checklist_item": "보상 경로 실패 시 복구 가능성 (잔여)",
      "location": "payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/listener/StockEventPublishingListener.java:55-61, 77-83",
      "problem": "stock commit/restore Kafka publish 실패 시 ERROR 로그 후 swallow — DLQ/outbox 보상 경로 없음. Kafka broker 장시간 중단(producer retry 소진) 시 이벤트 손실 가능.",
      "evidence": "catch (RuntimeException e) { LogFmt.error(...); } 블록 + 주석 action=SWALLOW(TX already committed). PLAN D1 및 리스너 주석이 후속 태스크로 명시 지연.",
      "suggestion": "Phase 4 이후 별도 토픽에서 (a) payment-service stock outbox 테이블 도입, (b) KafkaTemplate send-callback 보상, (c) product-service reconciliation 주기 중 택 1. 현 시점에서는 metric/alert로 감시 유지."
    }
  ],

  "previous_round_ref": "review-domain-1.md",
  "delta": {
    "newly_passed": [
      "보상/취소 로직에 멱등성 가드 존재 (FAILED 재고 실 복원)",
      "race window에 락/트랜잭션 격리 고려됨 (Redis DECR ↔ TX 원자성)",
      "상태 전이 불변식 위반 없음 (QUARANTINED 역전이 차단)",
      "사고 재구성을 위한 traceId가 다중 홉에 연속 전파됨",
      "멱등성 키 TTL이 Kafka retention·DLQ 재처리 주기와 정렬됨",
      "APPROVED 이벤트 계약 amount/approvedAt non-null 보장",
      "QUARANTINED 홀딩 자산 복구 경로 문서화"
    ],
    "newly_failed": [],
    "still_failing": []
  },

  "unstuck_suggestion": null
}
```
