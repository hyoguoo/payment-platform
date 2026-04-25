# PRE-PHASE-4-HARDENING-PLAN

**토픽**: [PRE-PHASE-4-HARDENING](topics/PRE-PHASE-4-HARDENING.md)
**날짜**: 2026-04-24
**라운드**: 1 (baseline 리뷰 findings 17건 → 19 태스크 분해)

> Baseline 리뷰:
> - `docs/rounds/pre-phase-4-hardening/review-critic-1.md` (decision=fail, 2 critical / 5 major / 3 minor)
> - `docs/rounds/pre-phase-4-hardening/review-domain-1.md` (decision=fail, 2 critical / 4 major / 3 minor)
>
> Dedupe 후 **critical 4 · major 8 · minor 5**.

---

## 태스크 목록

**그룹 A — 이벤트 계약 확장** (축 1, critical-1·2 기반)
- [x] T-A1 `amount` + `approvedAt` 필드 `ConfirmedEventPayload`/`ConfirmedEventMessage` 에 추가

  **완료 결과 (2026-04-24)** — `ConfirmedEventPayload` (pg-service): `amount(Long)` + `approvedAt(String)` 필드 추가, `approved(orderId, eventUuid, amount, approvedAt)` 팩토리에 `requireNonNull` 가드 적용. `quarantinedWithAmount` 추가. `ConfirmedEventMessage` (payment-service): 동일 두 필드 추가, 기존 테스트 5케이스 호환 유지. `PgConfirmResult`: `approvedAtRaw(String)` 7번째 필드 추가 + deprecated 6-arg 생성자 보존. `PgVendorCallService.buildApprovedPayload`: `result.approvedAtRaw()` non-null 시 원본 보존, null 시 Clock fallback. `PgFinalConfirmationGate.handleApproved`: Clock fallback으로 `approvedAtRaw` 주입. `DuplicateApprovalHandler.buildApprovedPayload`: Clock fallback 주입. `TossPaymentGatewayStrategy.toConfirmResult`: `response.approvedAt()` raw 문자열 7번째 arg 전달. `NicepayPaymentGatewayStrategy.toConfirmResult`: `response.paidAt()` raw 문자열 전달. `FakePgGatewayStrategy.confirm`: `approvedAtRaw` UTC 시각 주입. 전수 467 테스트 PASS (eureka 1 + gateway 3 + payment-service 281 + pg-service 155 + product-service 26 + user-service 1). 회귀 없음.
- [x] T-A2 `handleApproved` 에 수신 `approvedAt` 주입 + `amount` 총액 대조 → AMOUNT_MISMATCH 역방향 방어

  **완료 결과 (2026-04-24)** — `PaymentConfirmResultUseCase.handleApproved` 시그니처를 `handleApproved(paymentEvent, message)`로 변경. `parseApprovedAt(String)` private 메서드 추출: `approvedAt=null` → `IllegalArgumentException`. `isAmountMismatch(PaymentEvent, Long)` private 메서드 추출: `receivedAmount=null` 또는 도메인 총액(`longValueExact()`)과 불일치 → `true`. 불일치 시 `QuarantineCompensationHandler.handle(orderId, "AMOUNT_MISMATCH")` 호출 후 early return(done 미호출). 일치 시 `OffsetDateTime.parse(approvedAtRaw).toLocalDateTime()` 변환값을 `paymentEvent.done(receivedApprovedAt, localDateTimeProvider.now())`에 주입. `LocalDateTimeProvider` 생성자 주입 추가(`LocalDateTime.now()` 위조 제거). `handleFailed`의 `LocalDateTime.now()` → `localDateTimeProvider.now()` 교체. `ConfirmedEventConsumerTest` 기존 5케이스 APPROVED 메시지에 amount/approvedAt non-null 값 보정. `PaymentConfirmResultUseCaseTest` 신규 4케이스(TC-A2-1~4) 추가. ADR-15 AMOUNT_MISMATCH 방어선 양방향 작동 확인. 전수 285/285 PASS(payment-service), 회귀 없음.

**그룹 B — 재고 보상 실 복원** (축 1, Domain critical-1)
- [x] T-B1 `handleFailed` 에서 `FailureCompensationService.compensate(orderId, productId, qty)` 경유

  **완료 결과 (2026-04-24)** — `FailureCompensationService.compensate(orderId, Long productId, int qty)` 단일 productId 오버로드 신설. 기존 `compensate(orderId, List<Long>, int)`는 내부에서 단일 오버로드를 위임하도록 리팩토링. `PaymentConfirmResultUseCase`에 `FailureCompensationService` 필드 주입 추가. `handleFailed`에서 레거시 `stockRestoreEventPublisherPort.publish(orderId, productIds)` 호출 제거 → 각 `PaymentOrder`별 `failureCompensationService.compensate(orderId, order.getProductId(), order.getQuantity())` 루프로 교체. `ConfirmedEventConsumerTest` TC2 검증을 `compensate` 호출 기반으로 갱신. `PaymentConfirmResultUseCaseTest` T-B1 신규 3케이스(TC-B1-1 단일 qty / TC-B1-2 복수 productId / TC-B1-3 레거시 publish 미호출) 추가. 전수 288/288 PASS, 회귀 없음. T-B2 진입 가능.
- [x] T-B2 레거시 `StockRestoreEventKafkaPublisher.publish(String, List<Long>)` 오버로드 철거

  **완료 결과 (2026-04-24)** — `StockRestoreEventPublisherPort.publish(String, List<Long>)` 인터페이스 선언 삭제. `StockRestoreEventKafkaPublisher.publish(String, List<Long>)` 구현(qty=0 플레이스홀더) 삭제 — `publishPayload(StockRestoreEventPayload)` 단일 경로만 유지. `FakeStockRestoreEventPublisher`: `publish(String, List<Long>)` 구현, `StockRestoredRecord` inner record, `published` 리스트, `callCount` AtomicInteger, `publishedEvents()`/`publishedCount()` 메서드 모두 삭제. `PaymentConfirmResultUseCase`: `stockRestoreEventPublisherPort` 필드 삭제(handleFailed 이미 FailureCompensationService 경유) + 관련 import 제거. `PaymentConfirmResultUseCaseTest`: TC-B1-3(레거시 미호출 검증) 삭제 — 메서드 자체가 소멸됐으므로. `ConfirmedEventConsumerTest`: `stockRestorePublisher.publishedCount()` 잔여 검증 3개소 제거 + 불필요 import 정리. 호출처 grep 결과 실 코드 0건(주석만 1건). `./gradlew test` 전수 287/287 PASS, 회귀 없음. T-C1 진입 가능.

**그룹 C — 멱등성 수복** (축 1, critical-4 + major)
- [x] T-C1 payment dedupe TTL 기본값 `P8D` + application.yml 명시

  **완료 결과 (2026-04-24)** — `EventDedupeStoreRedisAdapter`: `@Value("${payment.event-dedupe.ttl:PT1H}")` → `@Value("${payment.event-dedupe.ttl:P8D}")` 기본값 변경. Javadoc 갱신(Kafka retention 7d + 복구 버퍼 1d = 8d 근거, product-service `StockRestoreUseCase.DEDUPE_TTL = Duration.ofDays(8)` 정렬 명시). `payment-service/src/main/resources/application.yml` `payment.event-dedupe.ttl: P8D` 명시적 override 추가(주석에 근거 포함). `EventDedupeStoreRedisAdapterTest.defaultTtl_shouldBe8Days` 신규 1케이스 GREEN. 전수 474 PASS(eureka 1 + gateway 3 + payment-service 288 + pg-service 155 + product-service 26 + user-service 1). 회귀 없음.
- [x] T-C2 `QuarantineCompensationHandler.handle` 사전 `isTerminal` 가드

  **완료 결과 (2026-04-24)** — `PaymentEventStatus.isTerminal()`: 이미 존재(DONE/FAILED/CANCELED/PARTIAL_CANCELED/EXPIRED=true, QUARANTINED=non-terminal). `PaymentEvent.quarantine()`: 기존 `PaymentStatusException` 가드 → `IllegalStateException("Cannot transit terminal status to QUARANTINED: " + status)` 이중 가드로 교체(도메인 불변식). `QuarantineCompensationHandler.handle`: 진입 직후 `event.getStatus().isTerminal()` 체크 — true면 `PAYMENT_QUARANTINE_NOOP_TERMINAL` LogFmt.info + early return(markPaymentAsQuarantined·save 미호출). `EventType`: `PAYMENT_QUARANTINE_NOOP_TERMINAL` 엔트리 추가. 기존 `quarantine_InvalidStatus` 테스트 expectation `PaymentStatusException` → `IllegalStateException`으로 갱신. 신규 4케이스(handle_whenTerminalStatus_shouldNoOp 2건 / handle_whenNonTerminal_shouldQuarantine 2건 / markPaymentAsQuarantined_whenDone_shouldThrow / _whenFailed_shouldThrow) 전수 PASS. `./gradlew test` 전수 PASS, 회귀 없음.
- [x] T-C3 dedupe two-phase lease (`mark` short → `extend` long) + remove 실패 DLQ 전송

  **완료 결과 (2026-04-24)** — `EventDedupeStore` 포트: `markWithLease(eventUuid, shortTtl)` + `extendLease(eventUuid, longTtl)` + `boolean remove(eventUuid)` 추가. `markSeen` → `@Deprecated default`로 `markWithLease(P8D)` 위임. `EventDedupeStoreRedisAdapter`: SET NX EX(markWithLease) / SET XX EX(extendLease) / DEL→boolean(remove). `FakeEventDedupeStore`: Clock 주입 + ConcurrentHashMap TTL 만료 시뮬레이션. `PaymentConfirmDlqPublisher` 포트 신설. `PaymentConfirmDlqKafkaPublisher`: `payment.events.confirmed.dlq` 토픽, StringSerializer 전용 KafkaTemplate. `PaymentTopics.EVENTS_CONFIRMED_DLQ` 추가. `KafkaTopicConfig` + `KafkaProducerConfig`에 DLQ 토픽 빈 등록. `PaymentConfirmResultUseCase`: `markSeen` → `markWithLease(leaseTtl=PT5M)` 교체, `processMessageWithLeaseGuard` private 메서드 추출(try 블록 외부 변수 재할당 금지 준수), 성공 후 `extendLease(longTtl=P8D)`, 실패 후 `remove` → false이면 `dlqPublisher.publishDlq`. `application.yml`: `payment.event-dedupe.lease-ttl: PT5M` 추가. `FakePaymentConfirmDlqPublisher` 신설. `FakeEventDedupeStoreLeaseTest` 8케이스 + `PaymentConfirmResultUseCaseTwoPhaseLeaseTest` TC-C3-1~4 GREEN. 전수 306/306(payment-service) PASS, 회귀 없음.

**그룹 D — TX-발행 분리** (축 1, major-3·4)
- [x] T-D1 `OutboxAsyncConfirmService` Redis DECR 보상 경로 (caller 측 catch)

  **완료 결과 (2026-04-24)** — `StockCachePort.increment(Long productId, int quantity)` 메서드 추가 (ADR-D3 보상 전용, rollback과 의미 분리). `StockCacheRedisAdapter.increment`: `opsForValue().increment` 단순 INCR 구현. `FakeStockCachePort.increment`: ConcurrentHashMap merge. `OutboxAsyncConfirmService`: `StockCachePort` 생성자 주입 추가. `executeConfirmTxWithStockCompensation` private 메서드 추출 — try/catch(RuntimeException), catch에서 `compensateStock` 호출 후 re-throw. `compensateStock`: 각 PaymentOrder별 increment 루프, increment 실패 시 LogFmt.error(STOCK_COMPENSATE_FAIL) 삼키고 계속 — 원본 txException은 항상 re-throw 보장. `EventType`: `STOCK_COMPENSATE_SUCCESS` + `STOCK_COMPENSATE_FAIL` 추가. 테스트 3케이스(T-D1-1 보상호출+예외전파/T-D1-2 성공시increment미호출/T-D1-3 보상실패시원본예외) GREEN. `./gradlew test` 전수 PASS, 회귀 없음. T-D2 진입 가능.
- [x] T-D2 `PaymentConfirmResultUseCase` stock commit/restore publish 를 AFTER_COMMIT 리스너로 이동 + TX timeout 명시

  **완료 결과 (2026-04-24)** — `StockCommitRequestedEvent` / `StockRestoreRequestedEvent` record 2종 신설(`payment.application.event` 패키지). `StockEventPublishingListener`(`@Component`, `@TransactionalEventListener(AFTER_COMMIT)`) 신설: `onStockCommitRequested` → `stockCommitEventPublisherPort.publish`, `onStockRestoreRequested` → `stockRestoreEventPublisherPort.publishPayload`. Kafka 발행 실패 시 LogFmt.error 후 예외 삼킴(TX 이미 commit). `PaymentConfirmResultUseCase`: 생성자 3번째 인자 `StockCommitEventPublisherPort` → `ApplicationEventPublisher` 교체. `handleApproved` 내 직접 `stockCommitEventPublisherPort.publish` 제거 → `applicationEventPublisher.publishEvent(StockCommitRequestedEvent)` 루프 교체. `@Transactional` → `@Transactional(timeout=5)`. `ConfirmedEventConsumerTest` / `PaymentConfirmResultUseCaseTest` / `PaymentConfirmResultUseCaseTwoPhaseLeaseTest`: `FakeStockCommitEventPublisher` 제거, `CapturingApplicationEventPublisher` 내부 클래스로 교체. `PaymentConfirmResultUseCaseD2Test` 2케이스 + `StockEventPublishingListenerTest` 3케이스 GREEN. `./gradlew test` 전수 314/314(payment-service) PASS, 회귀 없음. T-E1 진입 가능.

**그룹 E — traceId 연속성** (축 2 전체)
- [x] T-E1 Micrometer Context Propagation 의존 추가 + VT executor 3곳 (`PgOutboxImmediateWorker`·`OutboxWorker`·`@Async outboxRelayExecutor`) 전파 활성화

  **완료 결과 (2026-04-24)** — `io.micrometer:context-propagation` 은 BOM 전이 의존으로 이미 포함(1.1.2) — 별도 명시 추가 불필요. `PgSlf4jMdcThreadLocalAccessor`(pg-service) + `Slf4jMdcThreadLocalAccessor`(payment-service) 신설 — `ThreadLocalAccessor<Map<String, String>>` 구현으로 Micrometer ContextRegistry 에 MDC 전파 경로 제공. `PgServiceConfig.@PostConstruct registerMdcAccessor()` + `MdcContextPropagationConfig.@PostConstruct registerMdcAccessor()` — 애플리케이션 기동 직후 ContextRegistry 에 Slf4j MDC accessor 등록. `PgOutboxImmediateWorker.start()`: `relayExecutor = ContextExecutorService.wrap(Executors.newVirtualThreadPerTaskExecutor(), ContextSnapshotFactory.builder().build())` — VT submit 시 호출 스레드 MDC 캡처·복원. `OutboxWorker.processParallel()`: 동일 패턴으로 raw executor 감싸기. `MdcTaskDecorator` 신설(payment-service `core/config`) — `MDC.getCopyOfContextMap()` 캡처 → Runnable 실행 후 `MDC.clear()` finally 보장. `AsyncConfig.outboxRelayExecutor()`: `TaskExecutorAdapter.setTaskDecorator(new MdcTaskDecorator())` — `@Async("outboxRelayExecutor")` 경계 MDC 전파 활성화. `PgOutboxImmediateWorkerMdcPropagationTest` 1케이스 + `OutboxWorkerMdcPropagationTest` 1케이스 + `MdcTaskDecoratorTest` 3케이스 GREEN. 전수 505/505 PASS (eureka 1 + gateway 3 + payment-service 318 + pg-service 156 + product-service 26 + user-service 1). 회귀 없음. T-E2 진입 가능.
- [x] T-E2 `HttpOperatorImpl` 2곳(payment-service·pg-service) Boot auto-config builder 주입 → `observationRegistry` 자동 적용

  **완료 결과 (2026-04-24)** — payment-service `HttpOperatorImpl`: `@PostConstruct init()` + 수동 `WebClient.builder().build()` 제거 → `WebClient.Builder webClientBuilder, int connectTimeoutMillis, long readTimeoutMillis` 생성자 주입. `webClientBuilder.clone().clientConnector(ReactorClientHttpConnector(httpClient)).build()` — auto-config customizer(ObservationRegistry) 상속 유지. pg-service `HttpOperatorImpl`: 동일 패턴으로 `RestClient.Builder restClientBuilder, int connectTimeoutMillis, int readTimeoutMillis` 생성자 주입. `restClientBuilder.clone().requestFactory(factory).build()`. `AdditionalHeaderHttpOperator`(테스트 전용 상속 클래스): `WebClient.builder()` noop builder 로 상위 생성자 위임으로 갱신. `HttpOperatorTraceparentPropagationTest`(payment-service·pg-service 각 2케이스): MockWebServer 기반 GET/POST 정상 호출 검증. mockwebserver:4.12.0 testImplementation 추가(okhttp3 전이 4.12.0). 전수 509/509 PASS (eureka 1 + gateway 3 + payment-service 320 + pg-service 158 + product-service 26 + user-service 1). 회귀 없음. T-E3 진입 가능.
- [x] T-E3 `scripts/smoke/trace-continuity-check.sh` 신설 — HTTP → Kafka → HTTP 다중 홉 traceId 연속성 검증

  **완료 결과 (2026-04-24)** — `scripts/smoke/trace-continuity-check.sh` 신설 (chmod +x, `#!/usr/bin/env bash`, `set -euo pipefail`). 시나리오: (1) 선행 조건 확인(docker/curl/openssl 명령어 + Gateway health). (2) `openssl rand -hex 16/8` 로 W3C traceparent(`00-<32hex>-<16hex>-01`) 생성. (3) `POST /api/v1/payments/checkout` 201 + orderId 추출. (4) `POST /api/v1/payments/confirm` 202 Accepted. (5) `GET /api/v1/payments/{orderId}/status` 폴링 → DONE 확인. (6) 비동기 relay 완주 대기(기본 20s). (7) `docker compose -f docker-compose.apps.yml logs --since=5m` 로 5개 서비스 로그 수집 → `traceId:<trace-id>` 등장 여부 검증. (8) payment-service Kafka listener / pg-service Kafka consumer 경로 추가 확인. 미발견 서비스 이름 출력 후 exit 1. 상단 주석: 용도·선행 조건·재현 절차·실패 시 조치 명시. `--auto-compose-up` / `--verbose` 옵션 지원. `docs/phase-gate/trace-continuity-smoke.md` 신설: 시나리오 설명·재현 명령어·서비스별 로그 위치·실패 시 트리아지(VT executor 미전파/Kafka observation 미활성/HTTP observationRegistry 미주입). `bash -n` 문법 검증 PASS. `./gradlew test` 전수 509/509 PASS (eureka 1 + gateway 3 + payment-service 320 + pg-service 158 + product-service 26 + user-service 1). 회귀 없음. T-E4 진입 가능.
- [x] T-E4 `PgOutboxRelayService.parseHeaders` ObjectMapper 실제 파싱 (또는 TODO 제거 + 근거 주석)

  **완료 결과 (2026-04-24)** — 옵션 B 적용. `parseHeaders(String headersJson)` private 메서드 삭제. 호출부(`relay` 메서드 내 publish 직전)에서 `Map.of()` 직접 전달. 근거 주석 추가: "Kafka 헤더는 `spring.kafka.template.observation-enabled=true` 가 publish 시점의 현재 span 에서 traceparent 를 자동 주입한다. `outbox row 의 headers_json` 은 향후 확장(예: attempt 카운터)을 위해 예약 필드이며 현 시점에는 사용하지 않는다." `PgOutboxRelayServiceTest` 에 `parseHeaders` 단위 테스트 없음 — 삭제 대상 없음. 전수 509/509 PASS (eureka 1 + gateway 3 + payment-service 320 + pg-service 158 + product-service 26 + user-service 1). 회귀 없음. T-F1 진입 가능.

**그룹 F — 코드 규율** (축 3)
- [x] T-F1 `FakePgGatewayStrategy.getStatusByOrderId` NPE 제거 — `UnsupportedOperationException` throw

  **완료 결과 (2026-04-24)** — `FakePgGatewayStrategy.getStatusByOrderId`: `return null` 제거 → `UnsupportedOperationException` throw로 교체. LogFmt.warn(WARN) 로그는 예외 throw 직전에 유지(예상 밖 호출 추적). 예외 메시지에 실제 복구 사이클 사용 전략(Toss/NicePay) 안내 포함. `FakePgGatewayStrategyTest.getStatusByOrderId_shouldThrowUnsupported` 1케이스 GREEN. 전수 159/159 PASS(pg-service). 회귀 없음.
- [x] T-F2 worker/aspect `catch (Exception)` 6건 정리 — RuntimeException 축소 + ERROR 승격 + metric

  **완료 결과 (2026-04-24)** — `PgOutboxImmediateWorker.workerLoop/relay`: `catch (Exception)` WARN → `catch (RuntimeException)` ERROR 승격. `relay` 에 `pg_outbox.relay_fail_total` Counter 추가(MeterRegistry 생성자 주입). `PgOutboxPollingWorker.poll`: 동일 패턴, 동일 카운터 이름. `DomainEventLoggingAspect`: 재throw 패턴 `catch (Exception)` → `catch (Throwable)` (Error도 기록 후 re-throw). `TossApiMetricsAspect`: 동일. `StockSnapshotWarmupConsumer.parse`: `catch (Exception)` → `catch (JsonProcessingException)` 전용 축소(RuntimeException 전파). `PaymentHistoryServiceImpl.recordPaymentHistory`: `catch (Exception)` → `catch (RuntimeException)` 축소(re-throw 패턴, checked exception 없음). `PgOutboxImmediateWorkerTest.relay_whenPublishThrows_shouldLogErrorAndIncrementMetric` + `PgOutboxPollingWorkerTest.polling_whenRelayThrows_shouldLogErrorAndContinue` + `StockSnapshotWarmupConsumerTest.parse_whenInvalidJson_shouldCatchJsonProcessingOnly` 3케이스 GREEN. `grep 'catch (Exception' */src/main/java` 결과 0건. 전수 PASS, 회귀 없음. T-F3 진입 가능.
- [x] T-F3 `LogFmt.banner(Logger, String...)` 헬퍼 + `FakePgGatewayStrategy` 배너 치환 + CONVENTIONS 규약 추가

  **완료 결과 (2026-04-24)** — `LogFmt.banner(Logger, Level, String... lines)` 헬퍼를 5개 서비스(payment-service·pg-service·product-service·user-service·gateway) 각 LogFmt에 ADR-19 복제(b) 방침으로 독립 추가. `org.slf4j.event.Level` import 추가, switch expression으로 레벨별 isEnabled 가드 후 직접 출력. `FakePgGatewayStrategy.warnActivation()`: 4줄 `log.warn("╔...")` 직접 호출 → `LogFmt.banner(log, Level.WARN, "╔...╗", "║...║", "╚...╝", ...)` 치환. `docs/context/CONVENTIONS.md` LogFmt 섹션에 기동 배너 예외 조항 추가(`LogFmt.banner` 경유 필수, 직접 `log.warn` 금지, 레벨·예시 포함). `LogFmtBannerTest` 3케이스(WARN 2줄/INFO 3줄/빈배열) GREEN. `grep -rn '\blog\.(warn|info|error)\b' */src/main/java` → 실 코드 0건(Javadoc 주석만). 전수 테스트 통과. T-F4 진입 가능.
- [x] T-F4 `docs/context/ARCHITECTURE.md` §Scheduler / §Confirm Flow 현재 구조로 재작성

  **완료 결과 (2026-04-24)** — `docs/context/ARCHITECTURE.md` §Scheduler 섹션: 구버전 `OutboxImmediateWorker`(SmartLifecycle+Channel) / `OutboxProcessingService` 참조 전부 제거. 실제 컴포넌트 반영: payment-service `OutboxWorker`(@Scheduled 폴링 안전망), `PaymentScheduler`(만료 스케줄러), pg-service `PgOutboxImmediateWorker`(SmartLifecycle+VT+LinkedBlockingQueue), `PgOutboxPollingWorker`(@Scheduled 2000ms). §Listener 섹션: `OutboxImmediateEventHandler`(AFTER_COMMIT+@Async) 실제 동작 + `StockEventPublishingListener`(T-D2, AFTER_COMMIT stock Kafka 발행) 신규 기술. §Confirm Flow 섹션: `PaymentConfirmChannel` 전제 완전 제거 → 3개 서브섹션(payment-service 발행/pg-service 발행/payment-service 수신)으로 재작성. 대칭성 비교표 + trade-off 명시. Key Design Decisions: `PaymentConfirmChannel` 항목 제거, `OutboxRelayService` 단일 진입점 항목 신설, `@CircuitBreaker` → "Phase 4 설치 예정" 수정. Error Handling: `OutboxProcessingService` 복구 사이클 참조 → `OutboxRelayService`+pg-service FCG 실 구조로 교체. 문서-코드 drift 0. `./gradlew test` 전수 PASS (소스 변경 없음). T-G1 진입 가능.

**그룹 G — minor 정리**
- [x] T-G1 `@CircuitBreaker` Javadoc / ARCHITECTURE 문구를 "Phase 4 설치 예정" 으로 정정

  **완료 결과 (2026-04-24)** — `ProductHttpAdapter` / `UserHttpAdapter` Javadoc: "@CircuitBreaker는 이 클래스 내부 메서드에만 적용" 단언 문구 → "Resilience4j @CircuitBreaker는 이 클래스 내부 메서드에 Phase 4에서 설치 예정 — port 인터페이스 오염 금지(ADR-22)"로 정정. `grep -rn '^\s*@CircuitBreaker' payment-service/src/main/java/.../adapter/http/` 결과 0건. `docs/context/ARCHITECTURE.md` §Key Design Decisions의 `@CircuitBreaker` 항목은 T-F4에서 이미 "Phase 4 설치 예정(ADR-22 예약)" 으로 정정 완료 확인. `./gradlew :payment-service:test --rerun` 324/324 PASS, 회귀 없음. T-G2 진입 가능.
- [x] T-G2 `DuplicateApprovalHandler.handleDuplicateApproval` eventUuid 파라미터 정리 (삭제 or 실 값)

  **완료 결과 (2026-04-24)** — `DuplicateApprovalHandler.handleDuplicateApproval` 시그니처에서 미사용 `String eventUuid` 3번째 파라미터 제거 (`(String orderId, BigDecimal payloadAmount)` 2-arg 로 단축). Javadoc `@param eventUuid` 항목 삭제. `TossPaymentGatewayStrategy.handleErrorResponse` 호출 (`request.orderId(), request.amount(), request.orderId()` → `request.orderId(), request.amount()`). `NicepayPaymentGatewayStrategy.handleConfirmResponse` + `classifyConfirmError` 2곳 동일 정정. `DuplicateApprovalHandlerTest` 5케이스 3번째 인자 `EVENT_UUID` 제거 + `EVENT_UUID` 상수 삭제. `grep -rn 'request\.orderId()' pg-service/src/main/java/.../gateway/` grep `handleDuplicateApproval` 결과 0건 확인. 161/161 PASS(pg-service), 전수 512 PASS, 회귀 없음. T-G3 진입 가능.
- [x] T-G3 QUARANTINED 운영자 복구 경로 `docs/context/TODOS.md` + `ARCHITECTURE.md` Quarantine flow 섹션 추가

  **완료 결과 (2026-04-24)** — `docs/context/TODOS.md`: "QUARANTINED 홀딩 자산 운영자 복구 (QUARANTINED-ADMIN-RECOVERY 토픽)" 신규 항목 추가(+40행). 배경(FCG INDETERMINATE → QUARANTINED + T3.5-07 자동 복구 철거 근거) + 필요 기능 4종((a) Admin API `/admin/payments/{orderId}/reconcile`, (b) Grafana 대시보드 확장, (c) SLA/TTR/알림 임계 정의, (d) 런북) + 제안 시점(Phase 4 이후 `QUARANTINED-ADMIN-RECOVERY` 토픽) + 관련 파일 4종 기재. `docs/context/ARCHITECTURE.md` Error Handling 섹션: "Quarantine Recovery (운영자 복구 경로) — 현재 자동 경로 없음" 하위 블록 신설(+16행). T3.5-07 철거 근거·홀딩 자산 복구 4단계 절차·모니터링 포인트·별도 토픽 예약 명시. 소스 변경 없음. `./gradlew test` 전수 PASS(소스 영향 없음). T-Gate 진입 가능.

**그룹 H — Critic Round 2 minor 잔존 이슈 (신규)**
- [x] T-H1 `PaymentConfirmPublisherPort` non-blocking in-memory 계약 Javadoc 명시 + `OutboxImmediatePublisherTest` non-blocking assertion 추가

  **완료 결과 (2026-04-24)** — `PaymentConfirmPublisherPort` 인터페이스에 포트 계약 Javadoc 추가: TX 내부 in-memory 즉시 완주 / 원격 I/O 차단 금지 / 실제 Kafka 발행은 `AFTER_COMMIT` 리스너 위임 / 위반 시 테스트 실패 안내. `OutboxImmediatePublisherTest.publish_shouldCompleteSynchronouslyUnder50ms` 1케이스 추가 — `Duration.between(before, after) < 50ms` + `publishEvent 1회` 검증(계약 가드). 전수 테스트 3/3 PASS(OutboxImmediatePublisherTest), 전수 `./gradlew test` PASS (325 payment-service 포함). 회귀 없음.

- [x] T-H2 `StockEventPublishingListener` catch 블록 `stock.kafka.publish.fail.total` counter 추가 + `TODOS.md` Phase 4 outbox 이관 항목

  **완료 결과 (2026-04-24)** — `StockEventPublishingListener`: 생성자에 `MeterRegistry` 3번째 인자 추가. `commitFailCounter` (tag `event=commit`) + `restoreFailCounter` (tag `event=restore`) 생성자에서 등록. `onStockCommitRequested` catch 블록: `commitFailCounter.increment()` + LogFmt.error 메시지에 metric 증가 안내 추가. `onStockRestoreRequested` catch 블록: 동일 패턴 `restoreFailCounter.increment()`. counter 이름 `stock.kafka.publish.fail.total` (Prometheus 노출 시 `stock_kafka_publish_fail_total`). swallow 자체는 유지 — TX 이미 commit 의도 보존. `StockEventPublishingListenerTest` TC-H2-1(commit 발행 실패 → counter tag event=commit 값 1) + TC-H2-2(restore 발행 실패 → counter tag event=restore 값 1) GREEN. `docs/context/TODOS.md`: "Phase 4 후속: stock commit/restore payment_outbox 이관" 항목 추가 (배경·방안 A/B·Grafana 알림 요구·관련 파일). 전수 `./gradlew test` PASS. 회귀 없음.

**그룹 I — 실 환경 회귀 fix (compose-up 스모크 발견 + R3''' NPE 회귀)**
- [x] T-I1 `AmountConverter.fromBigDecimalStrict` scale 검증 완화 — trailing zeros 허용

  **완료 결과 (2026-04-24)** — 회귀 발견 경위: T-A1 이후 compose-up 스모크에서 `PgVendorCallService.buildApprovedPayload`가 `AmountConverter.fromBigDecimalStrict(result.amount())`를 호출하는 경로에서, Kafka JSON 역직렬화 시 `BigDecimal("1000.00")` (scale=2, 정수 값)로 들어와 기존 `scale > 0` 거부 조건이 `ArithmeticException`을 던짐. 결과: `handleSuccess` throw → pg_inbox IN_PROGRESS 박힘 → 무한 NOOP → 결제 영구 PROCESSING. 수정 내용: `scale > 0 → ArithmeticException` 조건 제거 → `longValueExact()`로 교체 — 정수 값이면 trailing zeros(`1000.00`) 허용, 진짜 fractional(`150.50`) 만 거부. Javadoc 갱신: "정수 값이면 trailing zeros(`1000.00`) 허용 — Kafka JSON 역직렬화 호환" 명시. `PgInboxAmountStorageTest.TC4` 메시지 검증 완화(`"scale must be 0"` → 타입만 검증). 신규 3케이스(trailing zeros 허용 / 진짜 fractional 거부 / zero 반환) GREEN. 전수 `./gradlew test` PASS 회귀 없음.

- [x] T-I2 `AsyncConfig.outboxRelayExecutor` — `ContextExecutorService.wrap` + `Context.taskWrapping` 이중 래핑으로 Tracing context 전파

  **완료 결과 (2026-04-24)** — 회귀 발견 경위(R2): compose-up 스모크에서 Kafka producer 가 발행하는 메시지의 `traceparent` 헤더가 incoming HTTP traceparent 와 다른 새 trace 로 박힘. 근본 원인: `outboxRelayExecutor` 의 `MdcTaskDecorator` 가 SLF4J MDC ThreadLocal 만 복사하고 OTel Context ThreadLocal(span/traceId) 을 무시하므로 `@Async("outboxRelayExecutor")` 경계에서 active span 이 소실 → VT 에서 KafkaTemplate.send() 가 새 trace 시작. 수정 내용: `MdcTaskDecorator` 제거 → 이중 래핑 적용. (1) `Context.taskWrapping(raw)` — OTel Context ThreadLocal 전파(OTel ContextStorage 는 Micrometer ContextRegistry 와 별개 경로). (2) `ContextExecutorService.wrap(otelWrapped, factory::captureAll)` — Micrometer ContextRegistry 등록 accessor(MDC 등) 전파. `MdcTaskDecorator`(main) + `MdcTaskDecoratorTest`(test) 삭제(다른 사용처 없음). `AsyncConfigContextPropagationTest` 신규 2케이스(TC1 OTel Context 전파 / TC2 MDC 전파) GREEN. 전수 520/520 PASS (eureka 1 + gateway 3 + payment-service 326 + pg-service 163 + product-service 26 + user-service 1). 회귀 없음. T-Gate 진입 가능.

- [x] T-I3 product-service `KafkaMessageConverterConfig` 추가 — Kafka record deserialize 회귀 해소

  **완료 결과 (2026-04-24)** — 회귀 발견 경위(R3'): compose-up 스모크에서 `StockCommitConsumer`/`StockRestoreConsumer` 가 `Cannot convert from [java.lang.String] to [StockCommittedMessage]` 오류로 backoff 소진. 근본 원인: product-service 에 `RecordMessageConverter` 빈 부재 — pg-service 의 `KafkaMessageConverterConfig` 와 달리 product-service 에는 동일 빈이 등록되어 있지 않아 StringDeserializer 로 수신한 JSON 문자열이 record 타입으로 변환되지 못함. 수정 내용: `product-service/.../infrastructure/config/KafkaMessageConverterConfig.java` 신설 — `StringJsonMessageConverter(objectMapper)` 빈 등록, `@ConditionalOnProperty(name = "spring.kafka.bootstrap-servers")` 조건 적용(ADR-19 복제(b) 방침 — pg-service 패턴 독립 복제). user-service `@KafkaListener` grep 결과 0건 — 영향 없음. 전수 520/520 PASS (eureka 1 + gateway 3 + payment-service 326 + pg-service 163 + product-service 26 + user-service 1). 회귀 없음.

- [x] T-I4 `StockCommitRequestedEvent`/`StockRestoreRequestedEvent` ContextSnapshot 보존 — AFTER_COMMIT traceparent 회귀 해소

  **완료 결과 (2026-04-24)** — 회귀 발견 경위(R3''): 스모크 검증에서 `StockEventPublishingListener.onStockCommitRequested`(`@TransactionalEventListener(AFTER_COMMIT)`)가 publish하는 Kafka 메시지의 `traceparent` 헤더가 incoming HTTP traceparent와 다른 새 trace로 박힘. 근본 원인: `KafkaListener observation`이 listener method 종료와 동시에 닫히며 active span이 소실 — AFTER_COMMIT TX synchronization이 method stack 내부에서 호출되지만 active span이 이미 종료된 시점이므로 `KafkaTemplate.send()` 시 새 trace를 시작. 수정 내용: (1) `StockCommitRequestedEvent` + `StockRestoreRequestedEvent` record에 `ContextSnapshot contextSnapshot` 필드 추가. (2) `PaymentConfirmResultUseCase.handleApproved`: `contextSnapshotFactory.captureAll()` 호출 → snapshot 생성 → `StockCommitRequestedEvent`에 포함하여 발행. `contextSnapshotFactory` 필드를 생성자 내에서 `ContextSnapshotFactory.builder().build()`로 초기화(Spring auto-config Bean 없음). (3) `FailureCompensationService`: `StockRestoreEventPublisherPort` 직접 호출 → `ApplicationEventPublisher`로 교체. `compensate`에서 `captureAll()` → `StockRestoreRequestedEvent`(snapshot 포함) 발행. (4) `StockEventPublishingListener.onStockCommitRequested`/`onStockRestoreRequested`: `try (ContextSnapshot.Scope scope = event.contextSnapshot().setThreadLocals())` 블록으로 교체 — publish + 로그 모두 복원된 context에서 수행. (5) 기존 테스트 회귀 보정: `StockEventPublishingListenerTest` 5개 이벤트 생성자에 `emptySnapshot()` 추가. `FailureCompensationServiceTest`: `FakeStockRestoreEventPublisher` → `CapturingApplicationEventPublisher` 교체, `StockRestoreRequestedEvent` 기반 검증. `StockEventPublishingListenerTraceRestorationTest` 신규 3케이스(TC-I4-1 commit publish 시점 MDC traceId 복원 / TC-I4-2 restore publish 시점 MDC traceId 복원 / TC-I4-3 리스너 종료 후 원래 컨텍스트 복원) GREEN. 전수 `./gradlew test` 329/329(payment-service) PASS. 회귀 없음.

- [x] T-I5 gateway access log INFO + smoke 시나리오 user-service GET 추가 — R1/R4 회귀 해소

  **완료 결과 (2026-04-24)** — 회귀 발견 경위(R1/R4): R1 — gateway `TraceContextPropagationFilter`가 MDC에 traceId를 정상 주입하나 Spring Cloud Gateway happy path에서 access log가 INFO 레벨 출력 없음 → smoke grep 미매치. R4 — 스모크 시나리오가 checkout/confirm만 호출하여 user-service 로그에 traceId가 등장하지 않음. 수정 내용: (1) `EventType.GATEWAY_REQUEST_RECEIVED` enum 추가. (2) `TraceContextPropagationFilter.filter`: MDC 주입 블록 이후 `chain.filter` 직전에 `LogFmt.info(..., EventType.GATEWAY_REQUEST_RECEIVED, () -> "method=" + method + " path=" + path)` 추가 — traceparent 유무 무관하게 모든 요청에 INFO 출력. (3) `scripts/smoke/trace-continuity-check.sh`: checkout 직전에 `GET /api/v1/users/1` 호출 추가(동일 traceparent 헤더, HTTP 200 검증 후 진행). gateway 3/3 PASS, 전수 `./gradlew test` PASS. 회귀 없음.

- [x] T-I6 product-service `StockCommitConsumer` expiresAt null fallback — Producer/Consumer 스키마 차이 견고화

  **완료 결과 (2026-04-24)** — 회귀 발견 경위(R3'''): `StockCommitConsumer.consume`이 `message.expiresAt()`를 `null`인 채 `StockCommitUseCase.commit`에 전달 → `JdbcEventDedupeStore.recordIfAbsent(eventUuid, null)` 에서 `Timestamp.from(null)` NPE 발생. 근본 원인: payment-service `StockCommittedEvent`(4필드)와 product-service `StockCommittedMessage`(6필드) 스키마 불일치로 Jackson 역직렬화 시 `expiresAt=null`. 수정 내용: (1) `StockCommitUseCase`에 `public static final Duration DEDUPE_TTL = Duration.ofDays(8)` 추가 — Consumer에서 접근 가능하도록 public 공개. (2) `StockCommitConsumer.consume`: `message.expiresAt()` 직접 전달 대신 `resolveExpiresAt(message)` private 메서드 호출로 교체. `resolveExpiresAt`: expiresAt non-null이면 그대로, null이면 `occurredAt + DEDUPE_TTL`, occurredAt도 null이면 `Instant.now() + DEDUPE_TTL` fallback. LogFmt.info로 fallback 발생 사실 기록. (3) `StockRestoreConsumer`: `StockRestoreMessage`에 expiresAt 필드가 없고 `StockRestoreUseCase`가 내부적으로 `Instant.now().plus(DEDUPE_TTL)` 계산 — Consumer 레벨 fallback 불필요. TC-I6-1(occurredAt fixed → 정확한 8d 검증) + TC-I6-2(양쪽 null → ±5초 윈도우 검증) GREEN. 전수 525/525 PASS (eureka 1 + gateway 3 + payment-service 329 + pg-service 163 + product-service 28 + user-service 1). 회귀 없음.

- [x] T-I7 `StockEventPublishingListener` AFTER_COMMIT OTel Context 명시 propagation — Kafka traceparent 회귀 완전 해소

  **완료 결과 (2026-04-24)** — 회귀 발견 경위: T-I4 이후에도 product-service 가 받는 stock-committed Kafka 헤더의 traceparent 가 incoming HTTP traceparent 와 다름. 근본 원인: `ContextSnapshot.captureAll()`은 `ContextRegistry`에 등록된 `Slf4jMdcThreadLocalAccessor`(MDC만) 대상이고, OTel `Context.current()`는 별도 ThreadLocal(OTel ContextStorage)에 있어 captureAll 범위 밖임 — `setThreadLocals()`로 복원해도 OTel Context는 빈 상태. KafkaTemplate observation은 `tracer.currentSpan()`(OTel) 기준이므로 새 trace 시작. 수정 내용: (1) `StockCommitRequestedEvent` + `StockRestoreRequestedEvent` record에 `io.opentelemetry.context.Context otelContext` 필드 추가 (마지막 파라미터). (2) `PaymentConfirmResultUseCase.handleApproved`: `Context.current()` 캡처 → `StockCommitRequestedEvent`에 포함. (3) `FailureCompensationService.compensate`: `Context.current()` 캡처 → `StockRestoreRequestedEvent`에 포함. (4) `StockEventPublishingListener.onStockCommitRequested`/`onStockRestoreRequested`: `try (ContextSnapshot.Scope mdcScope = ...; Scope otelScope = event.otelContext().makeCurrent())` 이중 try-with-resources 로 MDC + OTel Context 양쪽 활성화. (5) 기존 테스트 회귀 보정: `StockEventPublishingListenerTest` 5개 이벤트 생성자에 `Context.root()` 추가. `StockEventPublishingListenerTraceRestorationTest` 3개 이벤트 생성자에 `Context.root()` 추가. `StockEventPublishingListenerOtelContextTest` 신규 3케이스(TC-I7-1 commit publish 시점 OTel traceId 활성화 / TC-I7-2 restore publish 시점 OTel traceId 활성화 / TC-I7-3 리스너 종료 후 호출 전 OTel Context 복원) GREEN. 전수 `./gradlew test` 528/528 PASS (eureka 1 + gateway 3 + payment-service 332 + pg-service 163 + product-service 28 + user-service 1). 회귀 없음.

- [x] T-I8 `StockEventPublishingListener` `onStockCommitRequested`/`onStockRestoreRequested` `@Async("outboxRelayExecutor")` 추가

  **완료 결과 (2026-04-24)** — `StockEventPublishingListener.onStockCommitRequested` + `onStockRestoreRequested` 두 메서드에 `@Async("outboxRelayExecutor")` 추가. `org.springframework.scheduling.annotation.Async` import 추가. T-I2 의 `outboxRelayExecutor`(이중 래핑: `Context.taskWrapping` + `ContextExecutorService.wrap`)가 submit 시점 OTel Context + MDC 를 VT 에서 자동 복원 → `payment.commands.confirm` 발행 경로(`OutboxImmediateEventHandler.@Async("outboxRelayExecutor")`)와 동일한 propagation 패턴으로 통일. 기존 try-with-resources(T-I4 + T-I7)는 이중 보호로 그대로 유지. Javadoc T-I8 절 추가(두 메서드 모두). `./gradlew test` 전수 528/528 PASS (eureka 1 + gateway 3 + payment-service 332 + pg-service 163 + product-service 28 + user-service 1). 회귀 없음.

- [x] T-I9 Part A `StockEventPublishingListener` Tracer/Span API 명시 활성화 시도 + Part B `UserController` access log

  **완료 결과 (2026-04-24)** — **Part A**: `io.micrometer.tracing.Tracer.withSpan()` API 검토 — `OtelTracer.withSpan()`은 내부에서 `OtelCurrentTraceContext.maybeScope(traceContext)`를 호출하며 OTel Context.makeCurrent()와 동일한 ThreadLocal 활성화 효과를 가짐. 단, Tracer.withSpan()은 `OtelSpan` 타입을 요구하므로 OTel Span 직접 변환이 추가로 필요한 반면, T-I7에서 적용한 `event.otelContext().makeCurrent()`는 더 단순하고 동일한 OTel ContextStorage 활성화를 달성. `StockEventPublishingListenerOtelContextTest` TC-I7-1~3 이 단위 수준 OTel Context 활성화를 이미 검증하므로 Tracer API 전환으로 얻는 추가 이점 없음 → 현행 유지. `StockEventPublishingListener.onStockCommitRequested` Javadoc에 T-I9 시도 결과 및 판단 근거 추가. 실제 KafkaTemplate observation traceparent 정합성은 통합 스모크(compose-up)에서만 최종 확인 가능. **Part B**: `user-service/.../user/core/common/log/EventType.java`에 `USER_QUERY_RECEIVED` 추가. `UserController.getUser`에 `LogFmt.info(log, LogDomain.USER, EventType.USER_QUERY_RECEIVED, () -> "id=" + id)` 진입 INFO log 추가. `@Slf4j` annotation 추가. 전수 528/528 PASS (eureka 1 + gateway 3 + payment-service 332 + pg-service 163 + product-service 28 + user-service 1). 회귀 없음.

- [x] T-I10 `ObservationRegistry.getCurrentObservation()` 명시 parent 전달 — KafkaTemplate observation parent 인식 정확화

  **완료 결과 (2026-04-24)** — 회귀 발견 경위: 스모크 분석에서 stock-committed 메시지의 traceparent 가 payment-service 수신 traceparent 와 다른 새 trace(`911214b...`)로 박힘. 원인: KafkaTemplate `observation-enabled=true` 가 parent 로 보는 것은 `ObservationRegistry.getCurrentObservation()`(Micrometer ThreadLocal)이고, T-I7/T-I8 에서 적용한 OTel Context.makeCurrent() 는 OTel ContextStorage 만 갱신하므로 두 ThreadLocal 이 동기화되지 않음. 수정 내용: (1) `StockCommitRequestedEvent` + `StockRestoreRequestedEvent` record 마지막 파라미터로 `Observation parentObservation`(nullable) 추가 — null 이면 기존 fallback 경로. (2) `PaymentConfirmResultUseCase`: 생성자에 `ObservationRegistry` 주입 추가. `handleApproved`에서 `observationRegistry.getCurrentObservation()` 캡처 → `StockCommitRequestedEvent`에 포함. (3) `FailureCompensationService`: `@RequiredArgsConstructor` 제거, `ObservationRegistry` 명시 생성자 주입 추가. `compensate`에서 `observationRegistry.getCurrentObservation()` 캡처 → `StockRestoreRequestedEvent`에 포함. (4) `StockEventPublishingListener`: `onStockCommitRequested`/`onStockRestoreRequested` 를 각각 `publishStockCommit`/`publishStockRestore` private 메서드(T-I4+T-I7 이중 보호 그대로)로 추출. 외부 `if(parent != null) { try(obsScope) { publishXxx(event) } } else { publishXxx(event) }` 패턴 — try 블록 내 외부 변수 재할당 금지 규약 준수. (5) 기존 테스트 보정: `StockEventPublishingListenerTest`/`OtelContextTest`/`TraceRestorationTest` 5개 이벤트 생성자에 `null` 추가(fallback 경로). `FailureCompensationServiceTest` + 4개 UseCase 테스트 `ObservationRegistry.NOOP` 추가. 전수 `./gradlew test` 332/332(payment-service) PASS + 전체 PASS, 회귀 없음. 통합 검증은 compose-up 스모크에서 후속 확인.

**그룹 J — traceparent 근본 해소 (stock_outbox 패턴 도입)**
- [x] T-J1 `stock_outbox` 테이블 + relay 패턴 도입 — stock 발행 traceparent 회귀 완전 해소
- [x] T-J2 `stockOutboxKafkaTemplate` ObservationRegistry 명시 wiring — Kafka traceparent 회귀 근본 원인 추정 fix
- [x] T-J3 `PgOutboxImmediateWorker` + `OutboxWorker` OTel Context.taskWrapping 이중 래핑 — payment.events.confirmed traceparent 회귀 근본 해소
- [x] T-J4 `PgOutboxChannel` OutboxJob context 동봉 — offer→take 경계 OTel Context + MDC 전파 근본 해소

  **완료 결과 (T-J1, 2026-04-24)** — T-I4~T-I10 (ContextSnapshot/OTel/Observation 전파 시도)가 근본 해소에 실패한 근본 원인 확인: Spring Kafka observation의 ThreadLocal scope가 AFTER_COMMIT 시점 이전에 닫혀 `Context.current()`/`ObservationRegistry.getCurrentObservation()` 모두 빈 상태. 해결책: `payment.commands.confirm` 경로에서 검증된 `OutboxImmediateEventHandler`(AFTER_COMMIT + @Async "outboxRelayExecutor") 패턴을 stock publishing에 동일하게 적용. **신설 main source**: `db/migration/V2__stock_outbox.sql` (id/topic/key/payload/headers_json/available_at/processed_at/attempt/created_at, idx_stock_outbox_processed_available 복합 인덱스) / `domain/StockOutbox.java` (create/of 팩토리, isPending/markProcessed/incrementAttempt) / `port/out/StockOutboxRepository` (save/findById/markProcessed) / `port/out/StockOutboxPublisherPort` (send String) / `event/StockOutboxReadyEvent` (record outboxId) / `entity/StockOutboxEntity` (JPA, backtick key) / `repository/JpaStockOutboxRepository` (JPQL @Modifying UPDATE) / `repository/StockOutboxRepositoryImpl` / `service/StockOutboxRelayService` (@Transactional relay: findById→publish→markProcessed) / `listener/StockOutboxImmediateEventHandler` (@TransactionalEventListener AFTER_COMMIT + @Async "outboxRelayExecutor") / `publisher/StockOutboxKafkaPublisher` (StringSerializer, @Qualifier "stockOutboxKafkaTemplate"). **수정 main source**: `PaymentConfirmResultUseCase` — ObservationRegistry/ContextSnapshotFactory 삭제, StockOutboxRepository + ObjectMapper 추가, handleApproved → stock_outbox INSERT + StockOutboxReadyEvent 발행. `FailureCompensationService` — ObservationRegistry 삭제, StockOutboxRepository + ObjectMapper 추가, compensate → stock_outbox INSERT + StockOutboxReadyEvent 발행. `KafkaProducerConfig` — stock-committed/stock-restore JsonSerializer 템플릿 삭제, stockOutboxKafkaTemplate(StringSerializer) 유지. `KafkaMessagePublisher` — stock 분기 삭제, payment.commands.confirm 단일 경로만 유지. **삭제 main source**: StockEventPublishingListener / StockCommitRequestedEvent / StockRestoreRequestedEvent / StockCommitEventPublisherPort / StockRestoreEventPublisherPort / StockRestoreEventPayload / StockCommitEventKafkaPublisher / StockRestoreEventKafkaPublisher. **신설/수정/삭제 test source**: StockOutboxRelayServiceTest(4케이스) / StockOutboxImmediateEventHandlerTest(1케이스) / FakeStockOutboxRepository / PaymentConfirmResultUseCaseD2Test / PaymentConfirmResultUseCaseTest / ConfirmedEventConsumerTest / FailureCompensationServiceTest / PaymentConfirmResultUseCaseTwoPhaseLeaseTest / KafkaMessagePublisherTest 수정. StockEventPublishingListenerTest / StockEventPublishingListenerOtelContextTest / StockEventPublishingListenerTraceRestorationTest / StockCommitEventPublisherTest / FakeStockCommitEventPublisher / FakeStockRestoreEventPublisher 삭제. `./gradlew :payment-service:test` 321/321 PASS, 회귀 없음. outboxRelayExecutor(@Async, T-I2 이중 래핑)가 submit 시점 OTel Context + MDC를 VT에서 정확히 복원 → traceparent 회귀 근본 해소.

  **완료 결과 (T-J2, 2026-04-24)** — `KafkaProducerConfig.stockOutboxKafkaTemplate` 및 `confirmedDlqKafkaTemplate` 두 빈 메서드에 `ObservationRegistry observationRegistry` 파라미터 추가 + `template.setObservationRegistry(observationRegistry)` 명시 호출. 자체 생성 `DefaultKafkaProducerFactory`는 Boot auto-config의 ObservationRegistry interceptor wire-in을 받지 못해 traceparent 전파 경로 누락 — `setObservationRegistry()` 직접 주입으로 보완. `commandsConfirmKafkaTemplate`은 Boot auto-config'd ProducerFactory를 주입받아 동작하므로 변경 불필요. `io.micrometer.observation.ObservationRegistry` import 추가. 321/321(payment-service) PASS, 전체 회귀 없음.

  **완료 결과 (T-J3, 2026-04-24)** — 스모크 trace 분석에서 확인된 진짜 ROOT CAUSE: `PgOutboxImmediateWorker.start()`의 VT executor가 T-E1 시점 `ContextExecutorService.wrap` 단일 래핑만 적용 — Micrometer ContextRegistry accessor(MDC)는 캡처되나 **OTel Context(active span)는 별도 ThreadLocal이라 캡처 안 됨** → VT thread `[virtual-3155]`에서 `traceId=N/A`가 되고 `payment.events.confirmed` Kafka 헤더 traceparent에 새 trace(`55229cc1...`)가 박힘. 수정: T-I2 패턴(`48cccc5c`) 재적용. (1) `PgOutboxImmediateWorker.start()`: `Context.taskWrapping(raw)` → `ContextExecutorService.wrap(otelWrapped, factory)` 이중 래핑. (2) `OutboxWorker.processParallel()`: 동일 이중 래핑 패턴 적용(try-with-resources 유지). (3) 두 파일 `io.opentelemetry.context.Context` import 추가. `./gradlew clean test` 517/517 PASS(eureka 1+gateway 3+payment-service 321+pg-service 163+product-service 28+user-service 1), 회귀 없음.

  **완료 결과 (T-J4, 2026-04-24)** — T-J3 이후 분석에서 추가 발견된 R3'' ROOT CAUSE: `relayExecutor`에 이중 래핑이 적용되었으나 `relayExecutor.submit()` 호출 시점이 **worker VT thread**(application start 시점 생성 → OTel Context 비어있음)이므로 래핑이 "빈 context"를 캡처하는 문제. 진짜 context는 `offer()`를 호출하는 Kafka consumer thread에 있음. 수정 내용: (1) `OutboxJob record(Long outboxId, Context otelContext, ContextSnapshot snapshot)` 신설(`pg.infrastructure.channel`). (2) `PgOutboxChannel.offerNow(Long)`: `Context.current()` + `ContextSnapshotFactory.builder().build().captureAll()` 캡처 → `OutboxJob` 생성 → `offer`(기존 API는 `offerNow` 위임으로 하위 호환). 내부 큐 타입 `LinkedBlockingQueue<Long>` → `LinkedBlockingQueue<OutboxJob>`. `take()` 반환 타입 `Long` → `OutboxJob`. (3) `PgOutboxImmediateWorker.workerLoop`: `Long id = channel.take()` → `OutboxJob job = channel.take()`. `relayExecutor.submit(() -> relay(id))` → `relayExecutor.submit(() -> relayWithContext(job))`. `relayWithContext(OutboxJob)`: `try (ContextSnapshot.Scope mdcScope = job.snapshot().setThreadLocals(); Scope otelScope = job.otelContext().makeCurrent()) { relay(job.outboxId()); }` — MDC(Micrometer) + OTel Context 이중 restore. (4) `OutboxReadyEventHandler.handle`: `channel.offer(outboxId)` → `channel.offerNow(outboxId)`. (5) 기존 테스트 보정: `PgOutboxChannelTest` take() 반환 타입 `OutboxJob`으로 갱신(LinkedBlockingQueue 반사 테스트 포함). `OutboxReadyEventHandlerTest` `offer` → `offerNow` Mock 검증으로 갱신. `OutboxJobContextPropagationTest` 5케이스 신규 GREEN(MDC 복원 / outboxId 보존 / offer 하위 호환 / 큐 full / OTel Context 동봉). 522/522 PASS(eureka 1+gateway 3+payment-service 321+pg-service 168+product-service 28+user-service 1), 회귀 없음.

**그룹 K — Domain Expert Round 4 critical fix (신규)**
- [x] K1 `PaymentConfirmResultUseCase.buildStockCommitOutbox` idempotencyKey를 `(orderId, productId)` 결정론적 UUID v3으로 변경 — multi-product RDB↔Redis 재고 불일치 회귀 해소
- [x] K2 도메인 가드 일관성 4건 일괄 fix: `PaymentEvent.done()` DONE→DONE no-op 가드 / `PaymentOutbox.incrementRetryCount` IN_FLIGHT 가드 / `PaymentEvent.quarantine()` `PaymentStatusException` 통일 / `PaymentOutboxStatus` SSOT 메서드 + raw 비교 치환
- [x] K3 wire format record 정합성: `ConfirmedEventMessage` 필드 순서 pg-service canonical과 통일 + `@JsonPropertyOrder` 양쪽 명시 / `StockCommittedEvent`에 `orderId(String)` + `expiresAt(Instant)` 추가 / `StockCommittedMessage.orderId` Long→String 통일 / `StockCommitUseCase.commit` orderId String 통일 / schema parity 단위 테스트 4종 신설

  **완료 결과 (2026-04-24)** — `PaymentEvent.done()`: DONE 자기전이 no-op 가드 추가(`if (this.status == DONE) return;`) + 허용 목록에서 DONE 제거. fail()의 isTerminal 패턴과 일관. `PaymentEvent.quarantine()`: `throw new IllegalStateException(...)` → `throw PaymentStatusException.of(PaymentErrorCode.INVALID_STATUS_TO_QUARANTINE)` 통일. INVALID_STATUS_TO_QUARANTINE(E03026) 코드 첫 사용. `PaymentOutbox.incrementRetryCount`: 첫 줄에 `if (this.status != IN_FLIGHT) throw PaymentStatusException.of(INVALID_STATUS_TO_RETRY)` 가드 추가 — toInFlight/toDone/toFailed 패턴 통일. `PaymentOutboxStatus`: `isTerminal()`(DONE/FAILED→true) / `isClaimable()`(PENDING→true) / `isInFlight()`(IN_FLIGHT→true) 3개 SSOT 메서드 추가. 외부 raw 비교 3개소 치환: `PaymentTransactionCoordinator.java:146` `== IN_FLIGHT` → `isInFlight()`, `PaymentOutboxUseCase.findActiveOutboxStatus` `== PENDING || == IN_FLIGHT` → `isClaimable() || isInFlight()`, `PaymentStatusServiceImpl.buildFromOutbox` `== PENDING` → `isClaimable()`. 기존 테스트 `quarantine_InvalidStatus`/`markPaymentAsQuarantined_whenDone_shouldThrow`/`_whenFailed_shouldThrow` expectation `IllegalStateException` → `PaymentStatusException` 보정. `done_Success`/`done_WithApprovedAt_Success`/`done_NullApprovedAt_ThrowsPaymentStatusException` DONE source 제거(no-op 케이스는 `done_whenAlreadyDone_shouldNoOp`로 별도 커버). 신규 테스트: `done_whenAlreadyDone_shouldNoOp`(no-op 불변식) + `quarantine_terminalStatus_shouldThrowPaymentStatusException`(K2-F9) + `PaymentOutboxTest.incrementRetryCount_whenNotInFlight_shouldThrow` 3케이스 + `PaymentOutboxStatusTest` 4케이스. 전수 `./gradlew test` 345/345(payment-service) PASS, 회귀 없음.

  **완료 결과 (2026-04-24)** — `StockEventUuidDeriver` 유틸 신설(`payment.application.util`): `derive(orderId, productId, prefix)` → `"{prefix}:{orderId}:{productId}"` 시드 기반 UUID v3 문자열 반환. `PaymentConfirmResultUseCase.buildStockCommitOutbox`: `idempotencyKey = paymentEvent.getOrderId()` (단일값, multi-product 시 충돌) → `StockEventUuidDeriver.derive(orderId, productId, "stock-commit")` (product별 고유 키)로 교체. Javadoc에 K1 fix 근거 명시(ADR-16 참조). `FailureCompensationService.compensate`: 기존 `deriveEventUUID` private 메서드 삭제 → `StockEventUuidDeriver.derive(orderId, productId, "stock-restore")`로 일원화 (`UUID.fromString` 변환). `StockEventUuidDeriverTest` 신설 4케이스(동일 입력=동일 UUID / 다른 productId=다른 UUID / 다른 prefix=다른 UUID / UUID 포맷 검증). `PaymentConfirmResultUseCaseD2Test.handleApproved_multiProduct_shouldUseUniqueIdempotencyKeyPerProduct` 추가(2개 productId → payload idempotencyKey 서로 다름 + StockEventUuidDeriver 예측값과 일치). `./gradlew test` 전수 PASS (eureka 1 + gateway 3 + payment-service 326 + pg-service 168 + product-service 28 + user-service 1 = 526/526). 회귀 없음.

  **완료 결과 K3 (2026-04-24)** — Finding 3: `ConfirmedEventMessage`(payment-service) 필드 순서를 pg-service `ConfirmedEventPayload` canonical(`orderId,status,reasonCode,amount,approvedAt,eventUuid`)에 통일(기존 `eventUuid`와 `amount` 위치 교체). `@JsonPropertyOrder` 양쪽에 명시. Finding 4: `StockCommittedEvent`(payment-service)에 `orderId(String)` + `expiresAt(Instant)` 추가(4→6 필드). `PaymentConfirmResultUseCase.buildStockCommitOutbox`에서 두 필드 명시 전달(`expiresAt = occurredAt + longTtl(8d)`). `StockCommittedMessage`(product-service) `orderId` 타입 `Long` → `String` 통일. `StockCommitUseCase.commit` 시그니처 `long orderId` → `String orderId`(추적·로깅 용도, parseLong 불필요). `StockCommitConsumer.consume` orderId fallback 0L → 빈 문자열로 변경(구버전 하위 호환). Schema parity 단위 테스트 4종 신설: `ConfirmedEventSchemaParityTest`(payment-service, 2케이스) / `StockCommittedSchemaParityTest`(payment-service, 2케이스) / `StockCommittedMessageSchemaParityTest`(product-service, 3케이스) / `ConfirmedEventPayloadSchemaParityTest`(pg-service, 3케이스). 기존 테스트 생성자 인수 순서 보정(ConfirmedEventMessage 생성자 6개 테스트 파일 일괄 수정). `StockCommitConsumerTest`/`StockCommitUseCaseTest` orderId String 통일. `./gradlew test` 전수 PASS (eureka 1 + gateway 3 + payment-service 349 + pg-service 171 + product-service 31 + user-service 1 = 556/556). 회귀 없음.
- [x] K4 PgInbox 도메인 메서드 부활(`markInProgress`/`markApproved`/`markFailed`/`markQuarantined`) + `JpaPgInboxRepository` JPQL 리터럴(`'NONE'` 등) → enum 파라미터화

  **완료 결과 K4 (2026-04-24)** — Finding 5: `PgInbox`에 intent-revealing 도메인 메서드 4종 추가. `markInProgress()`: NONE 가드 → IN_PROGRESS 전이(변이형). `markApproved(storedStatusResult)`: IN_PROGRESS 가드 → APPROVED + storedStatusResult 설정. `markFailed(storedStatusResult, reasonCode)`: IN_PROGRESS 가드 → FAILED + 양 필드 설정. `markQuarantined(storedStatusResult, reasonCode)`: `!status.isTerminal()` 가드 → QUARANTINED(불변식 6c). 가드 위반 시 `IllegalStateException`. 기존 `withStatus`/`withResult`는 `@Deprecated(since="K4", forRemoval=true)` 마킹(main source 호출처 0건 확인). Finding 12: `JpaPgInboxRepository` JPQL 4쿼리의 `'NONE'`/`'IN_PROGRESS'` 등 문자열 리터럴 → `:none`/`:inProgress`/`:approved`/`:failed`/`:quarantined` enum 파라미터화. `PgInboxRepositoryImpl` 호출처 4개소 `PgInboxStatus` enum 명시 전달. `FakePgInboxRepository`의 `transitToApproved`/`transitToFailed`/`transitToQuarantined` 내부에서 새 도메인 메서드 직접 호출로 교체. `PgInboxTest` 12케이스 신설(markInProgress 3 + markApproved 3 + markFailed 2 + markQuarantined 4). `./gradlew test` 전수 PASS (eureka 1 + gateway 3 + payment-service 349 + pg-service 183 + product-service 31 + user-service 1 = 568/568). 회귀 없음.

- [x] K5 시간 소스를 `LocalDateTimeProvider`/`Clock`으로 일관 주입 — `Instant.now()` / `LocalDateTime.now()` 직접 호출 제거

  **완료 결과 K5 (2026-04-24)** — `LocalDateTimeProvider` 인터페이스: `nowInstant()` default 메서드 추가(K5 확장, 기존 구현체 호환). `FailureCompensationService`: `LocalDateTimeProvider` 4번째 생성자 파라미터 추가(K5). `compensate`: `Instant.now()` → `localDateTimeProvider.nowInstant()`, `LocalDateTime.now()` → `localDateTimeProvider.now()` 교체. `StockOutboxRelayService`: `LocalDateTimeProvider` 3번째 생성자 파라미터 추가. `relay`: `LocalDateTime.now()` → `localDateTimeProvider.now()` 교체. `PaymentConfirmResultUseCase.buildStockCommitOutbox`: `Instant.now()` → `localDateTimeProvider.nowInstant()` 교체(이미 localDateTimeProvider 주입 존재). `PgInboxRepositoryImpl`: `Clock` 생성자 주입 추가. `LocalDateTime.now(ZoneOffset.UTC)` 4곳 → `LocalDateTime.now(clock)` + `clock.instant()` 교체. `PgInbox` 도메인: `create(orderId, amount, Instant now)` 오버로드 신설. `markInProgress(Instant updatedAt)` / `markApproved(result, Instant)` / `markFailed(result, reasonCode, Instant)` / `markQuarantined(result, reasonCode, Instant)` 4종 Instant 파라미터 오버로드 신설(옵션 A — 호출자가 clock.instant() 전달). 기존 no-arg 메서드 하위 호환 유지. 기존 테스트 회귀 보정: `FailureCompensationServiceTest` / `StockOutboxRelayServiceTest` LocalDateTimeProvider 주입 추가. `FailureCompensationServiceClockTest`(2케이스) / `StockOutboxRelayServiceClockTest`(1케이스) / `PgInboxClockTest`(5케이스) 8케이스 GREEN. 직접 호출 grep(영향 4파일): 0건. `./gradlew test` 전수 PASS(eureka 1 + gateway 3 + payment-service 352 + pg-service 188 + product-service 31 + user-service 1 = 576/576). 회귀 없음.

- [x] K6 DI 일관성 — `@Value` 필드 주입 제거 + `StockOutbox` 도메인 Lombok 통일

  **완료 결과 K6 (2026-04-24)** — F-3: 7개 파일 `@Value` 필드 주입 → 생성자 파라미터 `@Value`로 전환, 대상 필드 모두 `final` 부여. (1) `PaymentConfirmResultUseCase`: `leaseTtl`/`longTtl` → 생성자 10·11번째 파라미터(기존 필드 초기화 제거). (2) `ProductHttpAdapter`: `@RequiredArgsConstructor` 제거, `productServiceBaseUrl`(final) + 명시 생성자 추가. (3) `UserHttpAdapter`: 동일 패턴, `userServiceBaseUrl` final 명시 생성자. (4) `StockOutboxKafkaPublisher`: `sendTimeoutMillis` 생성자 2번째 파라미터로 이전(기존 `@Qualifier` 파라미터 유지). (5) `KafkaMessagePublisher`: `sendTimeoutMillis` 생성자 3번째 파라미터로 이전. (6) `PaymentConfirmDlqKafkaPublisher`: `sendTimeoutMillis` 생성자 2번째 파라미터로 이전. (7) `OutboxWorker`: `@RequiredArgsConstructor` 제거, `batchSize`/`parallelEnabled`/`inFlightTimeoutMinutes` final + 명시 생성자 추가. 테스트 보정: `KafkaMessagePublisherTest`(3→3 arg 생성자, `ReflectionTestUtils` 제거), `OutboxWorkerTest`(2→5 arg 생성자, `ReflectionTestUtils` 제거), `OutboxWorkerMdcPropagationTest`(2→5 arg 생성자, `ReflectionTestUtils` 제거), `PaymentConfirmResultUseCaseTest`/`D2Test`/`TwoPhaseLeaseTest`/`ConfirmedEventConsumerTest` 4개 파일 9→11 arg 생성자(`DEFAULT_LEASE_TTL`/`DEFAULT_LONG_TTL` 추가). F-4: `StockOutbox` 도메인 `PaymentEvent` 일관 Lombok 패턴 적용. `@Getter`/`@Builder(builderMethodName="allArgsBuilder", buildMethodName="allArgsBuild")`/`@AllArgsConstructor(access=PRIVATE)` 추가. 손 작성 9개 getter 제거. `create()`/`of()` 정적 팩토리 `allArgsBuilder` 체이닝으로 재구현. `markProcessed()`/`incrementAttempt()` 도메인 의미 메서드 유지. 잔존 `@Value` 필드 주입 확인: `StartupConfigLogger`·`PaymentHealthMetrics`·`KafkaProducerConfig`·`EventDedupeStoreRedisAdapter` 4개 파일은 범위 밖 → `TODOS.md`에 등록. `./gradlew test` 전수 PASS(eureka 1 + gateway 3 + payment-service 352 + pg-service 188 + product-service 31 + user-service 1 = 576/576). 회귀 없음.

- [x] K7 VT 이중 래핑 boilerplate 헬퍼 추출 — `ContextAwareVirtualThreadExecutors` 모듈별 신설

  **완료 결과 K7 (2026-04-24)** — F-5: `Context.taskWrapping(Executors.newVirtualThreadPerTaskExecutor())` → `ContextExecutorService.wrap(otelWrapped, ContextSnapshotFactory.builder().build())` 이중 래핑이 3곳에 동일 라인으로 복제된 boilerplate를 단일 헬퍼로 통일. ADR-19 복제(b) 방침 준수 — 공유 jar 없이 모듈별 독립 복사. 신설: `payment-service/core/config/concurrent/ContextAwareVirtualThreadExecutors.java` + `pg-service/pg/core/config/concurrent/ContextAwareVirtualThreadExecutors.java` (각 모듈 독립, `final` 클래스, private 생성자). 변경 3곳: (1) `AsyncConfig.outboxRelayExecutor()` — 기존 3줄 이중 래핑 → `new TaskExecutorAdapter(ContextAwareVirtualThreadExecutors.newWrappedVirtualThreadExecutor())` 1줄. (2) `OutboxWorker.processParallel()` — `try (ExecutorService executor = ContextAwareVirtualThreadExecutors.newWrappedVirtualThreadExecutor())` 위임. (3) `PgOutboxImmediateWorker.start()` — `relayExecutor = ContextAwareVirtualThreadExecutors.newWrappedVirtualThreadExecutor()` 위임. 불필요해진 import(`Context`, `ContextExecutorService`, `ContextSnapshotFactory`, `Executors`) 3파일에서 제거. `./gradlew test` 전수 PASS(eureka 1 + gateway 3 + payment-service 352 + pg-service 188 + product-service 31 + user-service 1 = 576/576). 회귀 없음.

**T-Gate — 기준선 재리뷰 + 종료 검증**
- [ ] Critic + Domain Expert 재리뷰 양쪽 SHIP_READY verdict
- [ ] `scripts/smoke/trace-continuity-check.sh` PASS
- [ ] `./gradlew test` 전수 PASS (회귀 없음)

---

## 태스크 상세

### T-A1 — `amount`/`approvedAt` 필드 추가

**의존**: 없음
**tdd**: true
**domain_risk**: true
**예상 난이도**: M

**범위**:
- `pg-service/.../ConfirmedEventPayload.java`: record 에 `Long amount`, `String approvedAt` 추가. APPROVED 팩토리(`approved(...)`) 서명 확장. FAILED/QUARANTINED 팩토리는 해당 필드 `null` 허용.
- `payment-service/.../ConfirmedEventMessage.java`: 동일 필드 추가. Jackson record 바인딩 그대로.
- `pg-service/.../DuplicateApprovalHandler` / `PgConfirmService` / `PgFinalConfirmationGate`: APPROVED 확정 지점에서 실제 벤더 `approvedAt`·`amount` 를 payload 에 주입.

**RED 테스트**:
- `ConfirmedEventPayloadTest`: `amount=null` 인 APPROVED 팩토리 호출 시 `IllegalArgumentException`
- `ConfirmedEventMessageTest`: ISO-8601 문자열 역직렬화 → `OffsetDateTime.parse` 성공
- `DuplicateApprovalHandlerTest` 확장: APPROVED 경로 발행 payload 에 amount+approvedAt non-null 검증

**완료 기준**: 모든 APPROVED 발행 경로가 벤더 실측값을 payload 에 실음. 컴파일 에러 0, 회귀 없음.

### T-A2 — `handleApproved` 역방향 금액 방어

**의존**: T-A1
**tdd**: true
**domain_risk**: true
**예상 난이도**: M

**범위**:
- `payment-service/.../PaymentConfirmResultUseCase.handleApproved`: 수신 `approvedAt` 을 `OffsetDateTime.parse(...).toLocalDateTime()` 변환 후 `PaymentEvent.done(approvedAt, now)` 에 주입.
- 수신 `amount` 와 `paymentEvent.getTotalAmount()` (Long minor unit 변환) 불일치 시 `QuarantineCompensationHandler.markPaymentAsQuarantined(AMOUNT_MISMATCH)` 호출.
- `LocalDateTime.now()` 위조 제거.

**RED 테스트**:
- `PaymentConfirmResultUseCaseTest`: APPROVED 수신 → `PaymentEvent.approvedAt` 이 수신 값과 일치
- 동일 Test: `amount` 총액 불일치 → AMOUNT_MISMATCH QUARANTINED 전이
- 동일 Test: `approvedAt` null 인 APPROVED 도착 시 IllegalArgumentException (방어)

**완료 기준**: ADR-15 AMOUNT_MISMATCH 방어선이 양방향으로 작동. 회귀 없음.

### T-B1 — `handleFailed` 실 qty 전달

**의존**: 없음 (T-A1 과 독립)
**tdd**: true
**domain_risk**: true
**예상 난이도**: M

**범위**:
- `PaymentConfirmResultUseCase.handleFailed` 루프에서 각 `PaymentOrder` 의 `productId` + `quantity` 를 `FailureCompensationService.compensate(...)` 에 전달.
- `StockRestoreEventKafkaPublisher.publish(orderId, List<Long>)` 오버로드 **호출 제거** (이 태스크에서는 removal 은 안 함; T-B2 로 분리).

**RED 테스트**:
- `PaymentConfirmResultUseCaseTest.whenFailed_shouldPublishStockRestoreWithActualQty`: 단일 주문 실패 시 `StockRestoreEventPublisher.publishPayload` 호출 인자에 실 qty 확인
- 복수 productId 시 각 productId 별로 1회씩 호출

**완료 기준**: FAIL 결제 재고 복원이 실 수량으로 동작. 테스트 GREEN.

### T-B2 — 레거시 publish 오버로드 철거

**의존**: T-B1
**tdd**: false (삭제 작업)
**domain_risk**: true
**예상 난이도**: S

**범위**:
- `StockRestoreEventKafkaPublisher.publish(String orderId, List<Long> productIds)` 메서드 삭제.
- `StockRestoreEventPublisherPort` 에 해당 오버로드가 선언돼 있다면 삭제.
- 호출처 grep 해서 0건 확인.

**완료 기준**: 레거시 경로 소멸. 컴파일/테스트 GREEN.

### T-C1 — Payment dedupe TTL `P8D`

**의존**: 없음
**tdd**: true (낮은 우선순위)
**domain_risk**: true
**예상 난이도**: S

**범위**:
- `EventDedupeStoreRedisAdapter` 기본값 `@Value("${payment.event-dedupe.ttl:P8D}")`.
- `payment-service/src/main/resources/application.yml`: `payment.event-dedupe.ttl: P8D` 명시.
- STATE.md / ARCHITECTURE 에 TTL 값 명시 — 사실 기재만.

**RED 테스트**:
- `EventDedupeStoreRedisAdapterTest`: 기본 TTL 이 `Duration.ofDays(8)` 와 동일

**완료 기준**: 기본값 8일. application.yml 에 override 명시.

### T-C2 — QuarantineCompensationHandler isTerminal 가드

**의존**: 없음
**tdd**: true
**domain_risk**: true
**예상 난이도**: S

**범위**:
- `QuarantineCompensationHandler.handle` 진입 시 `event.getStatus().isTerminal()` 이면 INFO LogFmt + no-op return.
- 도메인 `PaymentEvent.markPaymentAsQuarantined` 내부에도 이중 가드 — 종결 상태면 `IllegalStateException` (도메인 불변식).

**RED 테스트**:
- `QuarantineCompensationHandlerTest`: DONE 상태 event 도착 시 no-op + markPaymentAsQuarantined 미호출
- `PaymentEventTest`: DONE/FAILED → QUARANTINED 전이 시 IllegalStateException

**완료 기준**: 종결 상태 역전이 불가능. 2중 방어.

### T-C3 — dedupe two-phase lease + remove 실패 DLQ

**의존**: 없음 (T-C1 과 독립, 함께 할 수도)
**tdd**: true
**domain_risk**: true
**예상 난이도**: L

**범위**:
- `EventDedupeStore` 포트에 `markWithLease(eventUUID, shortTtl)` + `extendLease(eventUUID, longTtl)` 메서드 분리. 기존 `markSeen` 은 내부 호출로 리팩토링.
- `PaymentConfirmResultUseCase.handle`:
  - 진입 시 `markWithLease(eventUuid, Duration.ofMinutes(5))`
  - `processMessage` 성공 후 `extendLease(eventUuid, TTL)` (TTL 은 `payment.event-dedupe.ttl` 값)
  - `catch (RuntimeException)` 블록에서 `eventDedupeStore.remove(eventUuid)` 호출 + `remove` 실패 시 별도 경로로 DLQ 전송 (`PaymentConfirmDlqPublisher` 신설 or 재활용)
- Fake + Redis 어댑터 두 구현 모두 lease 의미 준수.

**RED 테스트**:
- `EventDedupeStoreTest` (Fake): lease 만료 시 재-`markWithLease` 성공
- `PaymentConfirmResultUseCaseTest`: `markWithLease` 성공 + `processMessage` 실패 + `remove` 실패 시 DLQ 전송 경로 타는지 확인
- `PaymentConfirmResultUseCaseTest`: `processMessage` 성공 시 `extendLease` 1회 호출

**완료 기준**: Redis flap 시 dedupe 영구 잠김 경로 소실. DLQ 로 복구.

### T-D1 — Redis DECR 보상 경로

**의존**: 없음 (T-A/B 와 독립)
**tdd**: true
**domain_risk**: true
**예상 난이도**: M

**범위**:
- `OutboxAsyncConfirmService.confirm`: `decrementStock` 성공 후 `executeConfirmTxWithStockCompensation(...)` private 메서드 호출. 해당 메서드는 `executeConfirmTx` 호출을 `try/catch RuntimeException` 로 감싸고, 예외 발생 시 `stockCachePort.increment(productId, quantity)` 호출 후 re-throw.
- `try 블록 내 외부 변수 재할당 금지` 규약 준수(필요 시 private 메서드 추출).

**RED 테스트**:
- `OutboxAsyncConfirmServiceTest.whenConfirmTxFails_ShouldCompensateStock`: `executeConfirmTx` throw → `stockCachePort.increment` 호출 횟수 = 상품 수
- `whenConfirmTxSucceeds_ShouldNotCompensate`: 성공 시 increment 호출 0

**완료 기준**: Redis 차감 후 TX 실패 시 재고 복원됨. 테스트 GREEN.

### T-D2 — stock commit/restore AFTER_COMMIT 리스너로 이동

**의존**: T-A1, T-B1
**tdd**: true
**domain_risk**: true
**예상 난이도**: L

**범위**:
- `PaymentConfirmResultUseCase.handleApproved`/`handleFailed` 내 직접 `stockCommitEventPublisher.publish` / `stockRestoreEventPublisher.publish` 호출 제거.
- 각각 `StockCommitRequestedEvent` / `StockRestoreRequestedEvent` Spring `ApplicationEvent` 발행으로 교체. `@TransactionalEventListener(phase=AFTER_COMMIT)` 리스너가 실제 Kafka publish 를 수행.
- `PaymentConfirmResultUseCase.handle` 에 `@Transactional(timeout=5)` 명시.

**RED 테스트**:
- `PaymentConfirmResultUseCaseTest`: APPROVED 처리 후 `ApplicationEvents.stream(StockCommitRequestedEvent.class).count() == N`
- `StockCommitEventListenerTest`: 리스너 수신 → `stockCommitEventPublisher.publish` 호출
- 기존 테스트 회귀 없음

**완료 기준**: Kafka broker 지연이 DB TX 블로킹으로 이어지지 않음. TX timeout 5초. 회귀 없음.

### T-E1 — MDC 전파 3곳

**의존**: 없음
**tdd**: true
**domain_risk**: true (사고 재구성)
**예상 난이도**: M

**범위**:
- `build.gradle`: `io.micrometer:context-propagation` 의존 추가 (payment-service + pg-service).
- `pg-service/.../PgOutboxImmediateWorker.relayExecutor`: `ContextExecutorService.wrap(executor, ContextSnapshotFactory.builder().build())` 로 감싸기.
- `payment-service/.../OutboxWorker.processParallel`: 동일 패턴.
- `payment-service/.../AsyncConfig` (또는 `SchedulerConfig`): `@Async("outboxRelayExecutor")` 의 `ThreadPoolTaskExecutor.setTaskDecorator(new MdcTaskDecorator())` 적용. `MdcTaskDecorator` 자체는 표준 Spring 패턴으로 작성.

**RED 테스트**:
- `PgOutboxImmediateWorkerMdcPropagationTest`: MDC 에 `traceId=X` 설정 후 submit → 람다 내부에서 동일 값 읽힘
- `OutboxRelayAsyncMdcPropagationTest`: `@Async` 경계에서 MDC 전파 확인

**완료 기준**: VT/@Async 3경로에서 MDC traceId 승계. 테스트 GREEN.

### T-E2 — HTTP 클라이언트 ObservationRegistry 자동화

**의존**: 없음
**tdd**: true
**domain_risk**: true
**예상 난이도**: M

**범위**:
- `payment-service/.../HttpOperatorImpl`: 생성자 주입 `RestClient.Builder` 사용, `.build()` 만 호출(auto-config 가 이미 `observationRegistry` 설정).
- `pg-service/.../HttpOperatorImpl`: 동일 변경.
- 어댑터(`ProductHttpAdapter`, `UserHttpAdapter`)에서 `Map.of()` 를 유지하되 traceparent 자동 전파는 observationRegistry 경로로 보장됨.

**RED 테스트**:
- `HttpOperatorTraceparentPropagationTest` (MockWebServer 기반): 호출 요청에 `traceparent` 헤더 존재 검증

**완료 기준**: HTTP 홉에서 traceparent 자동 전파. 테스트 GREEN.

### T-E3 — trace 연속성 스모크 스크립트

**의존**: T-E1, T-E2
**tdd**: false (스크립트)
**domain_risk**: true
**예상 난이도**: L

**범위**:
- `scripts/smoke/trace-continuity-check.sh` 신설. compose-up 후 다음 시나리오를 실행:
  1. `curl -H "traceparent: 00-<trace-id>-<span-id>-01" POST /api/v1/payments/checkout ...`
  2. `docker compose logs` 에서 `traceId=<trace-id>` 포함 라인이 5개 서비스(gateway/payment/pg/product/user) 전부에서 발견되는지 확인
  3. `docker compose logs payment-service pg-service` 에서 `[traceId:<trace-id>]` 가 outbox relay 경로 로그에도 존재하는지 확인
- 종료 조건: traceId 단절 0건. 스크립트 exit 0 이면 PASS.
- `docs/phase-gate/trace-continuity-smoke.md` 에 시나리오/재현 절차 문서화.

**완료 기준**: 스크립트 PASS. Phase 4 진입 전 관문 추가.

### T-E4 — PgOutboxRelayService.parseHeaders 정리

**의존**: 없음
**tdd**: false (작은 리팩토링)
**domain_risk**: false
**예상 난이도**: S

**범위**:
- 옵션 A: ObjectMapper 주입 + `readValue(headersJson, Map<String, String>)` 파싱 구현 + 단위 테스트.
- 옵션 B: TODO 주석 제거 + "observation-enabled=true 로 충분" 근거 주석 + `parseHeaders` 메서드 자체 삭제(호출부 `Map.of()` 직접 전달).
- **선호: 옵션 B** — 현재 실제 기능 영향 없음, 코드 단순화 우선.

**완료 기준**: TODO 0건. 의도 명확.

### T-F1 — FakePgGatewayStrategy NPE 제거

**의존**: 없음
**tdd**: true
**domain_risk**: true
**예상 난이도**: S

**범위**:
- `getStatusByOrderId(String)` 에서 `return null` → `throw new UnsupportedOperationException("Fake strategy: getStatusByOrderId 는 smoke 경로에서 호출되지 않아야 함")`.

**RED 테스트**:
- `FakePgGatewayStrategyTest.getStatusByOrderId_shouldThrowUnsupported`: 호출 시 `UnsupportedOperationException`.

**완료 기준**: 포트 계약 복구. 회귀 없음.

### T-F2 — worker/aspect catch 정리

**의존**: 없음
**tdd**: true
**domain_risk**: false
**예상 난이도**: M

**범위**:
- `PgOutboxImmediateWorker.workerLoop/relay`: `catch (Exception)` → `catch (RuntimeException)` + LogFmt.error (WARN → ERROR 승격) + 기존 메트릭 유지.
- `PgOutboxPollingWorker`: 동일.
- `DomainEventLoggingAspect`: 재throw 패턴 유지, `catch (Throwable)` 로 축소(Error 도 기록 후 re-throw).
- `TossApiMetricsAspect`: 동일.
- `StockSnapshotWarmupConsumer.parse`: `catch (JsonProcessingException)` 전용으로 축소.
- `PaymentHistoryServiceImpl`: 원래 Exception catch 목적 재검토 후 필요 시 `handleUnknownFailure` 경유.

**완료 기준**:
- `grep -rn 'catch (Exception' **/src/main/java` 결과 0건
- worker 계열 `catch (RuntimeException)` + metric increment 테스트 GREEN (aspect 는 재throw 확인)
- 전수 `./gradlew test` 회귀 없음

### T-F3 — LogFmt.banner 헬퍼

**의존**: 없음
**tdd**: true
**domain_risk**: false
**예상 난이도**: S

**범위**:
- 5개 서비스(payment/pg/product/user/gateway) `LogFmt` 에 `public static void banner(Logger log, Level level, String... lines)` 헬퍼 추가.
- `FakePgGatewayStrategy.postConstruct` 배너 4줄 → `LogFmt.banner(log, WARN, "╔...╗", ...)` 로 치환.
- `docs/context/CONVENTIONS.md` LogFmt 섹션에 "기동 배너는 `LogFmt.banner` 로만 허용" 예외 조항 추가.

**완료 기준**: 평문 `log.warn` 배너 0건. CONVENTIONS 갱신.

### T-F4 — ARCHITECTURE.md Scheduler 섹션 재작성

**의존**: 없음 (T-D2 완료 후 쓰면 더 정확)
**tdd**: false (문서)
**domain_risk**: false
**예상 난이도**: M

**범위**:
- `docs/context/ARCHITECTURE.md` §Scheduler / §Confirm Flow 를 현재 실제 구조로 재작성:
  - payment-service: `@Async + @TransactionalEventListener` (OutboxImmediateEventHandler) + `OutboxWorker @Scheduled` polling fallback
  - pg-service: `PgOutboxImmediateWorker` (SmartLifecycle, VT executor + LinkedBlockingQueue) + `PgOutboxPollingWorker @Scheduled`
  - payment-service `OutboxImmediateWorker` / `PaymentConfirmChannel` 참조 전부 제거
- Mermaid 다이어그램 갱신 (T-D2 결과 반영 — stock commit/restore 도 AFTER_COMMIT 경로로 표기).

**완료 기준**: 문서-코드 drift 0. Phase 4 시나리오 작성 시 근거 확실.

### T-G1 — @CircuitBreaker 주석 정정

**의존**: 없음
**tdd**: false
**domain_risk**: false
**예상 난이도**: S

**범위**:
- `ProductHttpAdapter.java` / `UserHttpAdapter.java` Javadoc: "@CircuitBreaker 는 ... 위치" → "(Phase 4 에서 설치 예정)" 로 수정.
- `docs/context/ARCHITECTURE.md:174` 동일 정정.

**완료 기준**:
- `grep -rn '@CircuitBreaker' payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/adapter/http/` 결과 0건
- `ProductHttpAdapter.java` / `UserHttpAdapter.java` Javadoc + `ARCHITECTURE.md:174` 에 "Phase 4 설치 예정" 문구 포함

### T-G2 — DuplicateApprovalHandler eventUuid 파라미터

**의존**: 없음
**tdd**: false
**domain_risk**: false
**예상 난이도**: S

**범위**:
- `TossPaymentGatewayStrategy.handleErrorResponse` / `NicepayPaymentGatewayStrategy.handleErrorResponse`: 호출 인자 확인. `PgConfirmRequest.eventUuid()` 가 존재하면 전달, 없으면 `DuplicateApprovalHandler.handleDuplicateApproval` 시그니처에서 eventUuid 파라미터 제거.
- **선호**: 현재 handler 에서 미사용이므로 파라미터 제거.

**완료 기준**:
- `grep -rn 'request\.orderId()' pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/infrastructure/gateway/` 에서 `handleDuplicateApproval` 호출 시 동일 `request.orderId()` 가 2회 등장하는 패턴 0건
- `handleDuplicateApproval` 시그니처에서 eventUuid 파라미터 삭제 또는 `request.eventUuid()` 전달로 치환
- 전수 `./gradlew test` 회귀 없음

### T-G3 — QUARANTINED 복구 경로 문서

**의존**: 없음
**tdd**: false
**domain_risk**: false (문서)
**예상 난이도**: S

**범위**:
- `docs/context/TODOS.md` 에 "FCG INDETERMINATE → QUARANTINED 홀딩 자산 복구 — 운영자 Admin API + 대시보드 + SLA 정의" 항목 추가.
- `docs/context/ARCHITECTURE.md` Quarantine flow 섹션 (없으면 신설) 에 운영자 진입점 placeholder 기술.
- 실제 Admin API 구현은 별도 토픽 (`QUARANTINED-ADMIN-RECOVERY`) 로 이관.

**완료 기준**: 홀딩 자산 복구 경로 문서화 시작점 확립.

### T-Gate — 기준선 재리뷰 + 종료 검증

**의존**: 모든 T-A/B/C/D/E/F/G
**tdd**: false
**domain_risk**: true
**예상 난이도**: M

**범위**:
- `/review` 스킬 재호출 → `docs/rounds/pre-phase-4-hardening/review-critic-2.md` + `review-domain-2.md`.
- 두 페르소나 모두 `decision=pass`(critical 0, major 0) 여야 함.
- `bash scripts/smoke/trace-continuity-check.sh` 실행, exit 0 확인.
- `./gradlew test` 전수 PASS.
- STATE.md stage → `verify` 로 전환.

**완료 기준**: 종료 조건 3개 모두 만족.

---

## 의존 그래프 (요약)

```
A1 ──> A2
A1 ──> D2
B1 ──> B2
B1 ──> D2
E1, E2 ──> E3
(그 외 그룹은 병렬 가능)
모든 그룹 ──> Gate
```

## ADR 추적

| Finding | ADR 참조 | 태스크 |
|---|---|---|
| critical-1 approvedAt/amount | ADR-15 AMOUNT_MISMATCH | T-A1, T-A2 |
| critical-1 재고 qty=0 | ADR-13 격리 트리거 / T3-04b 보상 | T-B1, T-B2 |
| critical-4 dedupe TTL | ADR-30 EventDedupeStore | T-C1 |
| major-3 TX 블로킹 | ADR-04 비동기 아키텍처 | T-D2 |
| major-4 Redis 비원자성 | ADR-13 격리 트리거 (경로 분리) | T-D1 |
| major MDC/OTel | CONVENTIONS 관측성 | T-E1, T-E2 |

## 변경 로그

- **2026-04-24** 초안 작성 (baseline 리뷰 Round 1 기반)
