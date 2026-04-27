# 기능별 플로우 코드 분석 + 문서 정합성 검증 보고서

> 작성: 2026-04-27 (MSA-TRANSITION + PRE-PHASE-4-HARDENING 봉인 직후)
> 갱신: 2026-04-27 — 재고 모델 정리(restore 폐기 + Redis 보상 + warmup 제거 + redis-stock 단독화) 4 커밋 후 baseline 재정리. SELF-LOOP 진입 직전 상태.
> 목적: 실제 코드를 trace 해 각 기능의 흐름을 정리하고, `docs/context/` 문서들과의 정합성을 검증한다.
> 후속: 본 보고서의 미해결 항목을 SELF-LOOP 사이클로 갱신.

---

## 0. 직전 정리 회고 (재고 모델)

이번 stock 정리에서 한 4 커밋:

| 커밋 | 핵심 변경 |
|---|---|
| `b94461d1` | payment-service `RedisConfig` + `StockRedisConfig` 신설 — redis-dedupe(default) + redis-stock 명시 wiring (D18 결함 해소) |
| `72e636bc` | payment-service: restore 발행 경로 + warmup + Reconciler stock 부분 + Divergence 일체 제거. handleFailed/handleQuarantined/D12 가드 에 Redis INCR 보상 신설 |
| `399b81b6` | product-service: redis 의존 + restore consumer/endpoint + StockSnapshotPublisher + setStock + product_event_dedupe 일체 제거 |
| `250e6d72` | scripts/seed-stock.sh 신설 + compose-up 자동 호출. 영구 문서(ARCHITECTURE/CONFIRM-FLOW-*/PAYMENT-FLOW/PITFALLS/TODOS/CONVENTIONS) 일괄 갱신. FLOW-ANALYSIS D4/D8/D11/D18 RESOLVED |

**현 모델**: redis-stock = payment-service 단독 선차감 캐시(SoT 미러), product RDB = 진짜 SoT (APPROVED 시만 누적 차감), stock.events.restore 토픽 폐기. 부팅 직후 1회 `seed-stock.sh` 가 mysql-product → redis-stock 으로 동일 수치 시드.

테스트: 전 모듈 570 PASS (eureka 1 + gateway 3 + payment 339 + pg 206 + product 20 + user 1).

---

## 1. 분석한 코드 진입점 (실제 trace 기준 — 재고 모델 정리 반영)

| # | 진입점 | 흐름 |
|---|---|---|
| **F1** | `POST /api/v1/payments/checkout` | `PaymentController.checkout` → `PaymentCheckoutServiceImpl.checkout` (`@Transactional`) → `IdempotencyStore.getOrCreate` → `OrderedUserUseCase.getUserInfoById` (HTTP user-service) + `OrderedProductUseCase.getProductInfoList` (HTTP product-service) + `PaymentCreateUseCase.createNewPaymentEvent` |
| **F2** | `POST /api/v1/payments/confirm` | `PaymentController.confirm` → `OutboxAsyncConfirmService.confirm` → `paymentEvent.validateConfirmRequest` → `PaymentTransactionCoordinator.decrementStock` (redis-stock Lua DECR, TX 외부) → `executeConfirmTxWithStockCompensation` (TX 실패 시 redis-stock INCR 보상) → `PaymentTransactionCoordinator.executeConfirmTx` (`@Transactional` 안에서 event 전이 + outbox PENDING + `confirmPublisher.publish(orderId, userId, amount, paymentKey)` 4파라미터) → 202 Accepted 반환 |
| **F3** | `OutboxImmediateEventHandler` | `@TransactionalEventListener(AFTER_COMMIT)` + `@Async("outboxRelayExecutor")` → `OutboxRelayService.relay` (`@Transactional`) → `claimToInFlight` CAS → `messagePublisherPort.send(payment.commands.confirm, …)` → `outbox.toDone` |
| **F4** | `OutboxWorker` `@Scheduled(fixedDelay)` | Step 0 `recoverTimedOutInFlightRecords` → Step 1 `findPendingBatch` → 각 row 에 대해 `OutboxRelayService.relay` 위임. parallel 모드 시 `ContextAwareVirtualThreadExecutors` 사용 |
| **F5** | `PaymentConfirmConsumer` (pg-service) | `@KafkaListener(topics=payment.commands.confirm, groupId=pg-service)` → `PgConfirmService.handle` → `EventDedupeStore.markSeen` (실패 시 try/catch + `remove` 보상) → inbox 상태 분기 (`handleNone`/`handleInProgress`/`handleTerminal`) → `PgVendorCallService.callVendor` |
| **F6** | `PgVendorCallService.callVendor` (`@Transactional`) | `pgConfirmStrategySelector.select(vendorType).confirm` → `GatewayOutcome` sealed interface 매칭 → `handleSuccess` / `handleDefinitiveFailure` / `handleRetry` (자체 retry — pg_outbox 의 `payment.commands.confirm` 으로 재발행, attempt≥4 시 DLQ) → `pg_outbox` INSERT + `pg_inbox` 상태 전이 + `PgOutboxReadyEvent` publish |
| **F7** | `PgOutboxImmediateWorker` + `PgOutboxPollingWorker` | pg_outbox row → `PgOutboxRelayService` → `PgEventPublisher.publish` (동기 `.get(timeout)`) → 토픽별 Kafka 발행 (`payment.events.confirmed` 또는 `payment.commands.confirm` 재시도) |
| **F8** | `ConfirmedEventConsumer` (payment-service) | `@KafkaListener(topics=payment.events.confirmed, groupId=payment-service)` → `PaymentConfirmResultUseCase.handle` (`@Transactional(timeout=5)`) |
| **F9** | `PaymentConfirmResultUseCase.handle` | `markWithLease(eventUuid, PT5M)` → false 면 skip → `processMessageWithLeaseGuard` → 분기: `handleApproved` / `handleFailed` / `handleQuarantined` → `extendLease(P8D)` → 실패 시 `remove` → false 면 `paymentConfirmDlqPublisher.publishDlq` |
| **F10** | `handleApproved` | `parseApprovedAt(non-null)` → `isAmountMismatch` 검사 → 불일치 시 **`compensateStockCache` (redis-stock INCR 보상)** + `QuarantineCompensationHandler.handle(orderId, AMOUNT_MISMATCH)` → 일치 시 `paymentCommandUseCase.markPaymentAsDone(event, approvedAt)` + 각 PaymentOrder 별 `StockOutboxFactory.buildStockCommitOutbox` → `stockOutboxRepository.save` → `StockOutboxReadyEvent` publish |
| **F11** | `handleFailed` (재고 모델 정리 후) | `paymentCommandUseCase.markPaymentAsFail(event, reason)` + 각 PaymentOrder 별 **`stockCachePort.increment(productId, qty)`** (redis-stock INCR 보상). product RDB 차감 안 됐으므로 stock.events.restore 발행 X |
| **F11b** | `handleQuarantined` | 각 PaymentOrder 별 **`stockCachePort.increment` 보상** + `QuarantineCompensationHandler.handle(orderId, reason)` 위임. 격리도 결제 미성립이라 Redis 일관성 유지 |
| **F12** | `StockOutboxImmediateEventHandler` | `@TransactionalEventListener(AFTER_COMMIT)` + `@Async("outboxRelayExecutor")` → `StockOutboxRelayService.relay(outboxId)` → `StockOutboxKafkaPublisher` → **`payment.events.stock-committed` 만 발행** (APPROVED 결과 전용. restore 토픽 자체 폐기) |
| **F13** | `GET /api/v1/payments/{orderId}/status` | `PaymentStatusServiceImpl.getPaymentStatus` → `findActiveOutboxStatus(orderId)` 가 PENDING/IN_FLIGHT 가지면 → `isClaimable` 분기 → 없으면 paymentEvent.status 매핑 (DONE/FAILED/그외→PROCESSING) |
| **F14** | `PaymentReconciler.scan()` `@Scheduled(fixedDelayString=2분)` | IN_FLIGHT 타임아웃 초과 결제를 `resetToReady` 만 수행 — stock 발산 감지/보정 책임은 제거됨 |
| **F15** | `D12 가드` (`executePaymentFailureCompensationWithOutbox`) | TX 내 outbox/event 재조회 → IN_FLIGHT AND 비종결 → 각 PaymentOrder 별 **`stockCachePort.increment` 보상** + outbox FAILED + markPaymentAsFail. 미충족 시 보상 skip + warn |
| **F16** | `compose-up.sh → seed-stock.sh` | docker compose up 직후 1회. `mysql-product` 의 stock 테이블 SELECT → `redis-stock` 에 SET. payment 가 confirm 진입 시 키 부재로 동작 이상 안 나도록 |

---

## 2. 문서와 코드의 불일치 표 (RESOLVED 4건 + 미해결 13건)

| ID | 문서 위치 | 문서 내용 | 실제 코드 | 심각도 |
|---|---|---|---|---|
| **D1** | `PAYMENT-FLOW.md` Phase 3 / Phase 5 | `confirmPublisher.publish(orderId)` — 1 파라미터 | 4 파라미터 `publish(orderId, userId, amount, paymentKey)` | ✅ **RESOLVED** (R2·Q1) |
| **D2** | `PAYMENT-FLOW.md` Phase 3 | `PaymentConfirmCommandMessage(orderId/paymentKey/amount/gatewayType/buyerId)` | 실제 필드: `(orderId, paymentKey, amount, vendorType, eventUuid)` — `buyerId` 가 아니라 `eventUuid`, `gatewayType` 이 아니라 `vendorType` | ✅ **RESOLVED** (R2·Q1) |
| **D3** | `PAYMENT-FLOW.md` 로컬 구동 시 주의사항 | "`OutboxImmediateEventHandler`는 `payment.monolith.confirm.enabled=true`일 때만 등록됨 — 현재 MSA 전환 진행형 플래그" | 코드는 `matchIfMissing=true` 라 default 활성화. MSA 봉인 후 사실상 무의미한 라인 | ✅ **RESOLVED** (R2·Q1) |
| **D4** | `CONFIRM-FLOW-ANALYSIS.md` + `ARCHITECTURE.md` 의 stock 발행 모델 | `StockCommitRequestedEvent` + `StockEventPublishingListener` | `stock_outbox` INSERT + `StockOutboxReadyEvent` + `StockOutboxImmediateEventHandler` | ✅ **RESOLVED** (커밋 #4) |
| **D5** | `CONFIRM-FLOW-FLOWCHART.md` 5번 다이어그램 | `paymentEvent.totalAmount` vs `message.amount` 의 `longValueExact()` 비교 | 동일 | OK |
| **D6** | `CONFIRM-FLOW-ANALYSIS.md` "FCG default 분기" | `executePaymentQuarantineWithOutbox` 가 FCG default 에서 호출 | 존재. 호출 chain 상세는 archive 위임 | OK |
| **D7** | `CONFIRM-FLOW-ANALYSIS.md` `handleFailed` (재고 정리 후 갱신본) | `stockCachePort.increment(productId, qty)` Redis 보상 | 동일 | OK |
| **D8** | `ARCHITECTURE.md` 어댑터 위치 표 | `StockEventPublishingListener` → `StockOutboxImmediateEventHandler` | 갱신됨 | ✅ **RESOLVED** (커밋 #4) |
| **D9** | `INTEGRATIONS.md` 외부 통신 매트릭스 | `payment-service ↔ pg-service Kafka bidirectional` | OK. 단 **pg-service 자체 retry 가 `payment.commands.confirm` 토픽에 자기 자신에게 재발행** 한다는 점이 미명시 | ✅ **RESOLVED** (R3·Q2) |
| **D10** | `CONFIRM-FLOW-ANALYSIS.md` "consumer 멱등 — EventDedupeStore two-phase lease" | payment 측 two-phase lease 만 명시 | pg 측 `EventDedupeStore.markSeen` (단순 mark 모델). payment 와 pg dedupe 가 **다른 모델** | ✅ **RESOLVED** (R5·Q4) |
| **D11** | `CONFIRM-FLOW-ANALYSIS.md` `handleApproved` 의 stock 발행 모델 | `StockCommitRequestedEvent` 직접 발행 → stock_outbox 패턴 | 갱신됨 | ✅ **RESOLVED** (커밋 #4) |
| **D12** | `CONFIRM-FLOW-ANALYSIS.md` "처리 실패 시 `remove(eventUuid)` → false 면 `dlqPublisher.publishDlq`" | OK | 코드 일치 | OK |
| **D13** | `CONFIRM-FLOW-ANALYSIS.md` "회복 시나리오" | 지속 운영 자체는 OK | **pg-service 의 retry/DLQ 정책 (attempt≥4 → DLQ)** 이 문서 어디서도 미명시 | ✅ **RESOLVED** (R5·Q4) |
| **D14** | `PaymentStatusServiceImpl` 의 폴링 결과 — `QUARANTINED` 매핑 | 문서 매핑 표에서 QUARANTINED → status=PROCESSING 인지 명시 X | 코드 default 분기로 PROCESSING. 격리는 영원히 끝나지 않을 수 있음 — 폴링 무한 | ✅ **RESOLVED** (R4·Q3) — CONFIRM-FLOW-ANALYSIS 상태 머신 표 + PITFALLS #17 |
| **D15** | `STACK.md` Flyway baseline 자동 적용 | OK | 코드는 통합 V1. 단 `@Tag("integration")` 통합 테스트의 Flyway 실 적용은 미실행 검증 | ✅ **RESOLVED** (R6·Q5) — 통합 테스트는 Flyway 비활성 + ddl-auto:create-drop 으로 격리됨을 STACK.md 에 명시. 부수 fix: application-test.yml spring 중복 키, dead `data.sql`, 빈 `data-test.sql` ScriptUtils 에러 |
| **D16** | `ARCHITECTURE.md` 토픽 표 | `payment.events.confirmed.dlq` payment-service 발행 | 코드: `PaymentConfirmDlqKafkaPublisher` | OK |
| **D17** | `ARCHITECTURE.md` 토픽 표 | `payment.commands.confirm.dlq` payment-service / pg-service relay | 코드: pg-service `PgVendorCallService.insertDlqOutbox` 만 — payment-service 발행 흔적 없음 | ✅ **RESOLVED** (R3·Q2) |
| **D18** | (사용자 발견) | payment 의 stock 캐시가 redis-stock 으로 가도록 의도 → wiring 결함으로 redis-dedupe 에 들어감 | `StockRedisConfig` 신설로 명시 wiring | ✅ **RESOLVED** (커밋 #1) |

---

## 3. 핵심 발견 요약 — 미해결 항목

### Major (정확성 영향)

- **D2 — `PaymentConfirmCommandMessage` 필드명**: 문서가 `buyerId` / `gatewayType` 이라고 적어놨으나 실제 wire contract 는 `eventUuid` / `vendorType`
- **D9 — pg-service 자체 retry** 가 `payment.commands.confirm` 에 재발행한다는 사실 미명시
- **D14 — `QUARANTINED` 상태의 status 폴링 결과 = `PROCESSING`** — 운영 영향 큰 사실. 격리된 결제는 무한 PROCESSING 으로 보임 → 폴링이 끝없이 — admin 개입 필요

### Minor

- **D1, D3, D10, D13, D15, D17** — 정확성 영향은 적으나 일관성 차원

---

## 4. 검증 통계

총 18 항목 중:
- ✅ **일치**: 5 (D5, D6, D7, D12, D16)
- ✅ **RESOLVED**: 4 (D4, D8, D11, D18 — stock 모델 정리 4 커밋)
- ⚠️ Major **미해결**: 3 (D2, D9, D14)
- ⚠️ Minor **미해결**: 6 (D1, D3, D10, D13, D15, D17)

---

## 5. 갱신 우선순위 (SELF-LOOP 사이클 권장 순)

| 라운드 | 영역 | 처리 항목 |
|---|---|---|
| ~~R1 (완료)~~ | ~~ARCHITECTURE / CONFIRM-FLOW-*~~ | ~~D4 / D8 / D11 / D18~~ — stock 정리 4 커밋에서 처리됨 |
| **R1** (다음) | `docs/context/PAYMENT-FLOW.md` | **D2 / D1 / D3** — 메시지 필드 정정, publish 시그니처, monolith.confirm.enabled 라인 정리 |
| R2 | `docs/context/INTEGRATIONS.md` + `ARCHITECTURE.md` | **D9 / D17** — pg-service 자체 retry 흐름 추가, DLQ 발행 출처 정정 |
| R3 | `docs/context/CONFIRM-FLOW-ANALYSIS.md` + `PITFALLS.md` | **D14** — QUARANTINED 의 status 폴링 결과 명시 + admin 복구 필요성 |
| R4 | `docs/context/CONFIRM-FLOW-ANALYSIS.md` 또는 `CONFIRM-FLOW-FLOWCHART.md` | **D10 / D13** — pg-service dedupe 모델 차이, retry/DLQ 정책 |
| R5 | `docs/context/STACK.md` | **D15** — `./gradlew :payment-service:integrationTest` 실행해 Flyway 적용 확인 후 명시 |

---

## 6. 본 보고서가 다루지 못한 영역 (SELF-LOOP 후속 큐)

본 분석은 confirm 사이클 + status 폴링에 집중. 추가 trace 가 필요한 영역:

- ~~stock 발산 감지/보정~~ (해소 — Reconciler stock 부분 제거)
- `PaymentReconciler.resetStaleInFlightRecords` 의 정합성 — 새 단순화된 책임 단독 검증
- `QuarantineCompensationHandler` 의 `handle` 분기 (현재 단순 markPaymentAsQuarantined 위임?)
- product-service 측 `StockCommitConsumer` 의 dedupe + 재고 처리 (restore 사라진 후 단일 경로)
- gateway 라우팅 정책
- `NicepayReturnController` (PG returnUrl 처리 분기)
- admin 페이지 (`PaymentAdminController`)
- 실 Toss / NicePay 어댑터의 응답 매핑 (예외 분류 정확성)
- `IdempotencyStoreRedisAdapter` 의 checkout 멱등성 검증 (Caffeine vs Redis 이중 가드?)
- `seed-stock.sh` 의 운영 안전성 — 운영 중 우발 호출 방어책

이들은 본 보고서의 18 항목과 별개로 SELF-LOOP 의 Q1' (같은 영역 다른 관점) / Q15+ (신규 영역) 라운드에서 다룬다.
