# code-critic-1

**Topic**: PRE-PHASE-4-HARDENING
**Round**: 1
**Persona**: Critic
**Stage**: code (pre-Phase-4 하드닝 기준선 리뷰)

## Reasoning

세 축 모두에서 critical 이슈가 있다. 축 1(비즈니스 로직)에서는 `PaymentConfirmResultUseCase.handleApproved` 가 PG 발행 `approvedAt` 과 `amount` 를 수신 payload(`ConfirmedEventMessage`)에 포함시키지 않아 `LocalDateTime.now()` 로 승인 시각을 위조하고 금액 재검증도 못 한다 — ADR-15 AMOUNT_MISMATCH 불변식이 pg-service 내부에만 갇혀 있다. 축 2(traceId 연속성)에서는 `PgOutboxImmediateWorker` 가 `Executors.newVirtualThreadPerTaskExecutor()` 로 작업을 submit 할 때 MDC/OpenTelemetry context 를 명시적으로 전파하지 않아 relay 로그에 traceId 가 누락될 수 있고, payment-service `OutboxWorker.processParallel` 에도 같은 문제가 있다. 축 3(코드 작성)에서는 `FakePgGatewayStrategy.getStatusByOrderId` 가 `return null` 로 포트 계약을 깨뜨리고, `ARCHITECTURE.md` 는 payment-service `OutboxImmediateWorker` 를 상세 기술하지만 실제 소스 트리에는 존재하지 않는다(documentation drift). 전체 gate 는 통과했지만 Phase 4 장애 주입 진입 전에 이 4건은 반드시 해소해야 한다.

## Checklist judgement

### task execution
- RED/GREEN/REFACTOR 커밋 흐름: **n/a** (리뷰 라운드, 복수 태스크 기준선)
- 커밋 메시지 포맷: **yes** — `feat:`/`test:`/`refactor:`/`chore:`/`docs:` 일관.
- STATE.md active task 갱신: **yes** (Phase 3.5 완료, Phase 4 대기 기록).

### test gate
- 전체 `./gradlew test` 통과: **yes** (STATE.md 및 phase-3-5-gate.md 에서 461 PASS 명시, 본 리뷰는 재실행 없이 기록 수락).
- 신규/수정 로직 테스트 커버리지: **yes** (PgEventPublisherTest, ProductHttpAdapterContractTest, UserHttpAdapterContractTest, DuplicateApprovalHandlerCircularDependencyTest, PgOutboxImmediateWorkerTest @RepeatedTest(50) 존재).
- 신 state machine 전이 `@ParameterizedTest`: **n/a** (신규 상태 전이 없음, 기존 5상태 유지).

### convention
- Lombok `@RequiredArgsConstructor`/`@Getter`/`@Data` 금지: **yes**.
- 신규 로깅 LogFmt 사용: **no** — `FakePgGatewayStrategy` 의 배너 로그 4줄이 평문 `log.warn(...)` 이다. 주석에 "의도적 평문" 이라 명시했지만 CONVENTIONS 에는 예외 조항 없음. minor 로 처리.
- `null` 반환 금지: **no** — `FakePgGatewayStrategy.getStatusByOrderId` (line 92) 이 `return null` 로 `PgStatusLookupPort` 계약을 위반. critical 해당.
- `catch (Exception e)`: **no** — 프로덕션 경로에 7건 잔존. `PgOutboxImmediateWorker`(line 112, 122), `PgOutboxPollingWorker`(line 64), `DomainEventLoggingAspect`(line 48), `PaymentHistoryServiceImpl`(line 31), `StockSnapshotWarmupConsumer`(line 49), `TossApiMetricsAspect`(line 44). 재throw 경로는 수용 가능하지만 worker/aspect 의 `catch(Exception) → log+swallow` 패턴은 CONVENTIONS 의 "없다면 `handleUnknownFailure` 경유" 예외를 이탈. major.

### execution discipline
- 범위 밖 코드 수정 없음: **yes** (T3.5 스코프 내).
- 분석 마비: **n/a** (리뷰 라운드).

### domain risk (critic 도 체크)
- 보상/취소 로직 멱등성 가드: **yes** — `PgOutboxRelayService.relay` 가 `processedAt != null` / `available_at > now` 로 skip 처리. `DuplicateApprovalHandler` 는 2경로 모두 inbox `findByOrderId` 로 재진입 방어.
- PG "이미 처리됨" 응답 정당성 검증: **yes** — `DuplicateApprovalHandler.queryVendorStatus` 1회 호출 후 금액 대조, INDETERMINATE → QUARANTINED 로 격리.
- 상태 전이 불변식 (SUCCESS→FAIL 금지 등): **no** — `PaymentConfirmResultUseCase.handleApproved` 가 `LocalDateTime.now()` 로 `approvedAt` 을 조작하여 "벤더 승인 시각"을 잃는다(축 1 결함). `PaymentEvent.done(approvedAt=now)` 호출 시 `MISSING_APPROVED_AT` 은 피하지만 의미론적으로 잘못된 timestamp. critical.
- race window 의 락/트랜잭션: **yes** — `PaymentConfirmResultUseCase.handle` 에서 `markSeen → TX → remove on exception` 보상 패턴, `PaymentTransactionCoordinator.executePaymentFailureCompensationWithOutbox` 의 D12 가드 잘 구현됨. 단, Virtual Thread relayExecutor 쪽 MDC 누락(축 2).
- paymentKey/카드번호 평문 로그 노출: **yes** — `FakePgGatewayStrategy.maskKey` 적용, production adapter 도 key 직접 로그 없음 확인.

## Findings

### [critical-1] PG approvedAt / amount 가 payment-service 에 전달되지 않음

- **severity**: critical
- **checklist_item**: domain risk / 상태 전이가 불변식을 위반하지 않음
- **location**:
  - `payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/messaging/consumer/dto/ConfirmedEventMessage.java`:15-20
  - `pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/infrastructure/messaging/event/ConfirmedEventPayload.java`:20-46
  - `payment-service/.../PaymentConfirmResultUseCase.java`:85
- **problem**: pg-service 가 APPROVED 를 발행할 때 `ConfirmedEventPayload.approved(orderId, eventUuid)` 는 `amount=null, approvedAt 없음` 으로 만든다. payment-service 수신 record `ConfirmedEventMessage(orderId, status, reasonCode, eventUuid)` 에 `approvedAt` 필드조차 없다. `handleApproved` 는 `paymentEvent.done(LocalDateTime.now(), LocalDateTime.now())` 로 "지금" 을 `approvedAt` 으로 박는다. 결과: (1) 실제 벤더 승인 시각과 DB 기록 시각이 수초~수분 차이 날 수 있음 (장애/지연 시 확대), (2) 금액을 payment-service 에서 재검증 못 해 AMOUNT_MISMATCH 방어선이 pg-service 에만 존재 — 역방향 오염(payment_event 총액 vs 벤더 승인액 불일치)을 놓친다.
- **evidence**:
  - `PaymentConfirmResultUseCase.java:85`: `paymentEvent.done(LocalDateTime.now(), LocalDateTime.now());`
  - `ConfirmedEventMessage.java`: record 에 `approvedAt`/`amount` 필드 없음.
  - `ConfirmedEventPayload.approved` 팩토리: `amount=null`.
- **suggestion**: `ConfirmedEventPayload` + `ConfirmedEventMessage` 에 `Long amount`, `String approvedAt`(ISO-8601) 필드 추가. pg-service `PgConfirmService` / `DuplicateApprovalHandler` / FCG 경로에서 항상 벤더 실제 승인 시각·금액을 payload 에 태운다. payment-service `handleApproved` 는 전달받은 `approvedAt` 을 `PaymentEvent.done()` 에 주입하고, `paymentEvent.getTotalAmount()` 와 수신 `amount` 가 불일치 시 `QUARANTINED(AMOUNT_MISMATCH)` 로 격리(역방향 방어선). ADR-15 불변식을 양쪽에서 지킨다.

### [critical-2] FakePgGatewayStrategy.getStatusByOrderId null 반환 — 포트 계약 위반

- **severity**: critical
- **checklist_item**: convention / null 반환 금지, Optional 사용
- **location**: `pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/infrastructure/gateway/fake/FakePgGatewayStrategy.java`:86-93
- **problem**: `PgStatusLookupPort.getStatusByOrderId` 구현체인 Fake 가 `return null;` 로 빠져나온다. 주석은 "smoke happy path 에서는 호출되지 않는다" 라 하지만, `DuplicateApprovalHandler.queryVendorStatus`(line 156) 가 실제로 이 포트를 호출하고 결과를 `vendorStatus.amount()` 로 바로 접근한다 → NPE. smoke 프로파일에서 중복 승인 / FCG 경로가 트리거되면 즉시 NPE 로 compose-up 스모크가 꺼진다. 더 중요한 건 **계약**: Port 구현체는 null 금지가 규약이며, "호출되지 않아야 한다" 는 의도는 계약으로 표현돼야 한다 (`UnsupportedOperationException` 또는 INDETERMINATE 결과 반환).
- **evidence**:
  - `FakePgGatewayStrategy.java:92`: `return null;`
  - `DuplicateApprovalHandler.java:137-138`: `PgStatusResult vendorStatus = queryOutcome.statusResult; long vendorAmountLong = AmountConverter.fromBigDecimalStrict(vendorStatus.amount());` — null 방어 없음.
  - CONVENTIONS.md "null 반환 금지, Optional 사용" 규약.
- **suggestion**: `return null` 대신 `throw new UnsupportedOperationException("Fake strategy: getStatusByOrderId 경로는 smoke 에서 예상되지 않음")` 또는 `PgGatewayRetryableException` 을 던져 `DuplicateApprovalHandler` 의 INDETERMINATE 경로로 유도. 반환 타입이 절대 null 일 수 없어야 한다는 포트 불변식을 명시적으로 보존.

### [major-1] Virtual Thread executor 에 MDC/OTel context 전파 없음 — traceId 체인 단절

- **severity**: major
- **checklist_item**: domain risk / race window 가 있는 경로에 고려됨 (+ 축 2 traceId 연속성)
- **location**:
  - `pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/scheduler/PgOutboxImmediateWorker.java`:59, 105-117
  - `payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/scheduler/OutboxWorker.java`:48-53
  - `payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/listener/OutboxImmediateEventHandler.java`:35-39 (@Async)
- **problem**: `PgOutboxImmediateWorker.workerLoop` 에서 `channel.take()` 수신 직후 `relayExecutor.submit(() -> relay(id))` 로 재제출한다. `relayExecutor = Executors.newVirtualThreadPerTaskExecutor()` 는 MDC(SLF4J) 나 OpenTelemetry Context 를 자동 전파하지 않는다. Kafka listener 에서 만들어진 traceId 가 channel.offer(id) 경유로 `Long` 만 넘기므로, relay 쪽에서 로그를 찍을 때 `[traceId:N/A]` 로 떨어진다. payment-service `OutboxWorker.processParallel` 도 동일. `OutboxImmediateEventHandler.@Async("outboxRelayExecutor")` 도 `TaskDecorator` 미설정 시 같은 문제. CONVENTIONS.md "5 서비스 `logback-spring.xml` 패턴에 `[traceId:%X{traceId:-N/A}]` 포함 — 로그 라인 grep 으로 전 서비스 span 추적 가능" 불변식이 outbox relay 경로에서 실제로 깨진다.
- **evidence**:
  - `PgOutboxImmediateWorker.java:59`: `relayExecutor = Executors.newVirtualThreadPerTaskExecutor();` — MDC adapter 없음.
  - 같은 파일 line 109: `relayExecutor.submit(() -> relay(id));` — 람다 내부에서 MDC snapshot 복원 없음.
  - `OutboxWorker.java:50-52`: 동일 패턴, `try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor())` + `executor.submit(() -> outboxRelayService.relay(...))`.
  - CONVENTIONS.md §관측성 규약 T3.5-13 line 360: "로그 라인 grep 으로 전 서비스 span 추적 가능".
- **suggestion**: (1) VT executor 를 `ContextExecutorService.wrap(executor, ContextSnapshotFactory.builder().build())` (Micrometer Context Propagation) 로 감싸거나, (2) 람다에서 `Map<String,String> ctx = MDC.getCopyOfContextMap()` 스냅샷 → 실행 스레드 진입 시 `MDC.setContextMap(ctx)` + try/finally 로 clear 를 수행하는 소형 헬퍼(`MdcPreservingExecutor`) 를 표준 유틸로 추가. `@Async` 는 `ThreadPoolTaskExecutor.setTaskDecorator(new MdcTaskDecorator())` 로 같은 효과. Phase 4 Tempo 분산 추적 전에 이 3곳을 모두 보정하지 않으면 장애 주입 시 어느 relay 가 어느 HTTP 요청에 귀속되는지 추적 불가.

### [major-2] ARCHITECTURE.md documentation drift — payment-service OutboxImmediateWorker 실존하지 않음

- **severity**: major
- **checklist_item**: execution discipline / 범위 밖 코드 수정 없음 (문서 정합성 sub-judgement)
- **location**:
  - `docs/context/ARCHITECTURE.md`:83-88, 101-146 (Confirm Flow 섹션)
  - 실제 소스: `payment-service/src/main/java/.../payment/scheduler/` 에 `OutboxImmediateWorker.java` 부재. `OutboxWorker.java`, `PaymentScheduler.java` 만 존재.
  - 실제 구현: `payment/listener/OutboxImmediateEventHandler.java` (`@Async` + `@TransactionalEventListener`).
- **problem**: ARCHITECTURE.md §Scheduler 섹션과 §Confirm Flow 블록 다이어그램이 "OutboxImmediateWorker (SmartLifecycle, VT/PT 워커 N개, channel.take())" 를 세밀하게 서술한다. 실제 payment-service 코드는 `@Async("outboxRelayExecutor")` 리스너 방식이고 `PaymentConfirmChannel` 도 payment-service 에 존재하지 않는다(grep 결과 0). 즉 ARCHITECTURE.md 는 pg-service `PgOutboxImmediateWorker` 의 구조를 payment-service 에 투영한 레거시 문서. Phase 4 진입 전 "완벽" 기준선에서 문서 drift 는 장애 시나리오 작성의 근거를 흔든다(어느 스레드 모델에 장애를 주입할지 헷갈림).
- **evidence**:
  - `find payment-service -name "OutboxImmediateWorker*"` → hit 0.
  - `grep -rn "PaymentConfirmChannel\|LinkedBlockingQueue" payment-service/src/main` → 0 hit.
  - `OutboxImmediateEventHandler.java:35-39`: `@TransactionalEventListener + @Async` 실제 구현.
  - ARCHITECTURE.md:83 "OutboxImmediateWorker — SmartLifecycle 구현체; 앱 시작 시 N개(기본 200개)의 VT/PT 워커 스레드를 생성해 PaymentConfirmChannel 에서 take()" — 사실과 불일치.
- **suggestion**: ARCHITECTURE.md §Scheduler 섹션을 현재 구조(`OutboxImmediateEventHandler @Async + OutboxWorker @Scheduled`)로 다시 쓰고, §Confirm Flow 다이어그램에서 "OutboxImmediateWorker / PaymentConfirmChannel / channel.take()" 문단 제거. pg-service 의 `PgOutboxImmediateWorker` 는 별도 섹션으로 분리(대칭 구조이긴 하나 모듈이 다름).

### [major-3] @Transactional 내부에서 동기 Kafka 발행 — DB 트랜잭션 블로킹 리스크

- **severity**: major
- **checklist_item**: domain risk / race window 가 있는 경로에 고려됨
- **location**:
  - `payment-service/.../PaymentConfirmResultUseCase.java`:45-62, 88-91, 104-105
  - `payment-service/.../KafkaMessagePublisher.java`:86-110 (sendTimeoutMillis 기본 10초)
- **problem**: `PaymentConfirmResultUseCase.handle` 전체가 `@Transactional`. `handleApproved` 는 각 PaymentOrder 에 대해 `stockCommitEventPublisherPort.publish(...)` 를 호출하고, 구현체 `KafkaMessagePublisher.sendTyped` 가 `template.send(...).get(sendTimeoutMillis, MILLISECONDS)` 로 동기 블로킹(기본 10초). Kafka broker 지연 시 DB 트랜잭션 + Hikari connection 이 최대 10초 × N건 동안 묶인다. 장애 주입(toxiproxy Kafka 지연 시나리오) 시 connection pool 고갈로 전체 서비스가 먼저 죽는다. T3.5-08 이 의도적으로 동기 발행을 불변식으로 박았지만, **TX 밖으로 내보내야 하는 것** 이 원칙이다(outbox relay 경로가 이미 있으므로 별도 outbox row 로 위임하거나, AFTER_COMMIT 리스너로 분리).
- **evidence**:
  - `PaymentConfirmResultUseCase.java:45` `@Transactional` + line 88-91 loop 내부 `stockCommitEventPublisherPort.publish(...)` (TX 안쪽).
  - `KafkaMessagePublisher.java:88` `template.send(...).get(sendTimeoutMillis, TimeUnit.MILLISECONDS);` 기본 10초.
  - Phase 4 시나리오 "Kafka broker 느림" 주입 시 N × 10초 블로킹.
- **suggestion**: (a) stock commit/restore 도 payment_outbox 패턴으로 wrap — TX 안에서는 outbox INSERT 만, 발행은 relay 워커가. (b) 최소한 `@Transactional(timeout=5)` 명시해서 DB side 타임아웃을 Kafka timeout 보다 짧게. (c) 이상적으로 `TransactionPhase.AFTER_COMMIT` 리스너로 이동(실패 시 재컨슘 경로 보장 필요).

### [major-4] PaymentTransactionCoordinator.executeConfirmTx — TX 내부 publisher.publish 호출

- **severity**: major
- **checklist_item**: domain risk / race window 가 있는 경로에 고려됨
- **location**: `payment-service/.../PaymentTransactionCoordinator.java`:84-95
- **problem**: `executeConfirmTx` 는 `@Transactional` 내부에서 `paymentOutboxUseCase.createPendingRecord(orderId)` 직후 `confirmPublisher.publish(orderId, buyerId, amount, paymentKey)` 를 호출한다. 주석 라인 81-82 는 "PaymentConfirmEvent 발행도 TX 내부에서 수행 — AFTER_COMMIT 리스너가 드롭되지 않도록 TX 동기화가 활성 상태일 때 publish한다" 고 정당화하지만, `PaymentConfirmPublisherPort` 의 구현체가 실제 Kafka publisher 인 경우(ADR-04 대칭) 동기 Kafka 발행이 TX 안으로 들어간다. 현재는 `OutboxImmediatePublisher.publish = ApplicationEventPublisher.publishEvent(PaymentConfirmEvent)` 로 Spring 이벤트라서 빠르지만, 포트 계약상 미래 구현이 Kafka 가 될 수 있다. 포트 이름만 봐서는 위험이 가려짐 — 계약 주석에 "**반드시** in-memory Spring event, Kafka 발행 금지" 제약을 넣어야 한다.
- **evidence**:
  - `PaymentTransactionCoordinator.java:84-95` `@Transactional public PaymentEvent executeConfirmTx(...)` 내부에 publish 호출.
  - `PaymentConfirmPublisherPort` 는 추상 포트이며 구현 바뀌면 TX 내부 블로킹 우려.
- **suggestion**: `PaymentConfirmPublisherPort` Javadoc 에 "Must complete in memory; must not block on remote I/O" 계약을 박고, 구현체 `OutboxImmediatePublisher` 의 테스트에 non-blocking assertion 추가. 또는 publish 를 TX 밖으로 이동하고 `@TransactionalEventListener(AFTER_COMMIT)` 에서 offer/enqueue 하는 패턴으로 전환.

### [major-5] catch (Exception) 워커/aspect 경로의 swallow 패턴

- **severity**: major
- **checklist_item**: convention / catch (Exception e) 없음
- **location**:
  - `pg-service/.../scheduler/PgOutboxImmediateWorker.java`:112-115, 122-125
  - `pg-service/.../scheduler/PgOutboxPollingWorker.java`:62-67
  - `payment-service/.../infrastructure/aspect/DomainEventLoggingAspect.java`:48-52
  - `pg-service/.../infrastructure/aspect/TossApiMetricsAspect.java`:44-50
  - `payment-service/.../infrastructure/messaging/consumer/StockSnapshotWarmupConsumer.java`:49-53
- **problem**: CONVENTIONS 의 "catch (Exception e) 없음 — 있다면 handleUnknownFailure 경유" 가 지켜지지 않는다. 특히:
  - `PgOutboxImmediateWorker.workerLoop`(line 112) 가 `catch (Exception e) { LogFmt.warn(...) }` 로 swallow — channel.take 후 relayExecutor.submit 에서 RejectedExecutionException 같은 것이 나면 id 가 유실된다(outbox row 는 processed_at null 이라 polling 이 주워가긴 함 — 부분 방어).
  - `PgOutboxImmediateWorker.relay`(line 122) 도 `catch (Exception)` + log — PgOutboxRelayService.relay 가 예외를 던지는 이유(publish 실패) 가 그대로 삼켜진다. polling 워커가 결국 재시도하므로 자체 복구는 되지만, 이 경로의 예외는 metric 으로만 나올 뿐 level=ERROR 승격이 없어 알림이 빠진다.
  - `TossApiMetricsAspect.recordTossApiMetric`(line 44): aspect 는 `throws Throwable` 시그니처인데 `catch (Exception)` 로 잡으면 `Error` 는 통과하고 `Exception` 은 metric 기록 후 throw — 틀린 동작은 아니지만 `catch (Exception)` 의 광범위성은 의도 노출이 약함. `throw e` 는 있으므로 swallow 는 아님(minor급).
  - `StockSnapshotWarmupConsumer.parse`(line 49) 는 `JsonProcessingException` 을 구체적으로 잡는 편이 옳다(역직렬화 전용).
- **evidence**: grep 결과 7건 중 테스트 제외 프로덕션 경로 6건. CONVENTIONS.md 라인 30 "`catch (Exception e)` 없음 (있다면 `handleUnknownFailure` 경유)".
- **suggestion**: (1) worker swallow 경로를 `catch (RuntimeException)` + metric 카운터(increment) + level=ERROR 로 격상해 알림 트리거. (2) `StockSnapshotWarmupConsumer.parse` 는 `JsonProcessingException` 전용 catch. (3) aspect 계열은 재throw 만 유지하면 pass, `catch (Throwable)` 로 바꿔 Error 도 metric 후 재throw.

### [minor-1] FakePgGatewayStrategy 의 평문 log.warn 배너 — LogFmt 규약 예외 미문서화

- **severity**: minor
- **checklist_item**: convention / 신규 로깅이 LogFmt 사용
- **location**: `pg-service/.../infrastructure/gateway/fake/FakePgGatewayStrategy.java`:57-60
- **problem**: `PostConstruct` 에서 4줄 `log.warn("╔...╗")` 배너를 직접 찍는다. 주석(line 55-56)은 "시각적 경고 배너" 라 정당화하나, CONVENTIONS.md LogFmt 섹션에는 "예외적 평문 허용" 조항이 없다. STATE.md 의 "평문 로깅 잔재 banner 4건만" 기록으로 보아 팀 내 합의가 있었던 듯하나, 문서에 포함 안 됨.
- **evidence**: `grep` 결과 프로덕션 경로 평문 log.* 는 이 4줄만 잔존.
- **suggestion**: CONVENTIONS.md LogFmt 섹션에 "기동 배너(시각적 경고)는 평문 허용, 그 외 평문 금지" 예외 조항 추가. 또는 LogFmt 에 `banner(Logger, String...)` 헬퍼를 추가해 동일 효과를 규격화.

### [minor-2] PgOutboxRelayService.parseHeaders TODO — traceparent Kafka 헤더 전파 미완

- **severity**: minor
- **checklist_item**: domain risk / (축 2) traceId 정확 추적
- **location**: `pg-service/.../application/service/PgOutboxRelayService.java`:83-92
- **problem**: `parseHeaders` 는 항상 `Map.of()` 반환. 주석에 "T2b 이후 실제 헤더 활용 시 ObjectMapper 주입으로 확장" 잔재. 현재는 `spring.kafka.template.observation-enabled=true` 로 Micrometer 가 자동으로 `traceparent` 를 발행 헤더에 붙이므로(T3.5-13), 실사용 영향은 없다. 다만 outbox 스토어에 저장한 `headers_json` 이 있어도 relay 시점에 버린다는 의미이므로, DLQ 재처리나 확장 헤더 시나리오에서 정보 손실 가능.
- **evidence**: `PgOutboxRelayService.java:91` `return Map.of();`.
- **suggestion**: `ObjectMapper` 주입 후 `readValue(headersJson, new TypeReference<Map<String, String>>(){})` 로 파싱(base64 디코드 옵션). 당장 필요치 않다면 TODO 제거 + 주석에 "micrometer observation-enabled 로 충분함" 으로 의도 명시.

### [minor-3] ARCHITECTURE.md Cross-Context HTTP 어댑터 설명 — `@CircuitBreaker` 미구현 vs 주석의 근거

- **severity**: minor
- **checklist_item**: execution discipline / 문서-코드 정합성
- **location**: `payment-service/.../infrastructure/adapter/http/ProductHttpAdapter.java`:28 주석 + `UserHttpAdapter.java`:25 주석 + ARCHITECTURE.md:174
- **problem**: Javadoc 에 "@CircuitBreaker 는 이 클래스 내부 메서드에만 적용" 이라고 적어 놓고 실제 메서드에는 `@CircuitBreaker` 어노테이션이 없다. ARCHITECTURE.md 도 같은 표현. Phase 4 장애 주입 전에 CircuitBreaker 가 실제로 설치돼 있다는 착각을 줄 수 있음.
- **evidence**:
  - `ProductHttpAdapter.java` 전체에 `@CircuitBreaker` 어노테이션 grep 결과 0.
  - ARCHITECTURE.md:174 "모든 HTTP 호출은 Gateway(Eureka lb://) 경유. @CircuitBreaker 는 adapter 내부 메서드에만 위치 (ADR-22)" — 현재는 "예정" 문구임을 주석으로 명시 필요.
- **suggestion**: 주석/ARCHITECTURE 를 "Phase 4 에서 설치 예정" 으로 수정하거나, 지금 Resilience4j `@CircuitBreaker` + `@Retry` 를 즉시 설치(의존성 존재 가정). Phase 4 Toxiproxy 시나리오는 CircuitBreaker 가 있어야 의미 있는 라이브니스 검증이 된다.

## Scores (code stage)

- correctness: 0.68 (approvedAt/amount 유실 + null 반환 + swallow 패턴으로 감점)
- conventions: 0.80 (LogFmt 통일 + null 1건 + catch(Exception) 5건 잔존)
- discipline: 0.78 (문서 drift + TODO 잔재)
- test-coverage: 0.90 (PgEventPublisherTest / HttpAdapter 계약 / RepeatedTest 강함)
- domain: 0.72 (축 1 AMOUNT_MISMATCH 비대칭 + 축 2 MDC 누락)
- **mean: 0.776**

## Decision

**fail** — critical 2건(approvedAt/amount 유실, null 반환) + major 5건. Phase 4 진입 전 critical 2건은 필수 해소, major 는 최소 #1, #3 우선.

## JSON

```json
{
  "stage": "code",
  "persona": "critic",
  "round": 1,
  "task_id": null,
  "decision": "fail",
  "reason_summary": "축 1 approvedAt/amount pg→payment 전파 누락(ADR-15 비대칭) + 축 3 FakePgGatewayStrategy null 반환 2건이 critical. 축 2 Virtual Thread executor MDC 누락 + ARCHITECTURE.md drift + TX 내 동기 Kafka publish 는 major. Phase 4 장애 주입 전 선행 수정 필수.",
  "checklist": {
    "source": "_shared/checklists/code-ready.md",
    "items": [
      {"section": "test gate", "item": "전체 ./gradlew test 통과", "status": "yes", "evidence": "STATE.md 461 PASS, phase-3-5-gate.md 전 섹션 PASS"},
      {"section": "convention", "item": "null 반환 금지, Optional 사용", "status": "no", "evidence": "FakePgGatewayStrategy.java:92 return null"},
      {"section": "convention", "item": "catch (Exception e) 없음", "status": "no", "evidence": "PgOutboxImmediateWorker.java:112,122 / PgOutboxPollingWorker.java:64 / DomainEventLoggingAspect.java:48 / PaymentHistoryServiceImpl.java:31 / StockSnapshotWarmupConsumer.java:49 / TossApiMetricsAspect.java:44"},
      {"section": "convention", "item": "신규 로깅이 LogFmt 사용", "status": "no", "evidence": "FakePgGatewayStrategy.java:57-60 평문 log.warn 4줄 (의도적 배너이나 규약 미문서화)"},
      {"section": "domain risk", "item": "상태 전이가 불변식을 위반하지 않음", "status": "no", "evidence": "PaymentConfirmResultUseCase.java:85 paymentEvent.done(LocalDateTime.now(),...) — 벤더 approvedAt 유실"},
      {"section": "domain risk", "item": "race window에 락/트랜잭션 격리", "status": "no", "evidence": "PgOutboxImmediateWorker.java:59 Executors.newVirtualThreadPerTaskExecutor() — MDC/OTel context 미전파로 traceId 단절"},
      {"section": "domain risk", "item": "보상/취소 로직 멱등성 가드", "status": "yes", "evidence": "PgOutboxRelayService.relay processed_at null + available_at guard / DuplicateApprovalHandler 2경로 findByOrderId 재진입 방어"},
      {"section": "domain risk", "item": "PG ALREADY_PROCESSED 정당성 검증", "status": "yes", "evidence": "DuplicateApprovalHandler.queryVendorStatus 1회 호출 후 amount 대조, INDETERMINATE → QUARANTINED"},
      {"section": "domain risk", "item": "paymentKey 평문 로그 노출 없음", "status": "yes", "evidence": "FakePgGatewayStrategy.maskKey 적용, production adapter paymentKey 직접 로깅 grep 0"},
      {"section": "execution discipline", "item": "문서-코드 정합성", "status": "no", "evidence": "ARCHITECTURE.md:83-88,101-146 OutboxImmediateWorker/PaymentConfirmChannel 기술하나 payment-service 소스에 부재"}
    ],
    "total": 10,
    "passed": 4,
    "failed": 6,
    "not_applicable": 0
  },
  "scores": {
    "correctness": 0.68,
    "conventions": 0.80,
    "discipline": 0.78,
    "test-coverage": 0.90,
    "domain": 0.72,
    "mean": 0.776
  },
  "findings": [
    {
      "severity": "critical",
      "checklist_item": "domain risk / 상태 전이가 불변식을 위반하지 않음",
      "location": "payment-service/.../messaging/consumer/dto/ConfirmedEventMessage.java:15-20; pg-service/.../messaging/event/ConfirmedEventPayload.java:20-46; payment-service/.../usecase/PaymentConfirmResultUseCase.java:85",
      "problem": "pg-service가 발행하는 ConfirmedEventPayload에 amount/approvedAt 필드 부재. payment-service ConfirmedEventMessage record에도 두 필드 없음. handleApproved가 LocalDateTime.now()로 approvedAt을 위조하고 금액을 payment-service에서 재검증 못함 — ADR-15 AMOUNT_MISMATCH 방어선이 pg-service에만 존재.",
      "evidence": "PaymentConfirmResultUseCase.java:85 `paymentEvent.done(LocalDateTime.now(), LocalDateTime.now());`; ConfirmedEventPayload.approved() 팩토리가 amount=null을 넣음",
      "suggestion": "ConfirmedEventPayload/ConfirmedEventMessage에 amount(Long)+approvedAt(ISO-8601) 필드 추가. pg-service 발행 시 항상 벤더 실제 값 주입. payment-service handleApproved는 수신 approvedAt을 PaymentEvent.done에 전달하고 paymentEvent.getTotalAmount() vs 수신 amount 불일치 시 QUARANTINED(AMOUNT_MISMATCH)로 격리(역방향 방어선)."
    },
    {
      "severity": "critical",
      "checklist_item": "convention / null 반환 금지",
      "location": "pg-service/.../infrastructure/gateway/fake/FakePgGatewayStrategy.java:86-93",
      "problem": "PgStatusLookupPort.getStatusByOrderId 구현체가 return null로 포트 계약 위반. DuplicateApprovalHandler.queryVendorStatus(line 156)가 반환값을 즉시 vendorStatus.amount()로 접근하므로 smoke 경로에서 중복 승인/FCG 진입 시 NPE. 주석에 '호출되지 않는다'고 하나 계약은 코드로 표현돼야 함.",
      "evidence": "FakePgGatewayStrategy.java:92 `return null;` / DuplicateApprovalHandler.java:137-138 null 방어 없음 / CONVENTIONS.md 'null 반환 금지, Optional 사용'",
      "suggestion": "return null 대신 throw new UnsupportedOperationException(...) 또는 PgGatewayRetryableException 던져 DuplicateApprovalHandler의 INDETERMINATE(VENDOR_INDETERMINATE) 경로로 유도. 반환 타입이 절대 null 불가라는 포트 불변식 코드로 표현."
    },
    {
      "severity": "major",
      "checklist_item": "domain risk / race window 고려 (+ 축 2 traceId 연속성)",
      "location": "pg-service/.../scheduler/PgOutboxImmediateWorker.java:59,105-117; payment-service/.../scheduler/OutboxWorker.java:48-53; payment-service/.../listener/OutboxImmediateEventHandler.java:35-39",
      "problem": "Executors.newVirtualThreadPerTaskExecutor()로 submit되는 람다에 MDC/OpenTelemetry Context가 명시 전파되지 않음. Kafka listener에서 생성된 traceId가 channel.offer(Long)으로 넘어가면서 relay 쪽 로그에서 [traceId:N/A]로 찍힘. @Async(\"outboxRelayExecutor\")도 TaskDecorator 없으면 같은 문제. CONVENTIONS.md T3.5-13 '로그 라인 grep으로 전 서비스 span 추적 가능' 불변식이 outbox 경로에서 깨짐.",
      "evidence": "PgOutboxImmediateWorker.java:59 newVirtualThreadPerTaskExecutor + line 109 relayExecutor.submit(() -> relay(id)); OutboxWorker.java:50-52 동일 패턴",
      "suggestion": "(1) ContextExecutorService.wrap(executor, ContextSnapshotFactory.builder().build()) 사용(Micrometer Context Propagation), (2) MDC.getCopyOfContextMap 스냅샷 + setContextMap/clear helper 유틸, (3) @Async는 ThreadPoolTaskExecutor.setTaskDecorator(new MdcTaskDecorator()) — Phase 4 Tempo 진입 전 필수."
    },
    {
      "severity": "major",
      "checklist_item": "execution discipline / 문서-코드 정합성",
      "location": "docs/context/ARCHITECTURE.md:83-88,101-146; payment-service/src/main/java/.../payment/scheduler/ (OutboxImmediateWorker 부재)",
      "problem": "ARCHITECTURE.md가 payment-service OutboxImmediateWorker(SmartLifecycle, VT/PT N개, PaymentConfirmChannel.take())를 상세 기술하나 실제 소스에 부재. 실제는 @Async+@TransactionalEventListener 패턴. Phase 4 장애 주입 시나리오 작성 시 어느 스레드 모델에 주입할지 혼동.",
      "evidence": "find payment-service -name OutboxImmediateWorker* 결과 0; grep PaymentConfirmChannel payment-service/src/main 결과 0; 실제 구현 OutboxImmediateEventHandler.java:35-39",
      "suggestion": "ARCHITECTURE.md §Scheduler/§Confirm Flow를 현재 구조(@Async+리스너)로 재작성. pg-service PgOutboxImmediateWorker는 별도 섹션으로 분리(모듈 다름)."
    },
    {
      "severity": "major",
      "checklist_item": "domain risk / race window 고려",
      "location": "payment-service/.../PaymentConfirmResultUseCase.java:45-62,88-91,104-105; KafkaMessagePublisher.java:86-110",
      "problem": "handle() 전체가 @Transactional. handleApproved/handleFailed가 루프 안에서 동기 Kafka publish(.get(10s))를 호출. Kafka broker 지연 시 N×10초 동안 DB TX + Hikari connection 블로킹 → Phase 4 toxiproxy Kafka 지연 시나리오에서 connection pool 고갈로 서비스 선다운.",
      "evidence": "PaymentConfirmResultUseCase.java:45 @Transactional + line 88-91 publish loop; KafkaMessagePublisher.java:88 template.send(...).get(sendTimeoutMillis, MILLISECONDS) 기본 10000ms",
      "suggestion": "stock commit/restore도 payment_outbox 패턴으로 wrap(TX 안 INSERT만, 발행은 relay 워커). 최소한 @Transactional(timeout=5) 지정해서 DB 타임아웃 < Kafka 타임아웃. 이상적으로 AFTER_COMMIT 리스너로 이동."
    },
    {
      "severity": "major",
      "checklist_item": "domain risk / race window 고려",
      "location": "payment-service/.../PaymentTransactionCoordinator.java:84-95; application/port/out/PaymentConfirmPublisherPort.java",
      "problem": "executeConfirmTx가 @Transactional 내부에서 confirmPublisher.publish()를 호출. 현재 구현은 Spring ApplicationEventPublisher로 빠르지만 포트 계약상 미래 구현이 Kafka가 될 수 있음(위험 숨김). 계약 주석에 'non-blocking in-memory only' 제약 부재.",
      "evidence": "PaymentTransactionCoordinator.java:84-95 @Transactional 내부 publish 호출; PaymentConfirmPublisherPort Javadoc에 blocking/remote I/O 금지 명시 없음",
      "suggestion": "포트 Javadoc에 'Implementations must complete in memory; must not block on remote I/O' 계약 박고 OutboxImmediatePublisher 테스트에 non-blocking assertion. 또는 publish를 TX 밖 @TransactionalEventListener(AFTER_COMMIT)로 이동."
    },
    {
      "severity": "major",
      "checklist_item": "convention / catch (Exception e) 없음",
      "location": "pg-service PgOutboxImmediateWorker.java:112,122; PgOutboxPollingWorker.java:64; payment-service DomainEventLoggingAspect.java:48; TossApiMetricsAspect.java:44; PaymentHistoryServiceImpl.java:31; StockSnapshotWarmupConsumer.java:49",
      "problem": "CONVENTIONS.md 'catch (Exception e) 없음(있다면 handleUnknownFailure 경유)' 규약 위배. 특히 PgOutboxImmediateWorker.workerLoop/relay의 catch(Exception)+log는 swallow 패턴 — publish 실패가 metric/warn 레벨에만 기록되고 ERROR 승격 없음. Polling 워커가 재시도로 부분 복구하나 알림 누락.",
      "evidence": "grep 'catch (Exception' 프로덕션 경로 6건 잔존; PgOutboxImmediateWorker.java:122 catch(Exception e) { LogFmt.warn(...); } 재throw 없음",
      "suggestion": "워커 swallow 경로는 catch(RuntimeException)+metric increment+level=ERROR로 격상. StockSnapshotWarmupConsumer.parse는 JsonProcessingException 전용 catch. aspect는 재throw 있으면 catch(Throwable)로 확장해 Error도 metric 후 재throw."
    },
    {
      "severity": "minor",
      "checklist_item": "convention / 신규 로깅이 LogFmt 사용",
      "location": "pg-service/.../gateway/fake/FakePgGatewayStrategy.java:57-60",
      "problem": "PostConstruct 배너 4줄이 평문 log.warn. 주석은 '시각적 경고' 정당화하나 CONVENTIONS에 예외 조항 없음.",
      "evidence": "grep 프로덕션 경로 평문 log.* 잔재 이 4줄뿐",
      "suggestion": "CONVENTIONS.md LogFmt 섹션에 '기동 배너 평문 허용' 예외 조항 추가 또는 LogFmt.banner(Logger, String...) 헬퍼 추가."
    },
    {
      "severity": "minor",
      "checklist_item": "domain risk / traceId 정확 추적(축 2)",
      "location": "pg-service/.../application/service/PgOutboxRelayService.java:83-92",
      "problem": "parseHeaders가 항상 Map.of() 반환. outbox headers_json에 저장한 헤더가 relay 시 유실. 현재는 observation-enabled=true로 micrometer가 traceparent 자동 주입하므로 실사용 영향 없음.",
      "evidence": "PgOutboxRelayService.java:91 return Map.of(); 주석 'T2b 이후 실제 헤더 활용 시 확장'",
      "suggestion": "ObjectMapper 주입으로 JSON 파싱 구현 또는 TODO 제거+주석을 'micrometer observation으로 충분' 으로 정정."
    },
    {
      "severity": "minor",
      "checklist_item": "execution discipline / 문서-코드 정합성",
      "location": "payment-service/.../adapter/http/ProductHttpAdapter.java:28, UserHttpAdapter.java:25; docs/context/ARCHITECTURE.md:174",
      "problem": "Javadoc과 ARCHITECTURE가 '@CircuitBreaker는 adapter 내부 메서드에만' 이라 단언하나 실제 @CircuitBreaker 어노테이션 0건. Phase 4 장애 주입 전 CircuitBreaker 설치 착각 유발.",
      "evidence": "grep @CircuitBreaker ProductHttpAdapter/UserHttpAdapter 결과 0",
      "suggestion": "주석/ARCHITECTURE를 'Phase 4 설치 예정' 으로 수정하거나 Resilience4j @CircuitBreaker+@Retry 즉시 설치."
    }
  ],
  "previous_round_ref": null,
  "delta": null,
  "unstuck_suggestion": null
}
```
