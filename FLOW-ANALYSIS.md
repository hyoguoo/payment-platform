# 기능별 플로우 코드 분석 + 문서 정합성 검증 보고서

> 작성: 2026-04-27 (MSA-TRANSITION + PRE-PHASE-4-HARDENING 봉인 직후)
> 갱신: 2026-04-27 — Stock 모델 정리(restore 폐기 + Redis 보상 + warmup 제거) 후 D4/D11 RESOLVED 표시
> 목적: 실제 코드를 trace 해 각 기능의 흐름을 정리하고, `docs/context/` 문서들과의 정합성을 검증한다.
> 후속: 본 보고서의 불일치 항목을 SELF-LOOP 사이클로 갱신.

---

## 1. 분석한 코드 진입점 (실제 trace 기준)

| # | 진입점 | 흐름 |
|---|---|---|
| **F1** | `POST /api/v1/payments/checkout` | `PaymentController.checkout` → `PaymentCheckoutServiceImpl.checkout` (`@Transactional`) → `IdempotencyStore.getOrCreate` → `OrderedUserUseCase.getUserInfoById` (HTTP user-service) + `OrderedProductUseCase.getProductInfoList` (HTTP product-service) + `PaymentCreateUseCase.createNewPaymentEvent` |
| **F2** | `POST /api/v1/payments/confirm` | `PaymentController.confirm` → `OutboxAsyncConfirmService.confirm` → `paymentEvent.validateConfirmRequest` → `PaymentTransactionCoordinator.decrementStock` (Redis DECR, TX 외부) → `executeConfirmTxWithStockCompensation` → `PaymentTransactionCoordinator.executeConfirmTx` (`@Transactional` 안에서 event 전이 + outbox PENDING + **`confirmPublisher.publish(orderId, buyerId, totalAmount, paymentKey)`**) → 202 Accepted 반환 |
| **F3** | `OutboxImmediateEventHandler` | `@TransactionalEventListener(AFTER_COMMIT)` + `@Async("outboxRelayExecutor")` → `OutboxRelayService.relay` (`@Transactional`) → `claimToInFlight` CAS → `messagePublisherPort.send(payment.commands.confirm, …)` → `outbox.toDone` |
| **F4** | `OutboxWorker` `@Scheduled(fixedDelay)` | Step 0 `recoverTimedOutInFlightRecords` → Step 1 `findPendingBatch` → 각 row 에 대해 `OutboxRelayService.relay` 위임. parallel 모드 시 `ContextAwareVirtualThreadExecutors` 사용 |
| **F5** | `PaymentConfirmConsumer` (pg-service) | `@KafkaListener(topics=payment.commands.confirm, groupId=pg-service)` → `PgConfirmService.handle` → `EventDedupeStore.markSeen` → inbox 상태 분기 (`handleNone`/`handleInProgress`/`handleTerminal`) → `PgVendorCallService.callVendor` |
| **F6** | `PgVendorCallService.callVendor` (`@Transactional`) | `pgConfirmStrategySelector.select(vendorType).confirm` → `GatewayOutcome` sealed interface 매칭 → `handleSuccess` / `handleDefinitiveFailure` / `handleRetry` (자체 retry — pg_outbox 의 `payment.commands.confirm` 으로 재발행, attempt≥4 시 DLQ) → `pg_outbox` INSERT + `pg_inbox` 상태 전이 + `PgOutboxReadyEvent` publish |
| **F7** | `PgOutboxImmediateWorker` + `PgOutboxPollingWorker` | pg_outbox row → `PgOutboxRelayService` → `PgEventPublisher.publish` (동기 `.get(timeout)`) → 토픽별 Kafka 발행 (`payment.events.confirmed` 또는 `payment.commands.confirm` 재시도) |
| **F8** | `ConfirmedEventConsumer` (payment-service) | `@KafkaListener(topics=payment.events.confirmed, groupId=payment-service)` → `PaymentConfirmResultUseCase.handle` (`@Transactional(timeout=5)`) |
| **F9** | `PaymentConfirmResultUseCase.handle` | `markWithLease(eventUuid, PT5M)` → false 면 skip → `processMessageWithLeaseGuard` → 분기: `handleApproved` / `handleFailed` / `handleQuarantined` → `extendLease(P8D)` → 실패 시 `remove` → false 면 `paymentConfirmDlqPublisher.publishDlq` |
| **F10** | `handleApproved` | `parseApprovedAt(non-null)` → `isAmountMismatch` 검사 → 불일치 시 `QuarantineCompensationHandler.handle(orderId, AMOUNT_MISMATCH)` → 일치 시 `paymentCommandUseCase.markPaymentAsDone(event, approvedAt)` + 각 PaymentOrder 별 **`StockOutboxFactory.buildStockCommitOutbox` → `stockOutboxRepository.save` → `StockOutboxReadyEvent` publish** |
| **F11** | `handleFailed` | `paymentCommandUseCase.markPaymentAsFail(event, reason)` + 각 PaymentOrder 별 `failureCompensationService.compensate(orderId, productId, qty)` → 내부에서 stock_outbox INSERT + `StockOutboxReadyEvent` publish |
| **F12** | `StockOutboxImmediateEventHandler` | `@TransactionalEventListener(AFTER_COMMIT)` + `@Async("outboxRelayExecutor")` → `StockOutboxRelayService.relay(outboxId)` → `StockOutboxKafkaPublisher` → `stock.events.commit` 또는 `stock.events.restore` |
| **F13** | `GET /api/v1/payments/{orderId}/status` | `PaymentStatusServiceImpl.getPaymentStatus` → `findActiveOutboxStatus(orderId)` 가 PENDING/IN_FLIGHT 가지면 → `isClaimable` 분기 → 없으면 paymentEvent.status 매핑 (DONE/FAILED/그외→PROCESSING) |

---

## 2. 문서와 코드의 불일치 표

| ID | 문서 위치 | 문서 내용 | 실제 코드 | 심각도 |
|---|---|---|---|---|
| **D1** | `PAYMENT-FLOW.md` Phase 3 / Phase 5 | `confirmPublisher.publish(orderId)` — 1 파라미터 | 4 파라미터 `publish(orderId, userId, amount, paymentKey)` | minor |
| **D2** | `PAYMENT-FLOW.md` Phase 3 | `PaymentConfirmCommandMessage(orderId/paymentKey/amount/gatewayType/buyerId)` | 실제 필드: `(orderId, paymentKey, amount, vendorType, eventUuid)` — `buyerId` 가 아니라 `eventUuid`, `gatewayType` 이 아니라 `vendorType` | **major** — 외부 계약 오기 |
| **D3** | `PAYMENT-FLOW.md` 로컬 구동 시 주의사항 | "`OutboxImmediateEventHandler`는 `payment.monolith.confirm.enabled=true`일 때만 등록됨 — 현재 MSA 전환 진행형 플래그" | 코드는 `matchIfMissing=true` 라 default 활성화. MSA 봉인 후 사실상 무의미한 라인 | minor |
| **D4** | `CONFIRM-FLOW-ANALYSIS.md` 의 "AFTER_COMMIT stock 발행" + `ARCHITECTURE.md` 의 `StockEventPublishingListener` | `StockCommitRequestedEvent` 발행 → `StockEventPublishingListener` 가 `@TransactionalEventListener(AFTER_COMMIT)` 으로 `stock.events.commit` Kafka publish | 실제 코드: **`stock_outbox` 테이블에 INSERT** + `StockOutboxReadyEvent` publish → `StockOutboxImmediateEventHandler` → `StockOutboxRelayService` → `StockOutboxKafkaPublisher`. 즉 **stock 발행도 transactional outbox 패턴**으로 변경. PRE-PHASE-4 D2 이후 추가 진화 | ✅ **RESOLVED** (커밋 #4) — 새 재고 모델 정리 후 ARCHITECTURE / CONFIRM-FLOW-ANALYSIS / CONFIRM-FLOW-FLOWCHART 모두 갱신 |
| **D5** | `CONFIRM-FLOW-FLOWCHART.md` 5번 다이어그램 | `paymentEvent.totalAmount` vs `message.amount` 의 `longValueExact()` 비교 | 동일 | OK |
| **D6** | `CONFIRM-FLOW-ANALYSIS.md` "FCG default 분기" | `executePaymentQuarantineWithOutbox` 가 FCG default 에서 호출 | `PaymentTransactionCoordinator.executePaymentQuarantineWithOutbox` 존재 — OK. 다만 호출 chain (PaymentReconciler 등) 의 자세한 trace 는 ANALYSIS.md 가 archive 로 위임 | OK |
| **D7** | `CONFIRM-FLOW-ANALYSIS.md` `handleFailed` | `failureCompensationService.compensate(orderId, productId, qty)` | 동일 | OK |
| **D8** | `ARCHITECTURE.md` 어댑터 위치 표 | `StockEventPublishingListener` | 코드에 없음. 대신 `StockOutboxImmediateEventHandler` (한 줄 갱신 필요) | ✅ **RESOLVED** (커밋 #4) |
| **D9** | `INTEGRATIONS.md` 외부 통신 매트릭스 | `payment-service ↔ pg-service Kafka bidirectional` | OK. 단 **pg-service 자체 retry 가 `payment.commands.confirm` 토픽에 자기 자신에게 재발행** 한다는 점은 어디서도 문서화 안 됨. 실제 토픽 발행 패턴: payment 가 발행한 메시지 1건이 retry 시 pg 가 추가 N건 발행 → 같은 토픽이 양 측에서 발행됨 | **major** — 메시지 흐름 누락 |
| **D10** | `CONFIRM-FLOW-ANALYSIS.md` "consumer 멱등 — EventDedupeStore two-phase lease" | payment 측 two-phase lease 만 명시 | pg 측 `EventDedupeStore.markSeen` (단순 mark 모델). payment 와 pg dedupe 가 **다른 모델** — 문서가 이를 다루지 않음 | minor |
| **D11** | `CONFIRM-FLOW-ANALYSIS.md` `handleApproved` 의 "stock.events.commit 발행" | `StockCommitRequestedEvent` 직접 발행 | 실제: `stock_outbox` INSERT + `StockOutboxReadyEvent` (D4 와 같은 이슈) | ✅ **RESOLVED** (커밋 #4) |
| **D12** | `CONFIRM-FLOW-ANALYSIS.md` "처리 실패 시 `remove(eventUuid)` → false 면 `dlqPublisher.publishDlq`" | OK | 코드: `paymentConfirmDlqPublisher.publishDlq(eventUuid, originalException.getMessage())` | OK |
| **D13** | `CONFIRM-FLOW-ANALYSIS.md` "회복 시나리오" | 지속 운영 자체는 OK | 단 **pg-service 의 retry/DLQ 정책 (attempt≥4 → DLQ)** 은 문서 어디서도 명시 X | minor |
| **D14** | `PaymentStatusServiceImpl` 의 폴링 결과 — `QUARANTINED` 매핑 | 문서 매핑 표에서 QUARANTINED 가 status=PROCESSING 인지 명시 X | 코드 default 분기로 `PROCESSING` 반환. 사용자/운영 입장 매우 중요 (격리는 영원히 끝나지 않을 수 있음 — 폴링 무한) | **major** |
| **D15** | `STACK.md` Flyway baseline 자동 적용 | OK | 코드는 통합 V1. 단 `@Tag("integration")` 통합 테스트가 Flyway 적용 후 실 schema 검증이 되는지 — 검증 필요 (이번 단계는 코드 trace 만, 실제 ./gradlew :payment-service:integrationTest 미실행) | minor |
| **D16** | `ARCHITECTURE.md` 토픽 표 | `payment.events.confirmed.dlq` payment-service 발행 | 코드: `PaymentConfirmDlqKafkaPublisher` 가 발행 | OK |
| **D17** | `ARCHITECTURE.md` 토픽 표 | `payment.commands.confirm.dlq` payment-service / pg-service relay | 코드: pg-service `PgVendorCallService.insertDlqOutbox` — pg-service 만 발행. payment-service 가 발행하는 흔적 없음 | minor |
| **D18** | (사용자 발견) | payment 의 stock 캐시가 redis-stock 으로 가도록 의도되었으나 default `StringRedisTemplate` 주입으로 redis-dedupe 에 들어가는 wiring 결함 | redis-dedupe 와 redis-stock 이 물리적으로 분리되어 product-service `setStock` 과 정합 안 됨 | ✅ **RESOLVED** (커밋 #1 — `StockRedisConfig` 신설로 명시 wiring) |

---

## 3. 핵심 발견 요약 (우선순위)

### Critical (반드시 갱신)

- **D4 / D11 — Stock 이벤트 발행 모델이 PRE-PHASE-4 D2 이후 추가 진화**: `StockCommitRequestedEvent` → `StockOutboxReadyEvent` + `stock_outbox` outbox 패턴. payment-service 의 `PaymentConfirmResultUseCase.handleApproved` 안에서 `stock_outbox` row 를 INSERT 후 `StockOutboxReadyEvent` 발행 → `StockOutboxImmediateEventHandler` → `StockOutboxRelayService` → `StockOutboxKafkaPublisher`. 문서 3개(`ARCHITECTURE.md`, `CONFIRM-FLOW-ANALYSIS.md`, `CONFIRM-FLOW-FLOWCHART.md`)에 모두 반영 필요. PRE-PHASE-4 archive briefing 의 D2 본문도 "현재는 더 진화함" 표시 여지 있음

### Major (정확성 영향)

- **D2 — `PaymentConfirmCommandMessage` 필드명**: 문서가 `buyerId` / `gatewayType` 이라고 적어놨으나 실제 wire contract 는 `eventUuid` / `vendorType`. payment ↔ pg 계약이라 외부 시스템 인식에 직접 영향
- **D8 — `StockEventPublishingListener` → `StockOutboxImmediateEventHandler`**: ARCHITECTURE.md 어댑터 위치 표
- **D9 — pg-service 자체 retry 가 `payment.commands.confirm` 에 재발행** 한다는 사실: 토픽 발행이 payment-service 만이 아니라 pg-service 에서도 발생함. INTEGRATIONS.md 와 ARCHITECTURE.md 의 토픽 표가 이걸 반영해야
- **D14 — `QUARANTINED` 상태의 status 폴링 결과 = `PROCESSING`**: 운영 영향 큰 사실. 격리된 결제는 무한 PROCESSING 으로 보임 → 폴링이 끝없이 — admin 개입 필요

### Minor

- **D1, D3, D10, D13, D15, D17** — 정확성에는 영향 적으나 일관성 차원에서 갱신 가치

---

## 4. 검증 통계

총 17 항목 중:
- ✅ 일치: 5 (D5, D6, D7, D12, D16)
- ⚠️ Critical: 2 (D4, D11 — 사실상 같은 stock outbox 진화)
- ⚠️ Major: 4 (D2, D8, D9, D14)
- ⚠️ Minor: 6 (D1, D3, D10, D13, D15, D17)

---

## 5. 갱신 우선순위 (SELF-LOOP 사이클 권장 순)

| 라운드 | 영역 | 처리 항목 |
|---|---|---|
| R1 | `docs/context/CONFIRM-FLOW-ANALYSIS.md` + `CONFIRM-FLOW-FLOWCHART.md` + `ARCHITECTURE.md` | **D4 / D11 / D8 (Critical + Major)** — stock outbox 패턴 반영 |
| R2 | `docs/context/PAYMENT-FLOW.md` | **D2 / D1 / D3** — 메시지 필드 정정, publish 시그니처, monolith.confirm.enabled 라인 정리 |
| R3 | `docs/context/INTEGRATIONS.md` + `ARCHITECTURE.md` | **D9 / D17** — pg-service 자체 retry 흐름 추가, DLQ 발행 출처 정정 |
| R4 | `docs/context/CONFIRM-FLOW-ANALYSIS.md` + `PITFALLS.md` 또는 `CONCERNS.md` | **D14** — QUARANTINED 의 status 폴링 결과 명시 + admin 복구 필요성 |
| R5 | `docs/context/CONFIRM-FLOW-ANALYSIS.md` 또는 `CONFIRM-FLOW-FLOWCHART.md` | **D10 / D13** — pg-service dedupe 모델 차이, pg-service retry/DLQ 정책 |
| R6 | (선택) `docs/context/STACK.md` 또는 별도 검증 | **D15** — `./gradlew :payment-service:integrationTest` 실행해서 통합 테스트의 Flyway 적용 확인 |

---

## 6. 비고 — 본 보고서가 다루지 못한 영역

본 분석은 confirm 사이클 + status 폴링에 집중. 추가 trace 가 필요한 영역(SELF-LOOP 후속 큐):

- `PaymentReconciler` (`@Scheduled`) — 복구 사이클 상세
- `QuarantineCompensationHandler` 의 `handle` 분기 (현재 단순 markPaymentAsQuarantined 위임?)
- product-service 측 `StockCommitConsumer` / `StockRestoreConsumer` 의 dedupe + 재고 처리
- gateway 라우팅 정책
- `NicepayReturnController` (PG returnUrl 처리 분기)
- admin 페이지 (`PaymentAdminController`)
- 실 Toss / NicePay 어댑터의 응답 매핑 (예외 분류 정확성)

이들은 본 보고서의 17 항목과 별개로 후속 라운드에서 다룬다.
