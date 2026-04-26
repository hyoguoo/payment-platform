# Payment Flow 브리핑 — 웹에서 결제 요청 시 end-to-end 처리

현재 `main` (MSA 전환 중, pg-service 분리 완료 후 기준) 코드를 기준으로, 브라우저가
결제를 시작해서 최종 DONE/FAILED까지 도달하는 전 과정을 정리한다.

---

## 한 줄 요약

브라우저 → **checkout (결제 이벤트 생성)** → **PG SDK 창** → **confirm (Redis 재고 DECR
→ outbox PENDING 커밋 → Kafka `payment.commands.confirm` 발행)** → pg-service가
소비해 **실제 Toss/Nicepay 호출** → 결과를 `payment.events.confirmed`로 되쏨 →
payment-service가 **DONE/FAILED/QUARANTINED** 전이 + **재고 commit/restore** 이벤트
발행 → 브라우저는 **GET /status 폴링**으로 최종 상태 확인.

---

## 전체 플로우차트 (분기 포함)

### Phase 1 — 주문 생성 + PG SDK 진입

```mermaid
flowchart TD
    A["브라우저: checkout.html or checkout-nicepay.html"] -->|"POST /api/v1/payments/checkout<br/>Idempotency-Key"| B["PaymentCheckoutServiceImpl<br/>@Transactional"]
    B --> C{"IdempotencyStore<br/>이미 있는 키?"}
    C -->|Yes 중복| C1["isDuplicate=true<br/>기존 orderId 반환<br/>HTTP 200"]
    C -->|No 신규| D["OrderedUserUseCase.getUserInfoById<br/>→ user-service HTTP"]
    D --> E["OrderedProductUseCase.getProductInfoList<br/>→ product-service HTTP"]
    E --> F["PaymentCreateUseCase.createNewPaymentEvent<br/>PaymentEvent + PaymentOrder*<br/>status=READY 저장"]
    F --> G["HTTP 201 Created<br/>orderId, totalAmount"]
    G --> H["브라우저: PG SDK 호출<br/>Toss PaymentWidget or Nicepay AUTHNICE"]
    H --> I{"사용자 결제<br/>행위 완료?"}
    I -->|실패/취소| I1["실패 페이지 — 서버 상태 변경 없음"]
    I -->|성공| J["PG가 returnUrl로 리다이렉트<br/>paymentKey 포함"]
```

### Phase 2 — confirm 비동기 진입 (핵심)

```mermaid
flowchart TD
    J["브라우저: POST /api/v1/payments/confirm<br/>userId, orderId, amount, paymentKey"] --> K["OutboxAsyncConfirmService.confirm"]
    K --> K1["paymentEvent.validateConfirmRequest<br/>userId/amount/orderId/paymentKey 위변조 검증"]
    K1 --> K2["PaymentTransactionCoordinator.decrementStock<br/>Redis 원자 DECR — TX 외부"]
    K2 --> L{"재고 차감 결과"}
    L -->|"REJECTED<br/>재고 부족"| L1["handleStockFailure<br/>event.status=FAILED<br/>throw 409"]
    L -->|"CACHE_DOWN<br/>Redis 장애"| L2["markStockCacheDownQuarantine<br/>event.status=QUARANTINED<br/>quarantine_compensation_pending=true<br/>throw 409"]
    L -->|SUCCESS| M["executeConfirmTx @Transactional<br/>event: READY→IN_PROGRESS<br/>+ payment_outbox PENDING 삽입<br/>원자 커밋"]
    M --> N["confirmPublisher.publish<br/>→ Spring ApplicationEvent<br/>PaymentConfirmEvent"]
    N --> O["HTTP 202 Accepted<br/>orderId, amount 즉시 반환"]
    O --> P["브라우저: /status 폴링 시작"]
```

### Phase 3 — outbox relay → Kafka (payment-service)

```mermaid
flowchart TD
    N["PaymentConfirmEvent 발행됨<br/>TX 이미 커밋됨"] --> Q["OutboxImmediateEventHandler<br/>@TransactionalEventListener AFTER_COMMIT<br/>+ @Async outboxRelayExecutor VT"]
    Q --> R["OutboxRelayService.relay orderId"]
    R --> R1["Step1: claimToInFlight CAS<br/>PENDING→IN_FLIGHT"]
    R1 --> R2{"선점 성공?"}
    R2 -->|No 다른 워커 처리중| R2a["즉시 return 멱등"]
    R2 -->|Yes| R3["Step2: outbox + paymentEvent 조회"]
    R3 --> R4["Step3: KafkaMessagePublisher.send<br/>topic=payment.commands.confirm<br/>PaymentConfirmCommandMessage<br/>orderId/paymentKey/amount/gatewayType/buyerId"]
    R4 --> R5{"Kafka 발행 성공?"}
    R5 -->|실패 예외 전파| R5a["IN_FLIGHT 유지<br/>→ OutboxWorker가 타임아웃 복구 재발행"]
    R5 -->|성공| R6["Step4: outbox.toDone 저장"]

    S["scheduler/OutboxWorker<br/>@Scheduled 폴백"] -.->|"리스너 스킵/크래시 대비"| R
    S --> S1["PENDING 배치 조회<br/>+ IN_FLIGHT 타임아웃 recover"]
```

### Phase 4 — pg-service 소비 + 실제 PG 호출 + outbox relay

```mermaid
flowchart TD
    T["payment.commands.confirm<br/>Kafka Topic"] --> U["PaymentConfirmConsumer<br/>@KafkaListener groupId=pg-service"]
    U --> V["PgConfirmService.handle"]
    V --> V1{"EventDedupeStore.markSeen<br/>eventUUID 중복?"}
    V1 -->|Yes 중복| V1a["no-op return"]
    V1 -->|No 신규| V2["pg_inbox 조회"]
    V2 --> W{"inbox.status?"}
    W -->|NONE or null| W1["transitNoneToInProgress CAS"]
    W1 --> W1a{"CAS 성공?"}
    W1a -->|No 선점됨| W1b["no-op"]
    W1a -->|Yes| W1c["PgVendorCallService.callVendor<br/>vendorType=TOSS or NICEPAY"]
    W1c --> X1["Toss/Nicepay HTTP 호출"]
    X1 --> X2{"응답"}
    X2 -->|2xx 승인| X2a["inbox.status=APPROVED<br/>stored_status_result 저장"]
    X2 -->|4xx 확정 거절| X2b["inbox.status=FAILED"]
    X2 -->|5xx/timeout 불확실| X2c["inbox.status=QUARANTINED<br/>DLQ 경로"]
    W -->|IN_PROGRESS| W2["no-op 다른 소비자 처리중"]
    W -->|"APPROVED/FAILED/QUARANTINED<br/>terminal 재수신"| W3["stored_status_result로<br/>pg_outbox 재발행<br/>벤더 재호출 금지"]
    X2a --> Y["pg_outbox insert<br/>payload=ConfirmedEventMessage"]
    X2b --> Y
    X2c --> Y
    W3 --> Y
    Y --> Y1["OutboxReadyEventHandler<br/>AFTER_COMMIT → PgOutboxChannel.offer"]
    Y1 --> Y2["PgOutboxImmediateWorker<br/>VT 워커가 channel.take → relay"]
    Y1 -.->|"채널 full or 누락"| Y3["PgOutboxPollingWorker<br/>@Scheduled 폴백<br/>processedAt IS NULL AND availableAt&lt;=NOW"]
    Y2 --> Z["PgOutboxRelayService<br/>→ PgEventPublisher<br/>→ payment.events.confirmed 발행"]
    Y3 --> Z
```

### Phase 5 — payment-service 수신 + 최종 상태 + 재고 정산

```mermaid
flowchart TD
    AA["payment.events.confirmed<br/>Kafka Topic"] --> AB["ConfirmedEventConsumer<br/>@KafkaListener groupId=payment-service"]
    AB --> AC["PaymentConfirmResultUseCase.handle"]
    AC --> AC1{"EventDedupeStore.markSeen<br/>중복?"}
    AC1 -->|Yes| AC1a["no-op"]
    AC1 -->|No| AC2["paymentEvent 조회"]
    AC2 --> AD{"message.status"}
    AD -->|APPROVED| AE1["event.done approvedAt<br/>각 PaymentOrder별로<br/>stock.events.commit 발행<br/>→ product-service 재고 확정"]
    AD -->|FAILED| AE2["event.fail reasonCode<br/>stock.events.restore 발행<br/>→ product-service 재고 복원"]
    AD -->|QUARANTINED| AE3["QuarantineCompensationHandler.handle<br/>FCG 진입점<br/>재고 복구 + 수동 조사 알림"]

    AF["브라우저: 폴링<br/>GET /api/v1/payments/orderId/status"] --> AG["PaymentStatusServiceImpl"]
    AG --> AG1{"outbox active?<br/>PENDING/IN_FLIGHT"}
    AG1 -->|Yes PENDING| AG1a["status=PENDING<br/>approvedAt=null"]
    AG1 -->|Yes IN_FLIGHT| AG1b["status=PROCESSING"]
    AG1 -->|No outbox 이미 DONE| AG2{"event.status?"}
    AG2 -->|DONE| AH1["status=DONE<br/>approvedAt non-null<br/>→ 성공 페이지"]
    AG2 -->|FAILED| AH2["status=FAILED<br/>→ 실패 페이지"]
    AG2 -->|"그 외 READY/IN_PROGRESS/RETRYING"| AH3["status=PROCESSING<br/>→ 계속 폴링"]
```

---

## Outbox Relay 워커 대응 관계 (Phase 3 vs Phase 4 말미)

두 서비스 모두 Transactional Outbox 패턴을 쓰지만 **다른 인스턴스 / 다른 빈 / 다른 스레드**다. ADR-04 대칭 설계.

| 역할 | payment-service (Phase 3) | pg-service (Phase 4 말미) |
|---|---|---|
| AFTER_COMMIT 리스너 | `OutboxImmediateEventHandler` | `OutboxReadyEventHandler` |
| 즉시 릴레이 엔진 | `@Async("outboxRelayExecutor")` — Spring 관리 VT 풀 | `PgOutboxChannel` (in-memory BlockingQueue) + `PgOutboxImmediateWorker` (SmartLifecycle VT 워커 N개) |
| 폴링 폴백 | `OutboxWorker` (@Scheduled, PENDING 배치) | `PgOutboxPollingWorker` (@Scheduled, `processedAt IS NULL AND availableAt <= NOW`) |
| 실제 Kafka 발행 | `OutboxRelayService` → `KafkaMessagePublisher` | `PgOutboxRelayService` → `PgEventPublisher` |
| 발행 토픽 | `payment.commands.confirm` | `payment.events.confirmed` |

pg-service는 채널(`PgOutboxChannel`, BlockingQueue)을 **명시적으로** 두고 `PgOutboxImmediateWorker`가 `channel.take()` 블로킹 수신 → VT executor 위임. payment-service는 Spring `@Async`가 큐/워커를 캡슐화. ADR-30 available_at 기반 지연 발행은 pg 쪽에만 적용.

---

## 시계열 요약

| # | 주체 | 동작 | 결과물 |
|---|---|---|---|
| 1 | 브라우저 | `POST /checkout` | payment_event(READY) + payment_order INSERT, 201 |
| 2 | 브라우저 | PG SDK 열림 → 결제 승인 | `paymentKey` 획득, returnUrl 리다이렉트 |
| 3 | 브라우저 | `POST /confirm` | — |
| 4 | payment | Redis stock DECR | SUCCESS / REJECTED / CACHE_DOWN |
| 5 | payment | TX 커밋: event IN_PROGRESS + outbox PENDING | — |
| 6 | payment | `confirmPublisher.publish` (ApplicationEvent) | 호출자에게 **즉시 HTTP 202 반환** |
| 7 | payment | AFTER_COMMIT + @Async VT 리스너 | outbox IN_FLIGHT 선점 → **Kafka `payment.commands.confirm` 발행** → outbox DONE |
| 8 | pg | Kafka consume | pg_inbox NONE→IN_PROGRESS CAS |
| 9 | pg | Toss/Nicepay HTTP 호출 | APPROVED / FAILED / QUARANTINED |
| 10 | pg | pg_outbox 저장 → PgOutboxImmediateWorker relay | **Kafka `payment.events.confirmed` 발행** |
| 11 | payment | Kafka consume | event DONE/FAILED, 재고 commit/restore 발행 |
| 12 | 브라우저 | `GET /status` 폴링 | PENDING → PROCESSING → DONE/FAILED |

---

## 장애 복원 포인트

- **리스너 스킵/크래시**: payment쪽은 `OutboxWorker`, pg쪽은 `PgOutboxPollingWorker`가 PENDING/타임아웃 IN_FLIGHT를 재픽업
- **PG 5xx/timeout**: pg_inbox=QUARANTINED, payment 측 `QuarantineCompensationHandler`에서 FCG 경로 실행
- **재고 캐시 장애**: confirm 단계에서 CACHE_DOWN → event QUARANTINED + 보상 펜딩, `QuarantineCompensationScheduler`가 재시도
- **드리프트 체크**: `PaymentReconciler` (@Scheduled 2분) — pg/payment 상태 불일치 스캔
- **중복 메시지**: `EventDedupeStore`(Redis eventUUID) — pg/payment 양쪽에서 1단 dedupe, inbox/outbox 상태 CAS가 2단 멱등성

---

## 로컬 구동 시 주의사항

- `OutboxImmediateEventHandler`는 `payment.monolith.confirm.enabled=true`일 때만 등록됨 — 현재 MSA 전환 진행형 플래그.
  - `application-benchmark.yml`에 설정이 없으면 **payment 측 outbox relay는 OutboxWorker 폴백 (2초 주기)에만 의존**하므로 HTTP 202 이후 `/status=DONE`까지 2~4초 추가 지연 가능.
- `ConfirmedEventConsumer` / `PaymentConfirmConsumer`는 `spring.kafka.bootstrap-servers` 조건.
  - Kafka 미기동 상태로 띄우면 outbox는 IN_FLIGHT→DONE까지 가지만 `payment.events.confirmed` 소비자가 없어 **event.status는 영영 PROCESSING**에 멈춤.
- user-service / product-service가 안 떠 있으면 Phase 1의 HTTP 호출에서 503 (`USER_SERVICE_UNAVAILABLE` / `PRODUCT_SERVICE_UNAVAILABLE`) 반환 — checkout 자체가 안 뜸.
- Redis가 안 떠 있으면 `IdempotencyStoreRedisAdapter` 장애로 checkout 자체 실패. confirm 단계에서는 재고 DECR 실패 → `CACHE_DOWN` → QUARANTINED 전이 경로.
