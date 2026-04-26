# code-domain-1

**Topic**: pre-phase-4-hardening
**Round**: 1
**Persona**: Domain Expert

## Reasoning

돈·재고 정합성 관점에서 FAILED 경로의 재고 복원이 **qty=0 플레이스홀더**로 발행되어 사실상 재고가 절대 되돌아오지 않는 구조적 결함이 있다. 이외에도 (1) 이벤트 dedupe TTL 이 1시간(payment) vs 8일(product) 비대칭이어서 DLQ·지연 재처리 윈도우에서 이중 상태 전이 위험, (2) HTTP 어댑터가 수동 빌드 WebClient/RestClient 를 쓰고 `Map.of()` 로 `traceparent` 전달을 누락해 결제 사고 재구성이 HTTP 홉에서 끊길 소지, (3) Redis 재고 DECR 이 TX 밖에서 먼저 일어나는데 후속 `executeConfirmTx` 실패 시 Redis 보상 경로가 없는 구멍이 식별된다.

## Domain risk checklist

- [x] `paymentKey` / `orderId` / 카드번호 plaintext 로그 노출 — LogFmt에서 orderId/paymentKey 는 평문이지만 벤더 PII(카드번호 등)는 `TossPaymentApiResponse` 에서 로깅 경로 없음. 결제 ID는 내부 키로 PII 아님.
- [x] 보상/취소 로직 멱등성 가드 — `StockRestoreUseCase.restore` 는 existsValid+recordIfAbsent 원자성 TX 로 보호됨. `DuplicateApprovalHandler.handleDuplicateApproval` 는 vendor 조회 + inbox CAS 로 보호됨. OK.
- [x] "이미 처리됨" 맹목 수용 — `TossPaymentErrorCode.ALREADY_PROCESSED_PAYMENT` 를 success로 보지 않고 `DuplicateApprovalHandler` 로 위임해 amount 대조 경로 통과. OK.
- [x] 상태 전이 불변식 — `QuarantineCompensationHandler.handle` 은 `markPaymentAsQuarantined` 를 사용하지만 사전 상태 검증 없음 (이미 DONE/FAILED 인 event 도 덮어쓸 소지) — **finding 로 상세화**.
- [x] race window 에 락/격리 — `PgOutboxRelayService.relay` 는 processed_at 체크만 있고 낙관적 락 없음 (Immediate+Polling 동시 처리 시 save 충돌 가능) — T3.5-12 RepeatedTest 로 커버됐으나 실제 DB 단위 테스트와 차이 있음. 추가 점검 권고.

## 도메인 관점 추가 검토

1. **FAILED 경로 재고 복원이 qty=0 발행**
   - `PaymentConfirmResultUseCase.handleFailed` (`payment-service/.../PaymentConfirmResultUseCase.java:105`) 는 `stockRestoreEventPublisherPort.publish(orderId, productIds)` 를 호출한다.
   - `StockRestoreEventKafkaPublisher.publish` (`.../StockRestoreEventKafkaPublisher.java:37-54`) 는 이 오버로드를 `qty=0` 플레이스홀더로 구현. (주석: "publish(orderId, productIds)는 qty 정보가 없는 레거시 진입 경로로, qty=0 플레이스홀더로 발행한다.")
   - 결과적으로 `StockRestoreMessage.qty=0` → `StockRestoreUseCase.restoreStockInRdb` 에서 `current.getQuantity() + 0` 을 저장. **FAILED 결제에서 재고가 한 번도 복원되지 않는다.**
   - 돈 관점: FAIL 된 결제에서 사용자는 상품을 못 받는데 다른 사용자도 해당 재고를 사용할 수 없음 → 재고 영구 고립.
   - `FailureCompensationService.compensate` 가 정상 경로 (`publishPayload` 로 실제 qty 전달) 이지만 현재 `ConfirmedEventConsumer → handleFailed` 는 이 서비스를 경유하지 않는다.

2. **eventUuid 전달 파라미터 오배치 (DuplicateApprovalHandler 위임)**
   - `TossPaymentGatewayStrategy.handleErrorResponse` (`pg-service/.../TossPaymentGatewayStrategy.java:148-149`) 는 `duplicateApprovalHandler.handleDuplicateApproval(request.orderId(), request.amount(), request.orderId())` 로 3번째 인자에 `orderId` 를 전달. 시그니처는 `(String orderId, BigDecimal payloadAmount, String eventUuid)`.
   - 현재 메서드 내부에서 `eventUuid` 는 사용되지 않지만, 향후 멱등성 키로 활용할 때 `orderId` 가 eventUuid 자리에 박혀 있으면 동일 orderId 의 반복 호출이 모두 동일 "eventUuid" 로 처리됨 → 의도와 다른 dedupe 동작.

3. **이벤트 dedupe TTL 비대칭**
   - payment-service `EventDedupeStoreRedisAdapter` (`.../EventDedupeStoreRedisAdapter.java:31`) 기본 TTL = `PT1H` (1시간).
   - product-service `StockRestoreUseCase.DEDUPE_TTL` = 8일 (Kafka retention 7일 + 1일).
   - `payment.events.confirmed` 컨슈머 lag 또는 pod 재기동으로 1시간 이후 재컨슘 발생 시, payment-service dedupe 는 이미 expire → `markSeen` 이 다시 `true` 반환 → **PaymentEvent 상태 이중 전이 + stock.events.commit/stock.events.restore 이중 발행** (product-service 쪽은 8일 보호로 살아남지만 payment-service 내부 이중 발행 자체가 문제).
   - application.yml 에 `payment.event-dedupe.ttl` override 없음(grep 결과 0건) → 프로덕션 기본값 1h 확정.

4. **QuarantineCompensationHandler 의 사전 상태 검증 부재**
   - `QuarantineCompensationHandler.handle` (`.../QuarantineCompensationHandler.java:41-49`) 는 `paymentLoadUseCase.getPaymentEventByOrderId(orderId)` 로 event 를 조회한 뒤 무조건 `markPaymentAsQuarantined` 호출.
   - `payment.events.confirmed` 메시지가 QUARANTINED 로 도달하기 전에 이미 DONE 이거나 FAILED 로 전이된 경우가 있을 수 있다 (예: FCG 후 DLQ 재진입, 운영자 수동 전이). 종결 상태→QUARANTINED 역전이가 불변식을 깰 가능성.
   - `markPaymentAsQuarantined` 도메인 메서드 내부에 guard 가 있는지 확인 필요 — 현재 재검증 경로가 이 handler 안에 없다.

5. **Redis 재고 DECR 과 Confirm TX 간 비원자성**
   - `OutboxAsyncConfirmService.confirm` (`.../OutboxAsyncConfirmService.java:49-68`) 는 `decrementStock` 을 TX 밖에서 실행해 SUCCESS 시 `executeConfirmTx` 로 진입.
   - `executeConfirmTx` (`.../PaymentTransactionCoordinator.java:84-95`) 내부에서 `confirmPublisher.publish` (Kafka 발행) 가 실패하거나 TX commit 실패 시 payment_event/outbox 는 롤백되지만 **Redis 에서 이미 차감된 재고는 복원되지 않는다**.
   - 해당 경로의 보상이 명시적으로 없는 듯 함 — `PaymentReconciler` 가 RDB↔Redis 정합 맞추는 경로는 있으나 즉시성 없음. Phase 4 Toxiproxy 시나리오에서 이 race 가 재현될 가능성 높음.

6. **DLQ consumer 에서 원본 traceId 단절**
   - `PaymentConfirmDlqConsumer` (`.../PaymentConfirmDlqConsumer.java:50-55`) 는 단순히 `pgDlqService.handle(command)` 위임. 메시지 헤더의 원본 `traceparent` 전파 경로는 `spring.kafka.template.observation-enabled=true` + Micrometer Tracing 자동 인스트루먼테이션에 의존.
   - T3.5-13 커밋으로 자동 전파가 활성화됐지만, **PgEventPublisher 가 outbox 릴레이 시 `headers=Map.of()` 를 기본값으로 전달** (`PgOutboxRelayService.parseHeaders:83-92` 는 현재 `empty-map` 하드코딩, 주석: "실제 Map 파싱은 T2a-05a 범위에서 단순 empty-map 처리로 제한한다"). 그래서 outbox 에 저장된 `headers_json` 은 사실상 쓸모없음. DLQ 경로에서 최초 confirm 시도의 traceId 로 돌아가고 싶을 때 outbox relay 를 거친 메시지는 traceId 가 끊길 수 있다. 자동 계측이 커버하긴 하나, outbox 저장 시점과 발행 시점이 다르면 micrometer 현재 span 으로 덮어써져 **원본 confirm 요청 traceId 와 다른 trace 로 표기**된다.

7. **HTTP 어댑터가 수동 WebClient/RestClient 로 traceparent 미전파**
   - `HttpOperatorImpl` (`payment-service/.../HttpOperatorImpl.java:31-34`) 는 `WebClient.builder().clientConnector(...).build()` — `observationRegistry(...)` 미호출, Spring Boot auto-config 의 instrumented builder 미사용.
   - `ProductHttpAdapter.callGet/callPost` (`.../ProductHttpAdapter.java:71, 81`) 는 `Map.of()` 를 headers 로 전달 → traceparent 수동 전파 없음.
   - pg-service `HttpOperatorImpl` 도 동일 패턴 (`pg-service/.../HttpOperatorImpl.java:32-38`) — `RestClient.builder()` 수동, observationRegistry 미주입.
   - 돈 관점: Toss/Nicepay 호출 실패 시 pg-service 내부 trace 는 있으나 payment-service→product-service 조회 실패 trace 는 HTTP 경계에서 끊겨 **사고 조사 시 원인 추적 분단**. Phase 4 장애 주입 후 사후 분석이 막힐 수 있음.

8. **FCG INDETERMINATE 홀딩 후 운영자 수동 복구 경로 부재 문서화**
   - `PgFinalConfirmationGate.handleIndeterminate` (`.../PgFinalConfirmationGate.java:198-208`) 는 timeout/5xx 시 inbox QUARANTINED + outbox(QUARANTINED payload) INSERT.
   - payment-service `ConfirmedEventConsumer → handleQuarantined` 는 PaymentEvent 를 QUARANTINED 로 전이. **이후 실제 벤더 결제가 성공/실패로 확정됐을 때 운영자가 어떤 도구로 재조정(QUARANTINED → DONE/FAILED)하는지 문서화 없음** — `docs/context/TODOS.md` 에도 언급되지 않음. 재고와 돈이 홀딩된 채 묶이는 자산.
   - T3.5-07 에서 QUARANTINED 재고 복구 경로를 철거했으므로 운영자 개입 외에 자동 경로 없음 — 이는 설계 의도로 보이지만 대시보드/알림 경로 명시 필요.

9. **`payment.events.confirmed` 소비 후 TX 실패 시 dedupe 되돌림 경합**
   - `PaymentConfirmResultUseCase.handle` (`.../PaymentConfirmResultUseCase.java:46-62`) 는 `markSeen` 으로 dedupe 를 먼저 찍고, `processMessage` 실패 시 `remove(eventUuid)` 로 되돌린다.
   - 문제: `markSeen` 은 TX 밖(Redis), `processMessage` 는 `@Transactional` 로 DB. Redis 되돌림 자체가 실패하면 ("Redis 커넥션 flap") dedupe 가 박혀 있고 TX 도 롤백되어 **메시지가 영구 dedupe 된 채 처리 0건** 으로 남는다.
   - Kafka consumer 는 offset 을 커밋하지 않을 테니 재컨슘이 들어올 테지만, 새 메시지의 eventUuid 가 여전히 Redis 에 세팅되어 있어 skip 된다 → 결제 상태 전이가 영구 누락.

## Findings

### Finding 1 — FAILED 재고 복원 qty=0 (critical)

- **checklist_item**: 보상/취소 로직 멱등성 가드 (실질적으로 "보상 경로가 동작하는가" 의 upstream)
- **location**: `payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/application/usecase/PaymentConfirmResultUseCase.java:97-109` + `.../StockRestoreEventKafkaPublisher.java:37-54`
- **problem**: FAILED 분기가 `publishPayload(qty 포함)` 가 아닌 `publish(orderId, productIds)` 를 호출해 qty=0 이 consumer 로 전달. product-service 가 `current + 0 = current` 로 저장 → FAIL 결제의 재고가 영구히 복원되지 않는다.
- **evidence**: `StockRestoreEventKafkaPublisher.publish(String, List<Long>)` 내부 하드코딩 `new StockRestoreEvent(eventUUID, orderId, productId, 0, now)` + 주석 "qty 정보가 없는 레거시 진입 경로로, qty=0 플레이스홀더로 발행한다". `PaymentConfirmResultUseCase.handleFailed` 는 이 오버로드만 호출.
- **suggestion**: `handleFailed` 에서 `PaymentOrder` 별로 `FailureCompensationService.compensate(orderId, productIds, qty)` 또는 `publishPayload(StockRestoreEventPayload)` 를 호출해 실제 `order.getQuantity()` 를 전달. 레거시 `publish(orderId, productIds)` 오버로드는 제거 또는 예외 throw 로 안전하게 닫기.

### Finding 2 — payment dedupe TTL 1시간 vs Kafka retention 7일 비대칭 (critical)

- **checklist_item**: 보상/취소 로직 멱등성 가드
- **location**: `payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/dedupe/EventDedupeStoreRedisAdapter.java:31`
- **problem**: `@Value("${payment.event-dedupe.ttl:PT1H}")` 기본 1h. application.yml 에 override 없음. Kafka retention 7일·consumer lag 복구·DLQ 재처리 주기보다 훨씬 짧아 **동일 eventUuid 재컨슘 시 dedupe 우회** 가능.
- **evidence**: product-service `StockRestoreUseCase.DEDUPE_TTL=8일` 과 비교. STATE.md 상에도 "TTL=8일" 로 표기돼 있지만 payment-service 쪽은 1h 기본값 유지. grep 결과 override 0건.
- **suggestion**: TTL 을 Kafka retention 과 정렬 (기본 8일 또는 `PT192H` 이상) + application.yml 에 명시. Phase 4 k6 장애 시나리오에서 이 값이 드러날 여지 큼 — 값 합의 후 문서화.

### Finding 3 — HTTP 어댑터 traceparent 전파 누락 (major)

- **checklist_item**: (도메인 추가) "사고 재구성 가능성"
- **location**: `payment-service/src/main/java/com/hyoguoo/paymentplatform/core/common/infrastructure/http/HttpOperatorImpl.java:31-34` + `.../adapter/http/ProductHttpAdapter.java:71, 81` + `pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/infrastructure/http/HttpOperatorImpl.java:32-38`
- **problem**: WebClient/RestClient 를 수동 빌드하며 `observationRegistry(...)` 미주입. 어댑터 호출부는 `Map.of()` 를 headers 로 전달해 수동 전파도 없음. 결제 사고 시 payment-service → product/user-service HTTP 홉에서 trace 가 끊긴다.
- **evidence**: 빌드 시 `WebClient.builder()` 직접 호출, DI 된 `ObservationRegistry` 참조 없음. 어댑터는 `httpOperator.requestGet(url, Map.of(), ...)`.
- **suggestion**: `WebClient.Builder` / `RestClient.Builder` 를 Spring Boot auto-config 에서 주입받아 `.observationRegistry(registry)` 를 적용하거나, 어댑터에서 현재 MDC 의 traceId/spanId 를 W3C `traceparent` 포맷으로 조립해 헤더 전달.

### Finding 4 — Redis stock DECR 과 confirm TX 비원자성 (major)

- **checklist_item**: race window 에 락/트랜잭션 격리
- **location**: `payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/application/OutboxAsyncConfirmService.java:49-68` + `.../PaymentTransactionCoordinator.java:45-95`
- **problem**: `decrementStock` 이 TX 밖 Redis DECR. SUCCESS 후 `executeConfirmTx` TX 내부에서 `confirmPublisher.publish` 실패 또는 commit 실패 시 Redis 재고는 차감된 채 남는다. 명시적 보상 경로 없음.
- **evidence**: `OutboxAsyncConfirmService.confirm` 코드 구조. `PaymentReconciler` 가 있으나 즉시성 없는 정합 도구.
- **suggestion**: (a) Redis DECR 을 TX 후 `@TransactionalEventListener(AFTER_COMMIT)` 로 옮기거나, (b) `executeConfirmTx` 실패 시 `stockCachePort.increment(...)` 보상을 caller 에서 명시. 최소한 Phase 4 장애 주입 전에 경로 선택 필요.

### Finding 5 — QuarantineCompensationHandler 종결 상태 역전이 guard 부재 (major)

- **checklist_item**: 상태 전이 불변식 (SUCCESS → QUARANTINED 역전이 금지)
- **location**: `payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/application/usecase/QuarantineCompensationHandler.java:41-49`
- **problem**: event 를 조회한 뒤 현재 상태 검증 없이 `markPaymentAsQuarantined` 호출. 동일 orderId 가 이미 DONE/FAILED 종결됐을 때 이 메시지가 뒤늦게 도착하면 종결 상태가 QUARANTINED 로 강등될 위험. 종결 후 QUARANTINED 로 복귀하면 조회 API 응답·재고 상태가 흔들린다.
- **evidence**: handler 코드 본문에 `isTerminal()` 체크 없음. `PgDlqService.handle` 은 inbox 에서 `inbox.getStatus().isTerminal()` no-op 체크가 있지만 payment-service 쪽은 없음.
- **suggestion**: `handle` 진입 시 `event.getStatus().isTerminal()` 이면 no-op + INFO 로그. domain `markPaymentAsQuarantined` 가드도 이중 방어.

### Finding 6 — PaymentConfirmResultUseCase dedupe Redis 장애 시 영구 누락 (major)

- **checklist_item**: 멱등성 가드의 "실패 경로 안전성"
- **location**: `payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/application/usecase/PaymentConfirmResultUseCase.java:46-62`
- **problem**: `markSeen` 후 `processMessage` 가 throw 하면 `remove(eventUuid)` 호출. Redis 호출이 flap 으로 실패 (Phase 4 Toxiproxy 시나리오 가능) 시 dedupe 가 박힌 상태에서 TX 도 롤백되어 **재컨슘 시에도 markSeen=false 반환 → 처리 영구 스킵**.
- **evidence**: `catch (RuntimeException e) { eventDedupeStore.remove(message.eventUuid()); throw e; }` 블록 자체가 Redis 예외에 비내성. `remove` 실패 처리 경로 없음.
- **suggestion**: (a) `markSeen` 을 DB 기반으로 옮기거나 (payment 용 dedupe table), (b) `remove` 실패 시에도 메시지를 재처리할 수 있도록 DLQ 전송, (c) TTL 을 짧게 유지하되 정상 처리 마커와 실패 마커를 분리.

### Finding 7 — DuplicateApprovalHandler 위임 시 eventUuid 자리에 orderId 전달 (minor)

- **checklist_item**: 향후 멱등성 키 정합성
- **location**: `pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/infrastructure/gateway/toss/TossPaymentGatewayStrategy.java:148-149`
- **problem**: `duplicateApprovalHandler.handleDuplicateApproval(request.orderId(), request.amount(), request.orderId())` 세 번째 인자가 orderId. 시그니처는 eventUuid.
- **evidence**: 현재 handler 내부에서 eventUuid 미사용이지만 의도치 않은 값이 박혀 있음. Nicepay 쪽도 동일 패턴일 가능성 — 확인 필요 (해당 라인 `NicepayPaymentGatewayStrategy.java:161` 에 유사 호출 있음).
- **suggestion**: `request.eventUuid()` 필드가 있는지 확인 후 전달, 없으면 handler 파라미터에서 삭제하거나 명시적으로 `null` 전달로 의도 표현.

### Finding 8 — outbox relay 의 headers_json empty-map 하드코딩 (minor)

- **checklist_item**: (도메인 추가) 사고 재구성 가능성
- **location**: `pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/application/service/PgOutboxRelayService.java:83-92`
- **problem**: `parseHeaders` 가 `Map.of()` 를 반환하도록 하드코딩. DLQ/재시도 outbox row 에는 `attempt` 카운터 같은 커스텀 헤더가 저장돼 있지만 발행 시 사라짐. 원본 traceId 헤더가 있었더라도 발행 시점엔 현재 span 으로 덮여써진다.
- **evidence**: 주석 "실제 Map 파싱은 T2a-05a 범위에서 단순 empty-map 처리로 제한한다. (T2b 이후 실제 헤더 활용 시 ObjectMapper 주입으로 확장)" — 후속 Phase 에서 미완.
- **suggestion**: ObjectMapper 주입 후 `headers_json` → `Map<String, byte[]>` 역직렬화. 최소한 `traceparent` 헤더는 보존.

### Finding 9 — QUARANTINED 운영자 복구 경로 문서 공백 (minor)

- **checklist_item**: 복구 가능성 문서화
- **location**: `docs/context/TODOS.md` + `docs/context/ARCHITECTURE.md` (Quarantine flow 섹션)
- **problem**: FCG INDETERMINATE 로 QUARANTINED 홀딩된 event 를 실제 벤더 상태 확인 후 어떤 Admin 경로로 DONE/FAILED 전이시키는지 기술 없음. 재고·돈 동결 상태에서 운영자 개입 SLA·대시보드 연결 불분명.
- **evidence**: TODOS.md 에는 "Redis 캐시 장애 즉시 격리 → FAILED 전환 검토" 만 있고 FCG INDETERMINATE 경로는 언급 없음.
- **suggestion**: ARCHITECTURE.md Quarantine flow 섹션에 (a) 운영자 진입 API/Admin UI 경로, (b) QUARANTINED → DONE/FAILED 전이 감독 도구, (c) 알림 임계값 문서화.

## JSON

```json
{
  "stage": "code",
  "persona": "domain-expert",
  "round": 1,
  "task_id": null,

  "decision": "fail",
  "reason_summary": "FAILED 경로 재고 복원 qty=0 하드코딩 + payment dedupe TTL 1h vs Kafka retention 7일 비대칭 두 건의 critical 리스크가 확인됨. 이 상태로 Phase 4 장애 주입 진입 시 재고·돈 정합성 회귀가 쉽게 재현될 것.",

  "checklist": {
    "source": "_shared/checklists/code-ready.md#domain-risk",
    "items": [
      {
        "section": "domain risk",
        "item": "보상/취소 로직에 멱등성 가드 존재",
        "status": "no",
        "evidence": "PaymentConfirmResultUseCase.handleFailed가 qty=0 플레이스홀더로 stock.events.restore를 발행해 product-service에서 재고가 복원되지 않음 (StockRestoreEventKafkaPublisher.java:37-54)"
      },
      {
        "section": "domain risk",
        "item": "race window에 락/트랜잭션 격리 고려됨",
        "status": "no",
        "evidence": "OutboxAsyncConfirmService.confirm에서 Redis DECR이 TX 외부이고 후속 executeConfirmTx 실패 시 Redis 보상 경로 없음; PaymentConfirmResultUseCase의 markSeen→remove 경로가 Redis flap 시 영구 dedupe 잠김 위험"
      },
      {
        "section": "domain risk",
        "item": "상태 전이 불변식 위반 없음",
        "status": "no",
        "evidence": "QuarantineCompensationHandler.handle은 사전 isTerminal 체크 없이 markPaymentAsQuarantined 호출 → 종결 상태의 역전이 위험 (QuarantineCompensationHandler.java:41-49)"
      },
      {
        "section": "domain risk",
        "item": "PG ALREADY_PROCESSED 계열 특수 응답이 정당성 검증을 거침",
        "status": "yes",
        "evidence": "TossPaymentGatewayStrategy.handleErrorResponse → DuplicateApprovalHandler로 위임, amount 대조 후 inbox CAS 전이"
      },
      {
        "section": "domain risk",
        "item": "PII/paymentKey plaintext 로그 노출 없음",
        "status": "yes",
        "evidence": "LogFmt 로그에 orderId/paymentKey는 있으나 카드번호·고객정보 없음; 결제 ID는 내부 키"
      },
      {
        "section": "domain risk (추가)",
        "item": "사고 재구성을 위한 traceId가 다중 홉에 연속 전파됨",
        "status": "no",
        "evidence": "HttpOperatorImpl이 수동 WebClient/RestClient 빌드로 observationRegistry 미주입 + ProductHttpAdapter가 Map.of() headers 전달 (HttpOperatorImpl.java:31-34, ProductHttpAdapter.java:71)"
      },
      {
        "section": "domain risk (추가)",
        "item": "멱등성 키 TTL이 Kafka retention·DLQ 재처리 주기와 정렬됨",
        "status": "no",
        "evidence": "payment-service TTL 기본 1h (EventDedupeStoreRedisAdapter.java:31, application.yml override 0건) vs Kafka retention 7일"
      }
    ],
    "total": 7,
    "passed": 2,
    "failed": 5,
    "not_applicable": 0
  },

  "scores": {
    "correctness": 0.55,
    "conventions": 0.78,
    "discipline": 0.82,
    "test-coverage": 0.68,
    "domain": 0.48,
    "mean": 0.66
  },

  "findings": [
    {
      "severity": "critical",
      "checklist_item": "보상/취소 로직 멱등성 가드 + 실제 재고 복원 동작",
      "location": "payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/application/usecase/PaymentConfirmResultUseCase.java:97-109 및 .../infrastructure/messaging/publisher/StockRestoreEventKafkaPublisher.java:37-54",
      "problem": "FAILED 분기가 qty=0 플레이스홀더로 stock.events.restore를 발행해 product-service가 재고를 실제로 복원하지 않음. FAIL 결제의 재고가 영구 고립된다.",
      "evidence": "StockRestoreEventKafkaPublisher.publish(String, List<Long>) 하드코딩 qty=0 + 주석 '레거시 진입 경로, qty=0 플레이스홀더'. PaymentConfirmResultUseCase.handleFailed는 이 오버로드만 호출하여 FailureCompensationService.compensate(qty 포함) 경유하지 않음.",
      "suggestion": "handleFailed에서 PaymentOrder 별로 publishPayload(StockRestoreEventPayload) 또는 FailureCompensationService.compensate(orderId, productIds, qty) 호출해 실제 수량 전달. 레거시 publish(orderId, productIds) 오버로드는 제거 또는 UnsupportedOperationException throw."
    },
    {
      "severity": "critical",
      "checklist_item": "이벤트 dedupe TTL과 메시지 보관·재처리 주기의 정렬",
      "location": "payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/dedupe/EventDedupeStoreRedisAdapter.java:31",
      "problem": "payment-service dedupe TTL 기본 1시간. Kafka retention 7일·DLQ 재처리·consumer lag 복구 윈도우보다 훨씬 짧아 동일 eventUuid 재컨슘 시 dedupe 우회. PaymentEvent 상태 이중 전이 + stock.events.commit/restore 이중 발행으로 이어짐.",
      "evidence": "@Value('${payment.event-dedupe.ttl:PT1H}') + application.yml grep 결과 override 0건. product-service StockRestoreUseCase.DEDUPE_TTL은 Duration.ofDays(8)로 대응됨 — 같은 플랫폼에서 TTL 정책 충돌.",
      "suggestion": "기본값을 최소 Kafka retention(7일) + 버퍼(1일) = 8일로 재설정하고 application.yml에 명시. 문서 동기화(STATE.md 현재 'TTL=8일' 문구가 payment-service 에는 맞지 않음)."
    },
    {
      "severity": "major",
      "checklist_item": "다중 홉 traceId 연속성 (사고 재구성)",
      "location": "payment-service/src/main/java/com/hyoguoo/paymentplatform/core/common/infrastructure/http/HttpOperatorImpl.java:31-34; .../payment/infrastructure/adapter/http/ProductHttpAdapter.java:71, 81; pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/infrastructure/http/HttpOperatorImpl.java:32-38",
      "problem": "HTTP 클라이언트를 수동 빌드로 생성하고 ObservationRegistry를 주입하지 않아 Micrometer Tracing auto-instrumentation이 적용되지 않음. 어댑터도 Map.of() 헤더로 traceparent를 수동 전파하지 않아 payment-service→product/user-service 홉에서 trace 단절.",
      "evidence": "WebClient.builder().clientConnector(...).build() + RestClient.builder().requestFactory(...).build() 모두 observationRegistry(...) 미호출. 어댑터의 httpOperator.requestGet(url, Map.of(), ...) 호출 패턴.",
      "suggestion": "Spring Boot auto-config의 WebClient.Builder / RestClient.Builder를 주입받아 .observationRegistry(registry) 적용 또는 어댑터에서 MDC의 traceId/spanId를 W3C traceparent로 조립해 헤더 전달. 최소한 compose-up 스모크에서 traceId 연속 검증을 추가."
    },
    {
      "severity": "major",
      "checklist_item": "race window에 락/트랜잭션 격리 — Redis DECR 과 TX 원자성",
      "location": "payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/application/OutboxAsyncConfirmService.java:49-68 + .../PaymentTransactionCoordinator.java:45-95",
      "problem": "Redis 재고 DECR이 TX 외부. SUCCESS 후 executeConfirmTx 내부 confirmPublisher.publish 또는 TX commit이 실패하면 Redis 재고는 차감된 채 남고 payment_event/outbox는 롤백. 명시적 보상 경로 없음.",
      "evidence": "OutboxAsyncConfirmService.confirm 분기 구조 + PaymentTransactionCoordinator.executeConfirmTx 내부. PaymentReconciler는 정합 도구이나 즉시성 없음.",
      "suggestion": "Redis DECR을 @TransactionalEventListener(AFTER_COMMIT)로 옮기거나, executeConfirmTx 실패 시 stockCachePort.increment(...) 보상을 caller에서 호출. Phase 4 Toxiproxy Redis flap 시나리오 전에 경로 택일."
    },
    {
      "severity": "major",
      "checklist_item": "상태 전이 불변식 — 종결 상태 역전이 금지",
      "location": "payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/application/usecase/QuarantineCompensationHandler.java:41-49",
      "problem": "event 조회 후 isTerminal 체크 없이 markPaymentAsQuarantined 호출. 이미 DONE/FAILED 된 event가 뒤늦은 QUARANTINED 메시지로 역전이될 위험.",
      "evidence": "handle 메서드 본문에 가드 없음. PgDlqService.handle은 inbox.getStatus().isTerminal() no-op 체크 존재 — 일관성 결여.",
      "suggestion": "handle 진입 시 event.getStatus().isTerminal()이면 INFO 로그 후 no-op. 도메인 markPaymentAsQuarantined에도 이중 가드."
    },
    {
      "severity": "major",
      "checklist_item": "멱등성 가드의 실패 경로 안전성",
      "location": "payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/application/usecase/PaymentConfirmResultUseCase.java:46-62",
      "problem": "markSeen(Redis) → processMessage(TX) → 실패 시 remove(Redis) 경로. Redis 호출이 flap 으로 실패하면 dedupe 키가 박힌 채 TX는 롤백되어 재컨슘 시에도 markSeen=false → 처리 영구 스킵.",
      "evidence": "catch (RuntimeException e) { eventDedupeStore.remove(message.eventUuid()); throw e; } — remove 실패 처리 없음. Redis 장애 시 stuck.",
      "suggestion": "(a) dedupe를 DB 기반(payment-service 전용 event_dedupe table)으로 이관, (b) remove 실패 시 DLQ 전송 또는 별도 알림, (c) 실패 후 재처리를 강제하는 compensation 플래그 도입."
    },
    {
      "severity": "minor",
      "checklist_item": "멱등성 키 파라미터 정합성 (향후 사용 대비)",
      "location": "pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/infrastructure/gateway/toss/TossPaymentGatewayStrategy.java:148-149; .../nicepay/NicepayPaymentGatewayStrategy.java:161 유사 패턴",
      "problem": "DuplicateApprovalHandler.handleDuplicateApproval 의 3번째 인자(eventUuid) 자리에 orderId가 전달됨. 현재 handler에서 eventUuid 미사용이지만 향후 멱등성 키로 활용 시 동일 orderId의 반복 호출이 동일 'eventUuid'로 처리되어 의도와 다른 dedupe 동작.",
      "evidence": "TossPaymentGatewayStrategy.java:148-149 `duplicateApprovalHandler.handleDuplicateApproval(request.orderId(), request.amount(), request.orderId())`. 시그니처 `(String orderId, BigDecimal payloadAmount, String eventUuid)`.",
      "suggestion": "PgConfirmRequest에 eventUuid 필드가 있는지 확인 후 전달. 없으면 파라미터 제거 또는 명시적 null로 의도 표현."
    },
    {
      "severity": "minor",
      "checklist_item": "DLQ/outbox 경로 traceId 보존",
      "location": "pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/application/service/PgOutboxRelayService.java:83-92",
      "problem": "parseHeaders가 Map.of() 하드코딩. outbox 에 저장된 headers_json(예: attempt, 원본 traceparent)이 발행 시 사라짐.",
      "evidence": "주석 '실제 Map 파싱은 T2a-05a 범위에서 단순 empty-map 처리로 제한한다. (T2b 이후 실제 헤더 활용 시 ObjectMapper 주입으로 확장)' — 후속 Phase에서 미구현.",
      "suggestion": "ObjectMapper 주입 후 headers_json → Map<String, byte[]> 역직렬화. 최소한 traceparent 헤더는 보존."
    },
    {
      "severity": "minor",
      "checklist_item": "QUARANTINED 홀딩 자산 복구 경로 문서화",
      "location": "docs/context/ARCHITECTURE.md (Quarantine flow 섹션) + docs/context/TODOS.md",
      "problem": "FCG INDETERMINATE로 QUARANTINED된 event를 어떤 운영 도구로 최종 상태(DONE/FAILED)로 전이시키는지 문서화 없음. 재고/돈 동결 자산의 복구 SLA 불분명.",
      "evidence": "TODOS.md는 Redis 캐시 장애 격리 경로 검토만 기재. ARCHITECTURE.md Quarantine flow 섹션은 전이만 기술. Admin API/대시보드 링크 없음.",
      "suggestion": "ARCHITECTURE.md Quarantine flow에 (a) 운영자 Admin API 경로, (b) 감독 대시보드 링크, (c) 알림 임계값 및 대응 런북을 추가."
    }
  ],

  "previous_round_ref": null,
  "delta": null,
  "unstuck_suggestion": null
}
```
