# code-quality-domain-1

도메인 craftsmanship 관점에서 본 결과 — `PaymentEvent.done()` 의 DONE→DONE 비대칭 가드, `StockCommittedEvent.idempotencyKey=orderId` 단일 값 사용으로 인한 multi-product 결제의 RDB 재고 누락(critical), `ConfirmedEventPayload`/`ConfirmedEventMessage` 두 record 의 필드 순서 불일치, `PgInbox` 의 anemic domain (state 전이가 SQL UPDATE 만으로 강제됨), pg-service 와 payment-service 의 mutable vs immutable 도메인 패턴 비대칭 등 다수의 craftsmanship 결함이 식별됨. 1 critical + 5 major + 7 minor → fail.

## Reasoning

review-domain-3 까지의 라운드는 비즈니스 회귀 (실제 사용 시 일어나는 결제 사고) 를 막는 데 집중한 반면, 이번 craftsmanship 라운드는 도메인 모델 자체의 설계 적정성을 본다. 그 결과 `StockCommittedEvent.idempotencyKey` 가 multi-product 결제에서 collision 을 일으키는 명백한 도메인 사고 회귀가 발견되었고, `PgInbox` 가 사실상 anemic 한 데이터 캐리어로 퇴화한 점, payment-service 와 pg-service 가 각자 다른 mutation 컨벤션을 쓰는 점 등 도메인 일관성이 흔들리는 표면이 다수 발견되었다.

## Domain risk checklist

이번 라운드는 도메인 craftsmanship 관점으로 운영되므로 표준 verify-ready 체크리스트보다 review 코드 품질 게이트(`docs/CLAUDE.md` review 단독 스킬 가이드) 의 도메인 컬럼을 적용한다.

- 도메인 enum + isTerminal SSOT — `PaymentEventStatus.isTerminal()` 은 캡슐화 잘됨. 그러나 `PaymentOutboxStatus` 는 isTerminal 미보유 — partial.
- value object 부재 (Money/OrderId/PaymentKey) — primitive obsession.
- intent-revealing 메서드 (event.done(), event.fail()) — 일관 적용. 단 `PgInbox` 는 anemic, withStatus/withResult 미사용.
- 불변식 강제 — `PaymentEvent.done()` 이 DONE→DONE 자기 전이 허용하는 비대칭(타 전이는 isTerminal 가드 우선).
- aggregate 경계 — `PaymentEvent.paymentOrderList` 가 mutable List 그대로 노출됨.
- 멱등성/dedupe 패턴 — `StockCommittedEvent.idempotencyKey` 가 orderId 단일 값 → multi-product collision (critical).
- TTL/Clock 주입 — `FailureCompensationService`, `StockOutboxRelayService`, `PgInboxRepositoryImpl` 등 다수 위치에서 `LocalDateTime.now()` / `Instant.now()` 직접 호출.
- 외부 인터페이스 record 정합성 — `ConfirmedEventPayload` vs `ConfirmedEventMessage` 필드 순서 비대칭, `StockCommittedEvent` vs `StockCommittedMessage` 필드 갯수 비대칭.
- 도메인 표현 — 중복 errorCode 코드 (E03002 두 곳), 미사용 `INVALID_STATUS_TO_QUARANTINE`, IllegalStateException 직접 throw (도메인 예외 패턴 우회).
- 영향 범위 응집 — `freshOutbox.getStatus() == PaymentOutboxStatus.IN_FLIGHT` 같은 외부 분기가 application 계층에 산발.

## 도메인 관점 추가 검토

도메인 일관성·정확성 측면에서 craftsmanship 결함을 1 critical / 5 major / 7 minor 로 식별. 자세한 내용은 아래 Findings.

## Findings

1. **[CRITICAL]** Multi-product 결제에서 stock commit 의 dedupe key 충돌 — `paymentEvent.getOrderId()` 가 모든 PaymentOrder 에 동일하게 사용됨.
2. **[MAJOR]** `PaymentEvent.done()` 이 DONE→DONE 자기 전이를 허용 — `fail()` 의 isTerminal 가드 패턴과 비대칭.
3. **[MAJOR]** `ConfirmedEventPayload` (pg-service) 와 `ConfirmedEventMessage` (payment-service) 두 record 의 필드 순서가 다름 — 동일 토픽, 독립 record 의 동기화 약속 (ADR-30) 이 깨질 위험.
4. **[MAJOR]** `StockCommittedEvent` (producer 4 fields) vs `StockCommittedMessage` (consumer 6 fields) 의 스키마 불일치 — orderId/expiresAt 누락 + orderId 타입 불일치 (String 짜리 producer 필드를 Long 타입으로 expect).
5. **[MAJOR]** `PgInbox` 도메인이 anemic — 모든 상태 전이가 SQL `transitXxx` UPDATE 로만 강제, 도메인 객체에 transition method 없음. payment-service `PaymentEvent` 와 정반대 컨벤션.
6. **[MAJOR]** `PaymentOutbox.incrementRetryCount()` 가 현재 status 검증 없이 항상 PENDING 으로 되돌림 — DONE/FAILED outbox 가 호출되어도 silent 하게 다시 PENDING + nextRetryAt 으로 활성화됨.
7. **[MINOR]** `DuplicateApprovalHandler.buildApprovedPayload` 가 vendor 실제 approvedAt 없을 때 `Clock.now()` 로 fabricated approvedAt 을 생성 — 감사·정산 추적이 흔들림.
8. **[MINOR]** `PaymentErrorCode.INVALID_STATUS_TO_EXECUTE` 와 `INVALID_TOTAL_AMOUNT` 가 동일 코드 `E03002` 사용 (중복).
9. **[MINOR]** `PaymentEvent.quarantine()` 가 `IllegalStateException` 을 직접 throw — 다른 도메인 메서드는 `PaymentStatusException.of(PaymentErrorCode)` 패턴. `INVALID_STATUS_TO_QUARANTINE` 코드는 정의됐지만 미사용.
10. **[MINOR]** `PaymentOutboxStatus` enum 에 `isTerminal()` SSOT 미보유 — `PaymentEventStatus` 와 비대칭. application 계층이 `== IN_FLIGHT` 등 raw 비교를 함.
11. **[MINOR]** primitive obsession — `BigDecimal amount`, `String orderId`, `String paymentKey`, `String eventUuid` 가 도메인 전반에 노출. `Money(currency, amount)`, `OrderId`, `PaymentKey` value object 부재. `PaymentConfirmResultUseCase.isAmountMismatch` 의 `getTotalAmount().longValueExact()` 인라인이 `pg.AmountConverter.fromBigDecimalStrict` 와 비대칭.
12. **[MINOR]** `JpaPgInboxRepository` 의 JPQL 쿼리들이 `'NONE'`, `'IN_PROGRESS'` 등 enum 값을 문자열 리터럴로 박음 — `PgInboxStatus` 이름 변경시 컴파일 안 깨지고 silent break.
13. **[MINOR]** Clock 주입 부재 — `FailureCompensationService.compensate` (line 72, 75), `StockOutboxRelayService.relay` (line 47), `PgInboxRepositoryImpl.transitXxx` 메서드들이 `LocalDateTime.now()`/`Instant.now()` 직접 호출. `LocalDateTimeProvider` 패턴이 부분 적용됨.

### 1. [CRITICAL] Multi-product stock commit 의 dedupe key 충돌

- **location**: `payment-service/.../payment/application/usecase/PaymentConfirmResultUseCase.java:232-238` (`buildStockCommitOutbox`)
- **problem**: 동일 결제(여러 PaymentOrder)일 때 모든 commit 이벤트의 `idempotencyKey` 가 `paymentEvent.getOrderId()` 단일 값으로 설정된다. `product-service` 의 `StockCommitUseCase.commit` 는 `EventDedupeStore.recordIfAbsent(eventUUID, ...)` 로 dedupe — 첫 번째 product 가 commit 후 두 번째 이후 product 는 `firstSeen=false` 로 skip 된다. 결과: Redis 캐시는 각 product 의 `setStock` 이 호출되지만 RDB UPDATE 는 첫 번째 product 만 적용되어 영구 RDB↔Redis 불일치가 발생한다.
- **evidence**:
  - `PaymentConfirmResultUseCase.handleApproved` 가 `for (PaymentOrder order : paymentEvent.getPaymentOrderList())` 루프로 `buildStockCommitOutbox` 호출 (line 217-221).
  - `buildStockCommitOutbox` 의 line 236: `paymentEvent.getOrderId()` 를 `idempotencyKey` 로 박음 (모든 product 에 동일).
  - `product-service/.../StockCommitUseCase.commit` line 60-65: `eventUUID` 로 dedupe → first product 처리 후 두 번째 product 는 `STOCK_COMMIT_DUPLICATE` 로그만 남기고 RDB UPDATE 건너뜀.
  - `FailureCompensationService.deriveEventUUID` (line 91) 는 정확히 이 문제를 피하려 `"stock-restore:{orderId}:{productId}"` UUID v3 도출 패턴을 쓰는데, commit 측은 동일 패턴이 부재.
  - 테스트 `PaymentConfirmResultUseCaseD2Test` line 105-117 는 outbox row 수가 2 인 것만 검증하고 dedupe key 가 diverge 하는지는 검증하지 않음 → 회귀가 잡히지 않음.
- **suggestion**: `idempotencyKey` 도출을 `"stock-commit:{orderId}:{productId}"` 결정론적 UUID v3 으로 변경 (`FailureCompensationService.deriveEventUUID` 와 동일 패턴 재사용). 또는 `(orderId + ":" + productId)` 키로 직접 사용. 동시에 `PaymentConfirmResultUseCaseD2Test` 에 multi-product fixture + `idempotencyKey` 가 product 별로 다름 검증 추가.

### 2. [MAJOR] `PaymentEvent.done()` DONE→DONE self-transition 허용 비대칭

- **location**: `payment-service/.../payment/domain/PaymentEvent.java:97-111`
- **problem**: `done()` 메서드는 `status == DONE` 일 때 통과시키는데, 그 안에서 `paymentOrderList.forEach(PaymentOrder::success)` 를 호출한다. `PaymentOrder.success()` 는 `status != EXECUTING` 이면 throw → DONE 상태에서 재호출되면 `INVALID_STATUS_TO_SUCCESS` 예외가 터진다. 의도가 "isTerminal 시 no-op" 이라면 `fail()` 처럼 상단에서 isTerminal 가드를 두어야 한다. 의도가 "DONE 재호출 차단" 이라면 DONE 을 허용 목록에서 빼야 한다. 현재 코드는 둘 다 아닌 어중간한 상태로 의미가 모호하다.
- **evidence**:
  - `PaymentEvent.fail()` line 113-126 은 첫 줄에서 `if (isTerminalStatus()) return;` 가드 후 본문 진행 — 의도가 명확.
  - `PaymentEvent.done()` line 97-111 은 동일 가드 없이 DONE 을 허용 목록에 포함시키고 본문에서 paymentOrderList::success 까지 호출 — 종결 보호와 재진입 허용이 일관되지 않음.
  - `PaymentConfirmResultUseCase` 가 dedupe lease 로 재진입을 차단하지만 lease 누락 시 도메인이 마지막 방어선이 되어야 함.
- **suggestion**: `done()` 의 첫 줄에 `if (this.status == PaymentEventStatus.DONE) return;` no-op 가드 추가 + 허용 목록에서 DONE 제거. `MISSING_APPROVED_AT` 가드는 그 뒤로 옮긴다.

### 3. [MAJOR] `ConfirmedEventPayload` vs `ConfirmedEventMessage` 필드 순서 비대칭

- **location**:
  - `pg-service/.../infrastructure/messaging/event/ConfirmedEventPayload.java:22-29`
  - `payment-service/.../infrastructure/messaging/consumer/dto/ConfirmedEventMessage.java:21-28`
- **problem**: 두 record 모두 동일 토픽(`payment.events.confirmed`) 의 동일 메시지를 표현하지만 필드 순서가 다르다.
  - Producer (`ConfirmedEventPayload`): `orderId, status, reasonCode, amount, approvedAt, eventUuid`
  - Consumer (`ConfirmedEventMessage`): `orderId, status, reasonCode, eventUuid, amount, approvedAt`
  Jackson 이 필드명 기반 역직렬화를 해주므로 동작은 하지만, ADR-30 의 "공유 jar 없이 양쪽이 동기화되어야 한다" 약속이 한 record 에 필드를 추가할 때 자동으로 동기화되지 않을 위험을 키운다. 새 필드를 한쪽에만 추가하고 누락한 채 PR 통과 가능 (Jackson 은 missing field 를 null 로 채움). 도메인 craftsmanship 관점에서 이 비대칭 자체가 결함.
- **evidence**: 두 record 의 필드 선언 순서가 다름 (위 위치 참고).
- **suggestion**: 양 record 의 필드 순서를 동일하게 맞추고, 동기화 강제를 위해 단위 테스트를 추가 — `ConfirmedEventPayloadSchemaParityTest` 같은 테스트로 두 record 의 component 이름 set + 순서 비교. 추가로 `@JsonPropertyOrder` 명시.

### 4. [MAJOR] `StockCommittedEvent` 와 `StockCommittedMessage` 의 스키마 불일치

- **location**:
  - `payment-service/.../infrastructure/messaging/event/StockCommittedEvent.java:14-20` (producer, 4 필드)
  - `product-service/.../infrastructure/messaging/consumer/dto/StockCommittedMessage.java:21-28` (consumer, 6 필드)
- **problem**:
  - Producer: `productId(Long), qty(int), idempotencyKey(String), occurredAt(Instant)`
  - Consumer: `productId(Long), qty(int), idempotencyKey(String), occurredAt(Instant), orderId(Long), expiresAt(Instant)`
  Consumer 가 보유한 `orderId(Long)` 와 `expiresAt` 은 producer 가 발행하지 않으므로 항상 null. 더 심각한 점은 consumer 의 `orderId` 가 `Long` 타입인데 payment-service 의 `paymentEvent.getOrderId()` 는 `String` (예: `"order-001"`) — 만약 producer 가 `orderId` 필드를 추가하면 형 변환 오류로 폭주.
  - `StockCommitConsumer.consume` line 65: `message.orderId() != null ? message.orderId() : 0L` — 항상 0L 폴백 (현재 producer 발행 안하므로). 이것이 dedupe table 에 fallback 0L 으로 기록되면 추적·복구 시 의미 없는 키.
- **evidence**: 두 record 의 필드 갯수·타입 비교.
- **suggestion**: producer 의 `StockCommittedEvent` 에 `orderId(String)` 와 `expiresAt(Instant)` 추가하고 consumer 의 `orderId` 도 String 으로 통일. 또는 consumer 의 `orderId(Long)` 와 `expiresAt` 필드 삭제 (필요하면 idempotencyKey 로 충분).

### 5. [MAJOR] `PgInbox` 도메인이 anemic — 상태 전이가 도메인이 아닌 SQL UPDATE 에 캡슐화

- **location**:
  - `pg-service/.../pg/domain/PgInbox.java:83-103` (`withStatus`, `withResult` 정의됨, 호출 0회)
  - `pg-service/.../pg/infrastructure/repository/PgInboxRepositoryImpl.java:46-83` (모든 전이가 SQL UPDATE)
  - `pg-service/.../pg/infrastructure/repository/JpaPgInboxRepository.java:31-68` (JPQL 로 status 리터럴 박음)
- **problem**: `PgInbox.withStatus`/`withResult` 메서드는 정의되어 있지만 한 번도 호출되지 않는다. 모든 상태 전이는 `PgInboxRepositoryImpl.transitNoneToInProgress`/`transitToApproved`/`transitToFailed`/`transitToQuarantined` 의 SQL UPDATE 로 수행됨. 도메인은 사실상 데이터 캐리어 (anemic). state machine 의 진실은 JPQL 문자열에 있다 (`'NONE'`, `'IN_PROGRESS'` 리터럴).
  - payment-service 의 `PaymentEvent` 는 `event.done()`, `event.fail()`, `event.toRetrying()`, `event.quarantine()` 등 intent-revealing 메서드로 도메인이 자기 불변식 강제 — 두 도메인이 정반대 컨벤션을 사용한다.
  - `transitToApproved` 가 `void` 반환 (`PgInboxRepositoryImpl.java:66`) 이라 호출자는 CAS 실패(예: 이미 QUARANTINED 상태) 여부를 알 수 없음. 반면 `transitNoneToInProgress` 는 `boolean`. 비일관.
- **evidence**: `withStatus`/`withResult` 호출 grep 결과 0 hit (line 83-103 자체 정의 외).
- **suggestion**: `PgInbox` 에 도메인 메서드 추가 — `markInProgress`, `markApproved(storedStatusResult, vendorAmount, approvedAt)`, `markFailed(reasonCode)`, `markQuarantined(reasonCode)` 각각이 status guard + storedStatusResult/reasonCode 일관성 검증을 수행. Repository 는 `save(PgInbox)` 만 노출. SQL CAS 가 race window 가드로 필요하다면 dual write — 도메인이 먼저 검증, repository 가 CAS 로 동시성 가드.

### 6. [MAJOR] `PaymentOutbox.incrementRetryCount` 의 status 가드 부재

- **location**: `payment-service/.../payment/domain/PaymentOutbox.java:56-60`
- **problem**: 이 메서드는 현재 status 와 무관하게 `retryCount++ + status = PENDING + nextRetryAt = ...` 를 무조건 수행. DONE 또는 FAILED outbox 가 어떤 경유로 이 메서드 호출되면 silent 하게 PENDING 으로 되돌아가 워커가 다시 픽업한다 — 이미 종결된 결제가 다시 Kafka 발행되는 회귀 가능성.
  - `toInFlight()`/`toDone()`/`toFailed()` 는 모두 status guard 가 있는데 `incrementRetryCount` 만 누락 — 도메인 craftsmanship 비일관.
- **evidence**: line 34-54 의 `toInFlight`/`toDone`/`toFailed` 모두 `if (this.status != ...) throw ...` 가드. line 56 만 무가드.
- **suggestion**: `incrementRetryCount` 첫 줄에 `if (this.status != PaymentOutboxStatus.IN_FLIGHT) throw PaymentStatusException.of(PaymentErrorCode.INVALID_STATUS_TO_RETRY)` 추가 — IN_FLIGHT 에서만 retry 전이 허용.

### 7. [MINOR] `DuplicateApprovalHandler.buildApprovedPayload` 의 fabricated approvedAt

- **location**: `pg-service/.../pg/application/service/DuplicateApprovalHandler.java:293-298`
- **problem**: pg DB 부재 + vendor 응답 부재 시 `OffsetDateTime.now(clock).toString()` 으로 인공 approvedAt 을 생성하고 payment-service 까지 전파 — payment-service 가 이 값을 `PaymentEvent.approvedAt` 으로 영구 기록한다. 운영자가 vendor 콘솔 vs payment_event 시각 diff 로 reconciliation 할 때 misleading.
- **evidence**: line 293-298. `// Clock fallback 으로 현재 UTC 시각을 주입한다` 주석으로 의도 명시되어 있으나 운영 영향 미고려.
- **suggestion**: synthetic approvedAt 임을 표시하는 별도 필드 (`approvedAtSource: VENDOR | SYNTHETIC`) 또는 reasonCode 에 `SYNTHETIC_APPROVED_AT` 부착. 또는 vendor 호출 결과 응답 자체에 approvedAt 이 포함되도록 `PgStatusResult` 확장.

### 8. [MINOR] `PaymentErrorCode` 코드 중복 (E03002)

- **location**: `payment-service/.../payment/exception/common/PaymentErrorCode.java:12-13`
- **problem**: `INVALID_STATUS_TO_EXECUTE("E03002", ...)` 와 `INVALID_TOTAL_AMOUNT("E03002", ...)` 가 동일 코드 `E03002` 사용. 코드는 식별자 역할이므로 unique 가 craftsmanship 의 기본.
- **evidence**: 위 line.
- **suggestion**: 둘 중 하나의 코드를 변경 (예: `INVALID_TOTAL_AMOUNT` → `E03006` 또는 다음 미사용 코드). PR 단위 회귀 방지 위해 `PaymentErrorCodeUniquenessTest` 에서 코드 unique 검증 추가.

### 9. [MINOR] `PaymentEvent.quarantine()` 의 도메인 예외 패턴 우회

- **location**: `payment-service/.../payment/domain/PaymentEvent.java:157-166`
- **problem**: 다른 모든 상태 전이는 `PaymentStatusException.of(PaymentErrorCode.X)` 패턴이지만 `quarantine()` 만 `IllegalStateException` 직접 throw. ResponseAdvice/`PaymentExceptionHandler` 는 PaymentStatusException 만 매핑 — IllegalStateException 은 GlobalExceptionHandler 에서 500 으로 처리되어 클라이언트에 의미있는 에러 코드를 못 줌. 또한 정의된 `INVALID_STATUS_TO_QUARANTINE("E03026")` 코드가 정의만 되고 미사용.
- **evidence**: line 160-161 의 `throw new IllegalStateException(...)` vs 다른 메서드들의 `throw PaymentStatusException.of(...)`.
- **suggestion**: `throw PaymentStatusException.of(PaymentErrorCode.INVALID_STATUS_TO_QUARANTINE)` 으로 통일.

### 10. [MINOR] `PaymentOutboxStatus` 에 `isTerminal()` SSOT 메서드 부재

- **location**: `payment-service/.../payment/domain/enums/PaymentOutboxStatus.java`
- **problem**: 4개 status (`PENDING`, `IN_FLIGHT`, `DONE`, `FAILED`) 가 단순 enum 으로만 정의됨. application 계층(`PaymentTransactionCoordinator.java:146`) 에서 `freshOutbox.getStatus() == PaymentOutboxStatus.IN_FLIGHT` 같은 raw 비교 필요 — `PaymentEventStatus.isTerminal()` SSOT 패턴과 비대칭.
- **evidence**: enum 정의에 메서드 부재.
- **suggestion**: `isTerminal()` (DONE/FAILED → true), `isClaimable()` (PENDING → true), `isInFlight()` (IN_FLIGHT → true) 추가 + 외부 raw 비교를 메서드 호출로 치환.

### 11. [MINOR] primitive obsession — Money/OrderId/PaymentKey value object 부재

- **location**: 도메인 전반.
  - `PaymentEvent.orderId: String`, `PaymentEvent.paymentKey: String`
  - `PaymentOrder.totalAmount: BigDecimal` (currency 정보 없음)
  - `ConfirmedEventMessage.amount: Long` (minor unit 가정)
  - `PaymentConfirmResultUseCase.isAmountMismatch:277` 의 인라인 `getTotalAmount().longValueExact()` — pg-service 의 `AmountConverter.fromBigDecimalStrict` 같은 통합 유틸이 payment-service 에는 부재.
- **problem**: 화폐 단위 (KRW vs minor unit) 가 도메인 약속에만 의존, 컴파일러 강제 부재. 잘못된 conversion 이 silent 통과 가능.
- **evidence**: 위 위치 + `pg.AmountConverter` 와 payment-service 인라인 변환 비대칭.
- **suggestion**: `Money(currency, amount)` value object 도입 또는 최소한 `payment-service` 에 동일한 `AmountConverter` 복제 (ADR-30 정책 — 공통 lib 금지). 인라인 `longValueExact` 호출을 `AmountConverter.fromBigDecimalStrict` 로 일원화.

### 12. [MINOR] `JpaPgInboxRepository` 의 JPQL 에 enum 값 리터럴 박음

- **location**: `pg-service/.../infrastructure/repository/JpaPgInboxRepository.java:31-68`
- **problem**: 4개 JPQL 쿼리가 `'NONE'`, `'IN_PROGRESS'`, `'APPROVED'`, `'FAILED'`, `'QUARANTINED'` 를 문자열 리터럴로 박음. `PgInboxStatus` enum 값 이름이 변경되면 컴파일러가 잡지 못하고 런타임 silent break.
- **evidence**: line 32-33, 40-41, 50-52, 63-65.
- **suggestion**: JPQL 의 `WHERE e.status = :status` 형태로 파라미터화 + Java 측에서 `PgInboxStatus.NONE` 등 enum 전달. 또는 statics 로 enum 명을 상수화하고 컴파일 시 검증되는 메커니즘 도입.

### 13. [MINOR] Clock 주입 부재 — 도메인 시간 제어 누설

- **location**:
  - `payment-service/.../payment/application/service/FailureCompensationService.java:72, 75` — `Instant.now()`, `LocalDateTime.now()` 직접 호출
  - `payment-service/.../payment/application/service/StockOutboxRelayService.java:47` — `LocalDateTime.now()`
  - `payment-service/.../payment/application/usecase/PaymentConfirmResultUseCase.java:237` — `Instant.now()` (event occurredAt)
  - `pg-service/.../infrastructure/repository/PgInboxRepositoryImpl.java:50, 67-68, 74-75, 81-82` — `LocalDateTime.now(ZoneOffset.UTC)`
  - `pg-service/.../pg/domain/PgInbox.java:91, 102` — `Instant.now()` (도메인 안에서!)
- **problem**: `LocalDateTimeProvider` 가 일부에 적용되지만 일관 적용 부재. 특히 `PgInbox.withStatus/withResult` 가 도메인 내부에서 `Instant.now()` 호출 → 테스트 시 시간 고정 불가, 전이 시각 검증이 불안정.
- **evidence**: 위 line 들 + `LocalDateTimeProvider` 인터페이스가 일부 use-case 만 적용된 상태.
- **suggestion**: `Clock` 또는 `LocalDateTimeProvider` 를 `FailureCompensationService`, `StockOutboxRelayService`, `PgInboxRepositoryImpl` 까지 일관 주입. `PgInbox` 의 `withStatus`/`withResult` 는 `Instant updatedAt` 파라미터로 받도록 변경 (또는 anemic 우회 — finding 5 와 함께 처리).

## JSON

```json
{
  "stage": "code",
  "persona": "domain-expert",
  "round": 4,
  "task_id": "code-quality",

  "decision": "fail",
  "reason_summary": "Multi-product 결제의 stock commit dedupe key 충돌(critical)이 추가 결제 사고 회귀를 일으킬 수 있고, PaymentEvent.done() / PaymentOutbox.incrementRetryCount 의 가드 비대칭, ConfirmedEvent record 필드 순서 비대칭, PgInbox anemic 도메인, StockCommittedEvent 스키마 불일치 등 도메인 craftsmanship 결함 다수.",

  "checklist": {
    "source": "review-domain craftsmanship 컬럼 (.claude/skills/_shared/checklists 기준 review 단독 게이트)",
    "items": [
      {
        "section": "domain integrity",
        "item": "도메인 상태 머신이 enum + 메서드로 캡슐화되고 외부 분기로 새지 않음",
        "status": "no",
        "evidence": "PaymentOutboxStatus.isTerminal 부재 + PaymentTransactionCoordinator:146 raw 비교; PgInbox 모든 전이가 SQL UPDATE."
      },
      {
        "section": "domain integrity",
        "item": "도메인 메서드의 status 가드가 일관되게 적용됨",
        "status": "no",
        "evidence": "PaymentEvent.done() DONE→DONE 허용 비대칭, PaymentOutbox.incrementRetryCount 무가드, PaymentEvent.quarantine() IllegalStateException 우회."
      },
      {
        "section": "idempotency",
        "item": "stock 이벤트 dedupe key 가 (orderId, productId) 단위로 결정론적",
        "status": "no",
        "evidence": "PaymentConfirmResultUseCase.buildStockCommitOutbox:236 에서 idempotencyKey=orderId 단일 값 사용 → multi-product collision."
      },
      {
        "section": "external interface",
        "item": "독립 복제 record 의 필드 순서·타입·갯수 동기화",
        "status": "no",
        "evidence": "ConfirmedEventPayload vs ConfirmedEventMessage 필드 순서 다름; StockCommittedEvent(4) vs StockCommittedMessage(6) 갯수 다름 + orderId 타입 String/Long 불일치."
      },
      {
        "section": "naming and code consistency",
        "item": "에러 코드/예외 패턴이 일관 적용",
        "status": "no",
        "evidence": "PaymentErrorCode E03002 중복; INVALID_STATUS_TO_QUARANTINE 미사용; PaymentEvent.quarantine() IllegalStateException 우회."
      },
      {
        "section": "time control",
        "item": "도메인·application 의 시간 의존이 Clock/Provider 로 일관 주입",
        "status": "no",
        "evidence": "FailureCompensationService, StockOutboxRelayService, PgInboxRepositoryImpl, PgInbox 도메인 내부에서 LocalDateTime.now()/Instant.now() 직접 호출."
      },
      {
        "section": "domain richness",
        "item": "value object 로 primitive obsession 회피",
        "status": "no",
        "evidence": "Money/OrderId/PaymentKey 부재; payment-service 에 AmountConverter 부재 (pg-service 만 보유)."
      }
    ],
    "total": 7,
    "passed": 0,
    "failed": 7,
    "not_applicable": 0
  },

  "scores": {
    "correctness": 0.55,
    "conventions": 0.60,
    "discipline": 0.65,
    "test_coverage": 0.62,
    "domain": 0.50,
    "mean": 0.584
  },

  "findings": [
    {
      "severity": "critical",
      "checklist_item": "stock 이벤트 dedupe key 가 (orderId, productId) 단위로 결정론적",
      "location": "payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/application/usecase/PaymentConfirmResultUseCase.java:232-238",
      "problem": "buildStockCommitOutbox 가 idempotencyKey 를 paymentEvent.getOrderId() 로 설정 — multi-product 결제 시 모든 product 의 commit 이벤트가 동일 dedupe key 를 가져, product-service StockCommitUseCase 가 첫 번째 이벤트만 처리하고 두 번째 이후는 중복 skip. 결과: Redis 는 setStock 으로 갱신되지만 RDB UPDATE 는 첫 product 만 적용 → 영구 RDB↔Redis 불일치.",
      "evidence": "PaymentConfirmResultUseCase.handleApproved:217-221 의 PaymentOrder loop 가 동일 idempotencyKey=orderId 로 stock_outbox 다중 INSERT. product-service/StockCommitUseCase.commit:60-65 의 EventDedupeStore.recordIfAbsent 가 두 번째 이벤트를 firstSeen=false 로 skip. 비교: FailureCompensationService.deriveEventUUID:91 는 stock-restore 측은 정확히 (orderId, productId) UUID v3 도출. 테스트 PaymentConfirmResultUseCaseD2Test:105-117 는 outbox row 갯수만 검증 → 회귀 미검출.",
      "suggestion": "buildStockCommitOutbox 에서 deriveEventUUID(orderId, productId, prefix='stock-commit') 패턴으로 변경. 또는 idempotencyKey = orderId + ':' + productId. 동일 정신으로 D2Test 에 multi-product fixture 와 product 별 idempotencyKey 검증 추가."
    },
    {
      "severity": "major",
      "checklist_item": "도메인 메서드의 status 가드가 일관되게 적용됨",
      "location": "payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/domain/PaymentEvent.java:97-111",
      "problem": "done() 가 status==DONE 자기 전이를 허용하지만 그 안에서 PaymentOrder.success() 호출 — 이미 SUCCESS 인 PaymentOrder 에서 INVALID_STATUS_TO_SUCCESS 예외가 터진다. fail() 의 isTerminal no-op 패턴과 비대칭으로 의도 모호.",
      "evidence": "fail():113-116 의 isTerminalStatus → return; vs done():101-104 의 DONE 허용 + paymentOrderList.forEach(::success):110.",
      "suggestion": "done() 첫 줄에 if (status==DONE) return; 가드 추가 + 허용 목록에서 DONE 제거. 또는 isTerminal no-op 패턴으로 통일."
    },
    {
      "severity": "major",
      "checklist_item": "독립 복제 record 의 필드 순서·타입·갯수 동기화",
      "location": "pg-service/.../ConfirmedEventPayload.java:22-29 vs payment-service/.../ConfirmedEventMessage.java:21-28",
      "problem": "동일 토픽 메시지를 표현하는 두 record 의 필드 순서가 다름. ADR-30 의 양방향 동기화 약속이 자동화되지 않음 — Jackson 이 missing field 를 null 로 허용하므로 한쪽에 새 필드 추가 후 다른 쪽 누락이 PR 통과 가능.",
      "evidence": "Payload: orderId, status, reasonCode, amount, approvedAt, eventUuid. Message: orderId, status, reasonCode, eventUuid, amount, approvedAt. 필드 순서 diff.",
      "suggestion": "필드 순서 통일 + ConfirmedEventPayloadSchemaParityTest 같은 reflection 기반 단위 테스트로 양 record 의 component 이름 set 비교. @JsonPropertyOrder 명시."
    },
    {
      "severity": "major",
      "checklist_item": "독립 복제 record 의 필드 순서·타입·갯수 동기화",
      "location": "payment-service/.../StockCommittedEvent.java:14-20 vs product-service/.../StockCommittedMessage.java:21-28",
      "problem": "Producer record(4 필드) vs Consumer record(6 필드) 불일치. Consumer 의 orderId(Long)·expiresAt 은 producer 가 발행 안 함 (항상 null). 또한 producer 측 paymentEvent.orderId 는 String 이라 만약 producer 에 orderId 추가 시 Long 캐스팅 충돌.",
      "evidence": "Producer 필드 4개. Consumer 필드 6개. StockCommitConsumer.consume:65 의 message.orderId() != null ? message.orderId() : 0L fallback. expiresAt 도 동일하게 fallback (StockCommitConsumer:80-88).",
      "suggestion": "Producer 에 orderId(String) + expiresAt(Instant) 필드 추가, Consumer 의 orderId 도 String 으로 통일. 또는 consumer 의 두 필드 삭제 (idempotencyKey 만으로 dedupe 충분)."
    },
    {
      "severity": "major",
      "checklist_item": "도메인 상태 머신이 enum + 메서드로 캡슐화되고 외부 분기로 새지 않음",
      "location": "pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/domain/PgInbox.java:83-103 + pg-service/.../infrastructure/repository/PgInboxRepositoryImpl.java:46-83",
      "problem": "PgInbox 의 withStatus/withResult 메서드는 정의됐지만 호출처 0건. 모든 상태 전이는 PgInboxRepositoryImpl 의 transitXxx SQL UPDATE 로 수행됨. 도메인은 anemic 데이터 캐리어. payment-service PaymentEvent 의 intent-revealing 메서드 컨벤션과 정반대 — 동일 프로젝트 내 두 도메인 일관성 결여.",
      "evidence": "withStatus 호출 grep 결과 정의 외 0건. transitToApproved 가 void 반환 (CAS 실패 신호 유실), transitNoneToInProgress 는 boolean 반환 — 비일관.",
      "suggestion": "PgInbox 에 markInProgress/markApproved/markFailed/markQuarantined 도메인 메서드 추가. 가드 + storedStatusResult/reasonCode 일관성 검증. Repository 는 save(PgInbox) 만 노출. CAS 동시성은 SQL 가드로 dual write."
    },
    {
      "severity": "major",
      "checklist_item": "도메인 메서드의 status 가드가 일관되게 적용됨",
      "location": "payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/domain/PaymentOutbox.java:56-60",
      "problem": "incrementRetryCount 가 현재 status 무관하게 항상 PENDING 으로 되돌림. DONE/FAILED outbox 가 호출되어도 silent 하게 다시 PENDING + nextRetryAt 으로 활성화. 종결 outbox 재발행 회귀 가능.",
      "evidence": "toInFlight():35-40, toDone():43-47, toFailed():50-54 모두 status guard 보유. line 56 의 incrementRetryCount 만 무가드.",
      "suggestion": "incrementRetryCount 첫 줄에 if (this.status != PaymentOutboxStatus.IN_FLIGHT) throw PaymentStatusException.of(PaymentErrorCode.INVALID_STATUS_TO_RETRY) 추가. 호출 경로(executePaymentRetryWithOutbox)는 항상 IN_FLIGHT 에서 진입하므로 정상 흐름은 영향 없음."
    },
    {
      "severity": "minor",
      "checklist_item": "외부 인터페이스 도메인 정합성",
      "location": "pg-service/.../service/DuplicateApprovalHandler.java:293-298",
      "problem": "DB absent 경로에서 vendor 응답에 approvedAt 이 없을 때 OffsetDateTime.now(clock).toString() 으로 fabricated approvedAt 생성. payment-service 가 이를 PaymentEvent.approvedAt 으로 영구 기록 → 운영자 reconciliation 시 vendor 콘솔 시각과 불일치하는 추적 어려운 데이터 발생.",
      "evidence": "buildApprovedPayload(orderId, amount):293-298 의 'OffsetDateTime.now(clock).toString()' 주석에 의도 명시되어 있으나 운영 영향 미고려.",
      "suggestion": "ConfirmedEventPayload 또는 reasonCode 에 SYNTHETIC_APPROVED_AT 마커 추가. 장기적으로 PgStatusResult 가 vendor approvedAt raw 문자열을 반환하도록 확장."
    },
    {
      "severity": "minor",
      "checklist_item": "에러 코드/예외 패턴이 일관 적용",
      "location": "payment-service/.../exception/common/PaymentErrorCode.java:12-13",
      "problem": "INVALID_STATUS_TO_EXECUTE 와 INVALID_TOTAL_AMOUNT 가 동일 코드 E03002 사용. 코드는 unique 식별자가 기본.",
      "evidence": "위 line 양쪽 모두 'E03002'.",
      "suggestion": "INVALID_TOTAL_AMOUNT 코드를 다음 미사용 번호로 변경. PaymentErrorCodeUniquenessTest 추가."
    },
    {
      "severity": "minor",
      "checklist_item": "에러 코드/예외 패턴이 일관 적용",
      "location": "payment-service/.../payment/domain/PaymentEvent.java:157-166",
      "problem": "quarantine() 가 IllegalStateException 직접 throw — 다른 도메인 메서드는 PaymentStatusException.of 패턴. INVALID_STATUS_TO_QUARANTINE(E03026) 코드는 정의만 되고 미사용.",
      "evidence": "line 160-161 의 throw new IllegalStateException vs 다른 메서드들의 PaymentStatusException.of.",
      "suggestion": "throw PaymentStatusException.of(PaymentErrorCode.INVALID_STATUS_TO_QUARANTINE) 으로 통일."
    },
    {
      "severity": "minor",
      "checklist_item": "도메인 상태 머신이 enum + 메서드로 캡슐화되고 외부 분기로 새지 않음",
      "location": "payment-service/.../payment/domain/enums/PaymentOutboxStatus.java",
      "problem": "isTerminal/isClaimable/isInFlight 같은 SSOT 메서드 부재. PaymentTransactionCoordinator:146 등 외부에서 raw 비교 (== IN_FLIGHT). PaymentEventStatus.isTerminal 컨벤션과 비대칭.",
      "evidence": "enum 정의에 메서드 0개.",
      "suggestion": "isTerminal(DONE/FAILED), isClaimable(PENDING), isInFlight(IN_FLIGHT) 추가 + 외부 raw 비교를 메서드 호출로 치환."
    },
    {
      "severity": "minor",
      "checklist_item": "value object 로 primitive obsession 회피",
      "location": "payment-service domain 전반 + PaymentConfirmResultUseCase.java:277",
      "problem": "Money/OrderId/PaymentKey value object 부재. amount: BigDecimal, orderId: String, paymentKey: String 광범위 노출. payment-service 측에 pg-service 의 AmountConverter 와 같은 통합 변환 유틸 부재로 인라인 longValueExact 호출 발생.",
      "evidence": "PaymentEvent.amount: BigDecimal, ConfirmedEventMessage.amount: Long (minor unit 가정), PaymentConfirmResultUseCase.isAmountMismatch:277 의 인라인 longValueExact.",
      "suggestion": "Money(currency, amount) 도입 또는 최소한 AmountConverter 를 payment-service 에 복제 (ADR-30 정책). isAmountMismatch 에서 AmountConverter 호출."
    },
    {
      "severity": "minor",
      "checklist_item": "도메인 상태 머신이 enum + 메서드로 캡슐화되고 외부 분기로 새지 않음",
      "location": "pg-service/.../infrastructure/repository/JpaPgInboxRepository.java:31-68",
      "problem": "JPQL 쿼리들이 'NONE'/'IN_PROGRESS'/'APPROVED'/'FAILED'/'QUARANTINED' 를 문자열 리터럴로 박음. PgInboxStatus 이름 변경 시 컴파일 안 깨지고 silent break.",
      "evidence": "casNoneToInProgress/casInProgressToApproved/casInProgressToFailed/casNonTerminalToQuarantined 4개 쿼리에 status 리터럴.",
      "suggestion": "status 를 :param 으로 받고 Java 측 PgInboxStatus.NONE 등 enum 전달. 또는 컴파일 시 검증되는 메커니즘."
    },
    {
      "severity": "minor",
      "checklist_item": "도메인·application 의 시간 의존이 Clock/Provider 로 일관 주입",
      "location": "payment-service FailureCompensationService.java:72,75 + StockOutboxRelayService.java:47 + PaymentConfirmResultUseCase.java:237 + pg-service PgInboxRepositoryImpl.java:50,67-68,74-75,81-82 + PgInbox.java:91,102",
      "problem": "LocalDateTimeProvider 가 일부에 적용되지만 일관 부재. PgInbox 도메인 내부에서 Instant.now() 호출 → 테스트 시 시간 고정 불가.",
      "evidence": "위 line 들 + LocalDateTimeProvider 인터페이스가 일부 use-case 만 적용된 상태.",
      "suggestion": "Clock/LocalDateTimeProvider 일관 주입 + PgInbox 의 withStatus/withResult 가 Instant 파라미터를 받도록 변경 (또는 anemic 처리와 함께 finding 5 처리)."
    }
  ],

  "previous_round_ref": null,
  "delta": null,
  "unstuck_suggestion": null
}
```
