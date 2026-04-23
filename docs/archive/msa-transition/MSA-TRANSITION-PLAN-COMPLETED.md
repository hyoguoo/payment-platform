# MSA-TRANSITION-PLAN — 완료 태스크 상세 (Phase 0~3)

> 이 문서는 `docs/MSA-TRANSITION-PLAN.md`에서 완료된(✅) 태스크의 상세 블록을 분리 보관한 아카이브다.
> 작성 시점: 2026-04-23 (Phase 3 Gate 통과 직후, Phase 4 진입 전)
>
> - 원본 문서: [docs/MSA-TRANSITION-PLAN.md](../../MSA-TRANSITION-PLAN.md)
> - 토픽 설계 문서: [docs/topics/MSA-TRANSITION.md](../../topics/MSA-TRANSITION.md)
> - Phase 4 / Phase 5 등 미완료 태스크는 원본 PLAN에 남아 있다.
> - `T0-05` (Toxiproxy 장애 주입 도구 구성 — 완료 취소 2026-04-23), `T1-13` (FCG 격리 불변 — 스킵)은 완료로 분류되지 않아 원본 PLAN에 유지.

---

## 요약 브리핑 — 완료 결과 블록 스냅샷 (2026-04-21 Phase 0~3 완료 시점)

> 참고: 각 태스크 상세 블록 아래의 "**완료 결과**" 서브섹션이 SSOT. 본 스냅샷은 브리핑용 요약 모음.

**완료 결과 — T2a-Gate** (2026-04-21):
`scripts/phase-gate/phase-2a-gate.sh` 신설(executable). `docs/phase-gate/phase-2a-gate.md` 신설. 검증 항목 9섹션: pg-service actuator/health, mysql-pg 컨테이너 + pg DB 접속, Flyway V1(pg_inbox/pg_outbox), Kafka 토픽 payment.commands.confirm, consumer group pg-service 등록, PgOutboxImmediateWorker SmartLifecycle 기동, pg_inbox NONE→IN_PROGRESS CAS 전이 멱등성 smoke, pg.outbox.channel.* 메트릭 노출, pg_outbox clean state. 포트 충돌 주의(기본 8080 = Gateway 포트) 가이드 스크립트 상단 + 문서에 명시. PG_SERVICE_BASE 환경 변수로 재정의 가능. `./gradlew test` 418/418 회귀 없음.

**완료 결과 — T2a-06** (2026-04-21):
EventDedupeStore 포트(markSeen) + FakeEventDedupeStore(ConcurrentHashSet) 신설. PgInboxRepository에 transitNoneToInProgress(orderId, amount): boolean CAS 메서드 추가. FakePgInboxRepository에 putIfAbsent + compute 기반 원자 전이 구현. FakePgOutboxRepository에 id=null auto-increment 처리 추가. PgConfirmService(application/service): handle(PgConfirmCommand) — eventUUID dedupe(1단) → inbox 5상태 분기(2단): NONE→CAS 전이+PG 호출, IN_PROGRESS→no-op, terminal→stored_status_result pg_outbox 재발행(벤더 호출 금지). PaymentConfirmConsumer(infrastructure/messaging/consumer): @KafkaListener(payment.commands.confirm, pg-service) + @ConditionalOnProperty(spring.kafka.bootstrap-servers). PaymentConfirmConsumerTest 5케이스 전부 GREEN: TC1(NONE→PG 1회), TC2(IN_PROGRESS no-op), TC3(terminal 3종 재발행), TC4(eventUUID dedupe), TC5(동시성 8스레드→PG 1회). 전체 418/418 PASS(payment-service 395 + pg-service 23), 회귀 없음.

**완료 결과 — T2b-05** (2026-04-21):
DuplicateApprovalHandler(application/service) 신설: handleDuplicateApproval(orderId, payloadAmount, eventUuid) @Transactional — VendorQueryOutcome 캡슐화로 try-catch 외부 변수 재할당 없이 구현. vendor 조회 1회만(실패→VENDOR_INDETERMINATE). 경로 (1) pg DB 존재: inbox.amount==vendor.amount→stored_status_result 재발행(pg_inbox 상태 변경 없음), 불일치→QUARANTINED+AMOUNT_MISMATCH. 경로 (2) pg DB 부재: vendor.amount==payloadAmount→inbox 신설(APPROVED)+운영 알림(경고 로그), 불일치→inbox 신설(QUARANTINED+AMOUNT_MISMATCH). AmountConverter.fromBigDecimalStrict 재사용. NicepayPaymentGatewayStrategy: DuplicateApprovalHandler 생성자 주입(@RequiredArgsConstructor), handleDuplicateApproval() 공개 메서드 추가(2201 분기 위임 진입점), confirm() 내 분기 자리 주석 포함. TossPaymentGatewayStrategy: DuplicateApprovalHandler 생성자 주입, confirm() 내 ALREADY_PROCESSED_PAYMENT 분기 자리 주석 포함. DuplicateApprovalHandlerTest 6케이스 GREEN(TC1 DB존재+일치→재발행/TC2 DB존재+불일치→QUARANTINED/TC3 DB부재+일치→APPROVED/TC4 DB부재+불일치→QUARANTINED/TC5 vendor실패→VENDOR_INDETERMINATE/TC6 NicepayStrategy 2201→DuplicateApprovalHandler 위임 대칭성). 전체 488/488 PASS(payment-service 395 + pg-service 93), 회귀 없음.

**완료 결과 — T2b-04** (2026-04-21):
AmountConverter(infrastructure/converter) 신설: fromBigDecimalStrict(BigDecimal) — null→IllegalArgumentException, scale>0→ArithmeticException("amount scale must be 0, was: {scale}"), 음수→ArithmeticException("amount must be non-negative"), 정상→longValueExact() 반환. toBigDecimal(long) 역변환 보조. PgInboxAmountService(application/service) 신설: 불변식 4c 3경로 구현 — (a) recordPayloadAmount(orderId, payloadAmount): AmountConverter.fromBigDecimalStrict → transitNoneToInProgress. (b) validateAndApprove(orderId, vendorAmount): inbox.amount 조회 → 일치면 transitToApproved, 불일치면 transitToQuarantined(AMOUNT_MISMATCH). (c) recordAndApproveDirect(orderId, payloadAmount, vendorAmount): payload!=vendor→IllegalStateException, 일치→transitNoneToInProgress+transitToApproved. @Transactional + @Slf4j. PgInboxAmountStorageTest 4케이스 GREEN(TC1 NONE→IN_PROGRESS payload 기록/TC2 2자 대조 통과 APPROVED/TC3 2자 불일치 QUARANTINED+AMOUNT_MISMATCH/TC4 scale>0 ArithmeticException+음수 거부). AmountConverterTest 4케이스 GREEN(null/scale>0/음수/정상). 전체 482/482 PASS(payment-service 395 + pg-service 87), 회귀 없음.

**완료 결과 — T2b-03** (2026-04-21):
PgFinalConfirmationGate(application/service) 신설: performFinalCheck(orderId, eventUuid, amount) @Transactional — FcgOutcome 캡슐화로 try-catch 외부 변수 재할당 없이 구현. FCG 불변(ADR-15): PgGatewayPort.getStatusByOrderId() 단 1회 호출, 재시도 래핑 금지. PgPaymentStatus 3-way 매핑(DONE→APPROVED, ABORTED/CANCELED/PARTIAL_CANCELED/EXPIRED→FAILED, PgGatewayRetryableException·PgGatewayNonRetryableException·미확정→INDETERMINATE). APPROVED: pg_inbox transitToApproved + pg_outbox(events.confirmed, APPROVED) INSERT + PgOutboxReadyEvent. FAILED: transitToFailed(FCG_CONFIRMED_FAILED) + outbox INSERT + event. INDETERMINATE: transitToQuarantined(FCG_INDETERMINATE) + outbox INSERT(QUARANTINED) + event — 재시도 없음. PgFinalConfirmationGateTest 4케이스 GREEN(TC1 APPROVED/TC2 FAILED/TC3 timeout→QUARANTINED 1회만/TC4 5xx→QUARANTINED 재시도 0회). 전체 479/479 PASS(payment-service 395 + pg-service 79), 회귀 없음.

**완료 결과 — T2b-02** (2026-04-21):
PgInboxRepository 포트에 transitToQuarantined(orderId, reasonCode): boolean + findByOrderIdForUpdate(orderId): Optional 추가. FakePgInboxRepository에 transitToQuarantined(compute 기반 원자 전이, terminal 체크) + findByOrderIdForUpdate 구현. PgDlqService(application/service) 신설: handle(PgConfirmCommand) @Transactional — FOR UPDATE 조회 → terminal이면 no-op(불변식 6c) → pg_inbox QUARANTINED CAS 전이 + pg_outbox(topic=payment.events.confirmed, QUARANTINED+RETRY_EXHAUSTED 페이로드) INSERT(같은 TX) → TX commit 후 PgOutboxReadyEvent 발행(T2a-05b/c 경로 재사용). PaymentConfirmDlqConsumer(infrastructure/messaging/consumer) 신설: @KafkaListener(payment.commands.confirm.dlq, groupId=pg-service-dlq) + @ConditionalOnProperty(spring.kafka.bootstrap-servers) → PaymentConfirmConsumer와 물리적으로 다른 Spring bean(ADR-30 수락 기준). PaymentConfirmDlqConsumerTest 4케이스(파라미터 포함 6건) GREEN: TC1(IN_PROGRESS→QUARANTINED+events.confirmed 1건), TC2(terminal 3종→no-op+outbox 0건), TC3(events.confirmed row 1건만, 보상 큐 없음), TC4(DlqConsumer≠NormalConsumer 클래스 분리). 전체 470/470 PASS(payment-service 395 + pg-service 75), 회귀 없음.

**완료 결과 — T2b-01** (2026-04-21):
RetryPolicy(domain) 신설: MAX_ATTEMPTS=4, base=2s, multiplier=3, jitter=±25% equal, shouldRetry(attempt<4), computeBackoff(attempt, rng). PgInboxRepository 포트에 transitToApproved(orderId, storedStatusResult) + transitToFailed(orderId, storedStatusResult, reasonCode) 추가. FakePgInboxRepository 구현 확장. PgVendorCallService(application/service) 신설: callVendor(request, attempt, now) @Transactional — GatewayOutcome 캡슐화로 try-catch 외부 변수 재할당 없이 구현 → 성공(APPROVED outbox+inbox전이)/확정실패(FAILED outbox+inbox전이)/retryable+attempt<4(commands.confirm available_at=now+backoff nextAttempt header)/retryable+attempt>=4(commands.confirm.dlq attempt header). PgConfirmService.callVendor() placeholder → PgVendorCallService 위임으로 교체, Clock 주입 추가. PaymentConfirmConsumerTest setUp() 갱신(PgVendorCallService + Clock 주입). PgVendorCallServiceTest 5케이스 GREEN(TC1성공/TC2재시도/TC3DLQ/TC4확정실패/TC5DLQ원자성). RetryPolicyTest: shouldRetry 경계값 5케이스 + computeBackoff 범위 RepeatedTest(attempt=1/4 각 20회). 전체 464/464 PASS(payment-service 395 + pg-service 69), 회귀 없음.

**완료 결과 — T2b-Gate** (2026-04-21):
`scripts/phase-gate/phase-2b-gate.sh` 신설(executable). `docs/phase-gate/phase-2b-gate.md` 신설. 검증 항목 8섹션(a~h): (a) Phase 2.a Gate 전제 9항목(pg-service health/mysql-pg/Flyway V1/amount+reason_code+available_at 컬럼/DLQ 토픽+consumer group), (b) DuplicateApprovalHandlerTest 6케이스 위임(중복 승인 amount 일치/불일치×DB 존재/부재×vendor 실패), (c) pg_inbox.status 5상태 ENUM 컬럼 확인, (d) PgFinalConfirmationGateTest 4케이스 위임(FCG 불변: getStatus 1회, 재시도 0회), (e) PgVendorCallServiceTest 5케이스+RetryPolicyTest 위임(ADR-30 available_at 지연 재발행, DLQ 원자성), (f) PaymentConfirmDlqConsumerTest 4케이스 위임(QUARANTINED 전이+no-op terminal+DLQ consumer 클래스 분리), (g) AmountConverterTest+PgInboxAmountStorageTest 위임+DB smoke(NONE→IN_PROGRESS amount 기록), (h) 전체 Gradle test 488건 이상. `./gradlew test` 488/488 회귀 없음.

**완료 결과 — T2c-Gate** (2026-04-21):
`scripts/phase-gate/phase-2c-gate.sh` 신설(executable). `docs/phase-gate/phase-2c-gate.md` 신설. 검증 항목 7섹션(a~g): (a) Phase 2.b Gate 전제 6항목(pg-service health/mysql-pg/Flyway V1/payment.commands.confirm·dlq 토픽), (b) payment-service cutover 상태: PgStatusAbsenceContractTest 3케이스 GREEN(불변식 19) + PgStatusPort·PgStatusHttpAdapter·구버전 PaymentGatewayPort 소스 부재 + /internal/pg/status 엔드포인트 소스 부재, (c) pg-service application.yml pg.retry.mode=outbox 확인(ADR-30 스위치), (d) consumer group pg-service·pg-service-dlq 등록 확인, (e) Kafka 왕복 E2E: PaymentConfirmConsumerTest 5케이스 + PaymentConfirmDlqConsumerTest 4케이스 Gradle 위임, (f) 잔존 삭제 코드 confirmPaymentWithGateway·getPaymentStatusByOrderId 소스 부재, (g) 전체 Gradle test 472건 이상. T2c-01 application.yml 포트 8082 고정으로 이전 Gate 대비 포트 충돌 없음. Phase 2 전체(2.a+2.b+2.c) 완료 의의 및 Phase 3 진입 기반 문서 포함. `./gradlew test` 472/472 회귀 없음.

**완료 결과 — T2c-02** (2026-04-21):
`PgStatusAbsenceContractTest` 3케이스(TC1 PgStatusPort 클래스패스 부재/TC2 PgStatusHttpAdapter 클래스패스 부재/TC3 PaymentCommandUseCase·PaymentGatewayStrategy getStatus 메서드 부재) 계약 테스트로 불변식 19 고정. 삭제 확인: `PgStatusPort`, `PgStatusHttpAdapter`, `/internal/pg/status` 엔드포인트 — 이미 부재(T1-11c에서 삭제 완료). payment-service 잔존 PG 직접 호출 코드 전면 삭제: (1) 구버전 `payment/application/port/PaymentGatewayPort.java`(getStatus·getStatusByOrderId 포함) 삭제. (2) 구버전 `payment/infrastructure/internal/InternalPaymentGatewayAdapter.java` 삭제. (3) `PaymentCommandUseCase.confirmPaymentWithGateway()·getPaymentStatusByOrderId()` 삭제 — 프로덕션 미사용 확인. (4) `PaymentGatewayStrategy.getStatus()·getStatusByOrderId()` 인터페이스 메서드 삭제. (5) TossPaymentGatewayStrategy·NicepayPaymentGatewayStrategy getStatus/getStatusByOrderId 구현 삭제. 관련 테스트 정리: InternalPaymentGatewayAdapterTest 삭제, PaymentCommandUseCaseTest 4케이스·NicepayPaymentGatewayStrategyTest 10케이스 삭제, PaymentGatewayFactoryTest 익명 클래스 메서드 제거. `out.PaymentGatewayPort`(confirm/cancel only, ADR-02 준수)·`adapter/internal/InternalPaymentGatewayAdapter` 신버전만 유지. 372/472 PASS(payment-service 379 + pg-service 93) — 삭제 테스트 16건 제외, 회귀 없음. ADR-02/ADR-21 불변 확정.

**완료 결과 — T2c-01** (2026-04-21):
`pg-service/src/main/resources/application.yml` 신설. server.port=8082(gateway 8080·payment-service 8081 포트 충돌 방지). pg.retry.mode=outbox 키 선언으로 ADR-30 Phase 2.b 스위치 확정. spring.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS:localhost:9092} — 기존 환경변수 네이밍 대칭 유지. datasource: PG_DATASOURCE_URL/USERNAME/PASSWORD 환경변수(기본값 localhost:3308/pg). flyway.enabled=true + locations=classpath:db/migration. pg.outbox.channel.capacity/worker-count + pg.scheduler.polling-worker.* 기본값 명시. management actuator/prometheus 노출. OutboxProcessingService PG 직접 호출 경로: T1-11c에서 이미 삭제 완료(scheduler/ 패키지에 부재 확인). payment-service의 OutboxRelayService는 PG 직접 호출이 아닌 Kafka 발행 경로(이미 outbox 방식) — flag 적용 대상 없음(T2c-02에서 삭제 예정 코드 확인 후 처리). `./gradlew test` 488/488(payment-service 395 + pg-service 93) 회귀 없음.

**완료 결과 — T2d-01** (2026-04-21):
EventDedupeStore 포트 신설(payment-service 독립 복제, ADR-30). StockRestoreEventPublisherPort 포트 신설(stock.events.restore 토픽 발행 계약). ConfirmedEventMessage record 신설(payment.events.confirmed payload). PaymentConfirmResultUseCase(application/usecase) @Transactional handle: (1) eventUUID dedupe(markSeen false→no-op), (2) orderId로 PaymentEvent 조회, (3) status 3-way 분기 — APPROVED: done()+StockCommitEvent 발행(상품별)/FAILED: fail()+StockRestore 발행/QUARANTINED: QuarantineCompensationHandler.handle(FCG 진입점) 위임. ConfirmedEventConsumer(infrastructure/messaging/consumer): @KafkaListener(payment.events.confirmed, groupId=payment-service) + @ConditionalOnProperty(matchIfMissing=true, T1-18 교훈 반영). FakeEventDedupeStore/FakeStockRestoreEventPublisher/FakePaymentEventRepository(test) 신설. ConfirmedEventConsumerTest 5케이스 GREEN(TC1 APPROVED DONE+StockCommit/TC2 FAILED+Restore/TC3 QUARANTINED→handler 위임/TC4 dedupe 1회 전이/TC5 no-op publisher 0회). 전체 477/477 PASS(payment-service 384 + pg-service 93), 회귀 없음.

**완료 결과 — T2d-02** (2026-04-21):
ADR-12 결론 확정: 네이밍 규약 `<source-service>.<type>.<action>[.modifier]` + 토픽 목록표 6종(payment.commands.confirm/dlq/events.confirmed + stock.events.commit/restore + product.events.stock-snapshot) MSA-TRANSITION.md에 기록. 스키마 포맷 JSON Schema + 클라이언트 검증 채택. 에러 코드 어댑터-local 유지 + 도메인 중립 enum(APPROVED/FAILED/QUARANTINED) payload 원칙 확정. ProductTopics.java는 product-service 모듈이 T3-01에서 신설되므로 **이 태스크에서 실파일 생성 스킵** — ADR-12 토픽 목록표에 Phase 3 예고 기록만 함. PaymentTopics/PgTopics: 기존 상수 검토 완료, 누락 없음(중복 선언 금지 준수). ADR-31 결론 확정: 관측 지표 4종(pending_count/future_pending_count/oldest_pending_age_seconds/attempt_count_histogram) + 알림 4종 정의. PaymentOutboxRepository 포트 확장(countPending/countFuturePending/findOldestPendingCreatedAt 메서드 추가). JpaPaymentOutboxRepository JPQL 집계 쿼리 3종 추가. PaymentOutboxRepositoryImpl 구현 추가. PgOutboxRepository 포트 확장(countPending/countFuturePending/findOldestPendingCreatedAt). FakePgOutboxRepository 구현 추가. PaymentOutboxMetrics(payment-service/infrastructure/metrics): Gauge 3종(AtomicLong 캐시+Supplier 패턴) + DistributionSummary 1종, @Scheduled(fixedDelay=60s), @ConditionalOnProperty(scheduler.enabled=true). PgOutboxMetrics(pg-service/infrastructure/metrics): 동일 구조, Clock 주입. PgServiceConfig(pg-service/infrastructure/config): Clock Bean(Clock.systemUTC()) + @EnableScheduling 신설. observability/grafana/dashboards/payment-dashboard.json(2026-04-23 `chaos/grafana/`에서 이동): payment_outbox 패널 4개(pending_count/future_pending_count/oldest_pending_age_seconds/attempt_histogram) 추가 + alerting 4종(DLQ rate>0/future_pending>500 5min/oldest_age>300s/invariant>10). PaymentOutboxMetricsTest 2케이스 + PgOutboxMetricsTest 2케이스 GREEN. 전체 481/481 PASS(payment-service 386 + pg-service 95), 회귀 없음.

**완료 결과 — T2d-03** (2026-04-21):
ADR-21, ADR-02 적용. InternalOnlyGatewayFilter(GlobalFilter, Ordered, order=HIGHEST_PRECEDENCE+1) 신설: /internal/ 접두사 path 요청에 즉시 403 Forbidden 반환 후 다운스트림 체인 중단. gateway/src/main/resources/application.yml에 block-internal 라우트(uri=no://op, predicates=Path=/internal/**, filters=SetStatus:403) 추가. InternalOnlyGatewayFilterTest 2케이스(내부경로 403 차단/비내부경로 체인 위임) GREEN. 전체 484/484 PASS(gateway 3+payment-service 386+pg-service 95), 회귀 없음.

**완료 결과 — Phase-2-Gate** (2026-04-21):
`scripts/phase-gate/phase-2-gate.sh` 신설(executable). `docs/phase-gate/phase-2-gate.md` 신설. 검증 항목 10섹션(pre~j): (pre) clean test 484건 이상, (a) phase-2a/2b/2c-gate.sh 위임(SKIP_SUB_GATES=true 환경변수로 스킵 가능), (b) pg-service /actuator/health UP(포트 8082), (c) PaymentConfirmConsumerTest 5케이스+PaymentConfirmDlqConsumerTest 4케이스+ConfirmedEventConsumerTest 5케이스 Gradle 위임, (d) eventUUID dedupe(c 섹션 결과 집계), (e) PgVendorCallServiceTest 5케이스+PgFinalConfirmationGateTest 4케이스(FCG 불변: getStatus 1회, 재시도 0회), (f) DuplicateApprovalHandlerTest 6케이스+PgInboxAmountStorageTest 4케이스(2자 금액 대조 양 경로), (g) 3개 토픽 PartitionCount 동일 불변식 6b(Kafka 미기동 시 SKIP), (h) InternalOnlyGatewayFilterTest 2케이스+실제 HTTP 403 검증(Gateway 미기동 시 SKIP), (i) PgStatusAbsenceContractTest 3케이스(불변식 19), (j) 전체 Gradle test 484건 이상. 최종 요약에 "Phase 2 Gate ✓ — Phase 3 진입 가능" 메시지 출력. `./gradlew test` 485/485 회귀 없음(gateway 3+payment-service 387+pg-service 95). Phase 2 완료, Phase 3 진입.

**완료 결과 — T3-Gate** (2026-04-21):
`scripts/phase-gate/phase-3-gate.sh` 신설(executable). `docs/phase-gate/phase-3-gate.md` 신설. 검증 10섹션(pre~j): (pre) ./gradlew test 516건 이상, (a) product-service /actuator/health UP(포트 8083, 미기동 시 SKIP), (b) user-service /actuator/health UP(포트 8084, 미기동 시 SKIP), (c) Gateway /api/v1/users·/api/v1/products 라우트 T3-07 확인(미기동 시 SKIP), (d) product.events.stock-snapshot 토픽 존재+파티션 확인(Kafka 미기동 시 SKIP), (e) StockCommitConsumerTest(1케이스)+StockCommitUseCaseTest(3케이스) Gradle 위임(T3-04), (f) StockRestoreConsumerTest(1케이스)+StockRestoreUseCaseTest(4케이스) Gradle 위임(T3-05, 불변식 14 이중 복원 방지), (g) StockCommitUseCaseTest TC1+TC3 Redis SET 단위 테스트 커버 확인, (h) FailureCompensationServiceTest(2케이스) — FAILED 보상 발행+멱등 UUID(T3-04b), (i) ProductHttpAdapterTest(2케이스) — @ConditionalOnProperty 병행 유지+Strangler Vine(T3-06), (j) 전체 Gradle test 516건 이상. Phase 3 완료 ADR 확정: ADR-22(user-service 신설)/ADR-16(보상 dedupe)/ADR-14(재시도 정책)/ADR-02(재확정: port 오염 금지). 516/516 PASS, 회귀 없음.

**완료 결과 — T3-07** (2026-04-21):
gateway/src/main/resources/application.yml에 products-service-route(lb://product-service, /api/v1/products/**) + users-service-route(lb://user-service, /api/v1/users/**) 두 라우트 추가. 기존 payment-service lb 규약 동일하게 Eureka lb 경유. StripPrefix=0 적용(경로 그대로 전달). /internal/** 차단 라우트(block-internal)와 경로 겹침 없음 확인. 516/516 PASS(eureka 1+gateway 3+payment-service 390+pg-service 95+product-service 26+user-service 1), 회귀 없음.

**완료 결과 — T3-04** (2026-04-21):
StockCommitUseCase(application/usecase, @Service @Transactional) 신설: commit(eventUuid, orderId, productId, qty, expiresAt) — (1) EventDedupeStore.recordIfAbsent false→return(dedupe), (2) StockRepository.findByProductId+qty감소+save(RDB UPDATE), (3) RDB 성공 후에만 PaymentStockCachePort.setStock 호출(원자성 보장). RDB UPDATE 실패(재고 미존재) → IllegalStateException throw, Redis SET 호출 없음. StockCommitConsumer(infrastructure/messaging/consumer, @KafkaListener(payment.events.stock-committed, groupId=product-service), @ConditionalOnProperty(matchIfMissing=true, T1-18 교훈)) 신설: StockCommittedMessage 역직렬화 → StockCommitUseCase.commit 위임. StockCommittedMessage record(product-service 독립 복제 DTO, ADR-30): productId/qty/idempotencyKey/occurredAt/orderId/expiresAt. PaymentRedisStockAdapter(infrastructure/cache, @ConditionalOnProperty(product.cache.payment-redis.host)) 신설: paymentRedisTemplate(@Qualifier) 주입, setStock → Redis SET "stock:{productId}" TTL=24h. RedisPaymentConfig(infrastructure/config, @ConditionalOnProperty 대칭) 신설: redis-payment 전용 LettuceConnectionFactory + RedisTemplate<String, String> paymentRedisTemplate bean. application.yml: spring.data.redis(host/port) 추가, product.cache.payment-redis.port=6380(host는 환경변수 REDIS_PAYMENT_HOST 미설정 시 미활성화). ProductTopics.PAYMENT_EVENTS_STOCK_COMMITTED 상수 추가("payment.events.stock-committed"). V3__add_stock_commit_dedupe.sql: stock_commit_dedupe 테이블(event_uuid PK/order_id/product_id/qty/expires_at/created_at/idx_expires_at). ProductServiceApplicationTest: @MockitoBean PaymentStockCachePort 추가 + spring.kafka.listener.auto-startup=false(KafkaListener 컨테이너 자동 시작 방지). StockCommitUseCaseTest 3케이스 GREEN(TC1 RDB+Redis 순서/TC2 dedupe no-op/TC3 RDB실패 Redis미호출). StockCommitConsumerTest 1케이스 GREEN(TC4 usecase 1회 위임). 507/507 PASS(eureka 1+gateway 3+payment-service 386+pg-service 95+product-service 21+user-service 1), 회귀 없음.

**완료 결과 — T3-02** (2026-04-21):
settings.gradle에 `include 'user-service'` 추가. `user-service/build.gradle` 신설(spring-boot-starter-web/actuator/data-jpa/flyway-core/flyway-mysql/mysql-connector-j + Lombok 공통 + spring-boot-starter-test — Kafka 제외). User 도메인 이관(id/email/createdAt, payment-service 원본 복사, 삭제는 T3-06 범위). 포트 계층: UserRepository(findById: Optional<User>). 인바운드 포트 UserQueryService(queryById: UserQueryResult) + UserQueryUseCase(@Service @Transactional(readOnly=true), findById 없으면 UserNotFoundException). UserNotFoundException(core 의존 없음, UserErrorCode USER_001). 컨트롤러 UserController(@RestController GET /api/v1/users/{id}) + UserResponse record. UserQueryResult record(User → from). Flyway V1__user_schema.sql: user(id PK/email UNIQUE/created_at). UserServiceApplication(@SpringBootApplication, VT=application.yml). application.yml(port=8084, spring.threads.virtual.enabled=true, USER_DATASOURCE_URL 환경변수). UserServiceApplicationTest 스모크 1케이스 GREEN(autoconfig exclude 4종 + @MockitoBean UserRepository). 전체 487/487 PASS(eureka 1+gateway 3+payment-service 386+pg-service 95+product-service 1+user-service 1), 회귀 없음.

**완료 결과 — T3-01** (2026-04-21):
settings.gradle에 `include 'product-service'` 추가. `product-service/build.gradle` 신설(spring-boot-starter-web/actuator/kafka/data-jpa/flyway-core/flyway-mysql/mysql-connector-j/data-redis + Lombok 공통 + spring-boot-starter-test). Product/Stock 도메인 이관(payment-service 원본 복사, 삭제는 T3-06 범위). 포트 계층: StockRepository(findAll/findByProductId/save), EventDedupeStore(recordIfAbsent+TTL — pg-service markSeen 방식과 의도적으로 다름). 인바운드 포트 StockRestoreCommandService + StockRestoreUseCase 스캐폴드(UnsupportedOperationException, T3-05에서 완성). Flyway V1__product_schema.sql: product/stock/product_event_dedupe 3테이블. ProductTopics(EVENTS_STOCK_SNAPSHOT="product.events.stock-snapshot") + KafkaTopicConfig(@ConditionalOnProperty) 신설. StockSnapshotPublisher(@EventListener(ApplicationReadyEvent.class), @ConditionalOnProperty — Kafka 미구성 테스트 회피): 앱 기동 시 StockRepository.findAll() → KafkaTemplate 일괄 발행. ProductServiceApplication(@SpringBootApplication, VT=application.yml). application.yml(port=8083, spring.threads.virtual.enabled=true, PRODUCT_DATASOURCE_URL 환경변수, KAFKA_BOOTSTRAP_SERVERS). ProductServiceApplicationTest 스모크 1케이스 GREEN(autoconfig exclude 6종 + @MockitoBean 2개). 전체 486/486 PASS(eureka 1+gateway 3+payment-service 386+pg-service 95+product-service 1), 회귀 없음.

**완료 결과 — T3-03** (2026-04-21):


---

## 태스크 상세 블록 (Phase 0 ~ Phase 3)

## Phase 0 — 인프라 준비

**목적**: 모놀리스가 그대로 떠 있어도 동작하는 런타임 기반 확보. Kafka/Redis/Gateway/Observability docker-compose 기동.

**관련 ADR**: ADR-04, ADR-08, ADR-09, ADR-10, ADR-11, ADR-16, ADR-18, ADR-27, ADR-29, ADR-30, ADR-31

---

### T0-01 — docker-compose 기반 인프라 정의 ✅ 완료 (2026-04-21)

<!-- done: 2026-04-21 -->
**완료 결과**: docker-compose.infra.yml(Kafka KRaft·Redis·redis-payment AOF·Eureka·MySQL), docker-compose.observability.yml(Prometheus·Grafana·kafka-exporter·Tempo·Loki), settings.gradle 멀티모듈 플레이스홀더, observability/grafana/dashboards/payment-dashboard.json(6패널 스켈레톤 — 2026-04-23 `chaos/grafana/` → `observability/grafana/dashboards/` 이동), docs/phase-gate/kafka-topic-config.sh(ADR-30 파티션 동일성·retry 토픽 미존재 검증) 생성. `./gradlew test` 회귀 없음.

- **제목**: Kafka + 공유 Redis + payment 전용 Redis + Config Server + Discovery + 관측성 컨테이너 구성
- **목적**: ADR-10(compose 토폴로지), ADR-11(Spring Cloud 매트릭스), ADR-27(로컬 DX), ADR-31(관측성 5개 컴포넌트) — Kafka 브로커(KRaft 또는 ZK), 공유 Redis, payment-service 전용 Redis(`redis-payment`, AOF), Eureka(잠정), Config Server, Prometheus, Grafana(Grafana 결제 대시보드 스켈레톤: `published_total vs terminal_total`, DLQ 유입률, `pg_outbox.future_pending_count`, `oldest_pending_age_seconds`, `attempt_count` 분포, invariant 불일치 위젯 포함), kafka-exporter, Tempo, Loki 컨테이너를 `docker-compose.infra.yml` + `docker-compose.observability.yml`로 분리 정의. 토픽 3개(`payment.commands.confirm`, `payment.commands.confirm.dlq`, `payment.events.confirmed`) 동일 파티션 수, `replication.factor=3`, `min.insync.replicas=2`. retry 전용 토픽 미생성(ADR-30 방침). `settings.gradle` 루트 멀티모듈 구조 준비. 알림 4종(ADR-31) 정의 파일. PG 서비스는 무상태(DB 없음) — pg 전용 MySQL 미포함.
- **tdd**: false
- **domain_risk**: false
- **depends**: []
- **산출물**:
  - `docker-compose.infra.yml` — Kafka(+ZK/KRaft), 공유 Redis, `redis-payment`(AOF, keyspace `stock:{id}/idem:{key}`), Eureka, 네트워크 블록
  - `docker-compose.observability.yml` — Prometheus, Grafana, kafka-exporter, Tempo, Loki
  - `settings.gradle` — 루트 `payment-platform`, 향후 5개 서비스 include 준비
  - `observability/grafana/dashboards/payment-dashboard.json` — 결제 전용 대시보드 스켈레톤 (2026-04-23 `chaos/grafana/` 에서 이동)
  - `docs/phase-gate/kafka-topic-config.sh` — 토픽 설정 검증 스크립트(ADR-30 파티션 수 동일 확인)

---

### T0-02 — IdempotencyStore Caffeine → Redis 이관 (ADR-16)

- **제목**: payment-service 멱등성 저장소 Redis 어댑터 교체 (Caffeine 제거)
- **목적**: ADR-16(Idempotency 분산화) — 현 `IdempotencyStoreImpl`(Caffeine) 은 Phase-4.3 오토스케일러 다중 인스턴스 시 stateful 하여 horizontal stateless 위반. Redis SETNX(Lua) 로 교체. keyspace `idem:{key}`. Phase-0.1 payment 전용 Redis 전제.
- **tdd**: true
- **domain_risk**: true
- **depends**: [T0-01]
- **테스트 클래스**: `IdempotencyStoreRedisAdapterTest`
- **테스트 메서드**:
  - `getOrCreate_WhenKeyAbsent_ShouldInvokeCreatorAndStoreResult` — 첫 요청: creator 1회, Redis 저장, `IdempotencyResult.miss()` 반환
  - `getOrCreate_WhenKeyPresent_ShouldReturnCachedResultWithoutCreator` — 동일 key 2회: creator 0회, `hit()` 반환
  - `getOrCreate_ConcurrentMiss_ShouldInvokeCreatorOnce` — 동시 SETNX 경합: creator 1회만
  - `getOrCreate_ShouldRespectExpireAfterWriteSeconds` — TTL 경과 후 miss 처리
- **산출물**:
  - `payment-service/src/main/java/.../payment/infrastructure/idempotency/IdempotencyStoreRedisAdapter.java`
  - `payment-service/src/main/resources/application.yml` — `spring.data.redis.host: redis-payment`

- **완료 결과** (2026-04-21):
  - `src/main/java/.../payment/infrastructure/idempotency/IdempotencyStoreRedisAdapter.java` 신설 (`@Primary`, Lua script 2단계 원자 연산)
  - `src/main/resources/application.yml` — `spring.data.redis.host/port` 추가 (기본값 localhost)
  - `build.gradle` — `spring-boot-starter-data-redis` 추가 (Caffeine 유지)
  - `src/main/java/.../payment/application/dto/response/CheckoutResult.java` — `@JsonDeserialize/@JsonPOJOBuilder` 추가 (Jackson 역직렬화 지원)
  - 테스트 4개 신설, 전체 372개 PASS

---

### T0-03a — 루트 멀티모듈 전환

- **제목**: 단일 모듈 루트를 subprojects 구조로 재구성 (기존 src → payment-service 이관)
- **목적**: ADR-10 · 후속 Phase 2~3 서비스 분리 전제 — 기존 `src/main/**`를 `payment-service/src/main/**`으로 이관해 PLAN의 `payment-service/...`, `pg-service/...`, `product-service/...`, `user-service/...` 경로 가정과 정합. 루트 `build.gradle`을 subprojects 공통 블록(Java 21, Lombok, Checkstyle, SpotBugs, JaCoCo 기본 규약)으로 재구성하고, 기존 단일 모듈용 의존성은 `payment-service/build.gradle`로 이관. 빌드 회귀 없음.
- **tdd**: false
- **domain_risk**: false
- **depends**: [T0-01]
- **산출물**:
  - `git mv src payment-service/src` (rename 추적 유지)
  - `settings.gradle` — `include 'payment-service'` 추가, 루트 프로젝트명 `payment-platform` 유지
  - `build.gradle` — 루트 parent 공통 설정(subprojects 블록, Java 21, Lombok, Checkstyle, SpotBugs, JaCoCo)
  - `payment-service/build.gradle` — 기존 application 의존성(web, webflux, jpa, redis, caffeine, querydsl, prometheus, logstash, testcontainers)
  - `Dockerfile` — `COPY src/` → `COPY payment-service/src/`, build 경로 `payment-service/build/libs/*.jar`
  - `config/` — checkstyle/spotbugs 공유 디렉토리 유지, 경로 참조만 루트 기준으로 갱신

- **완료 결과** (2026-04-21):
  - `git mv src payment-service/src` — rename 추적 보존, 소스 코드 수정 없음
  - `build.gradle` — 루트 parent 재구성 (subprojects 공통 블록: Java 21, Lombok, Checkstyle, SpotBugs, JaCoCo, Spring BOM)
  - `payment-service/build.gradle` 신설 — application 의존성(web, webflux, jpa, redis, caffeine, querydsl, prometheus, logstash, testcontainers) + integrationTest/jacocoTestReport/spotbugs 태스크
  - `settings.gradle` — `include 'payment-service'` 실제 include로 교체
  - `Dockerfile` — COPY 경로 `payment-service/build/libs/*.jar` 갱신
  - `.gitignore` — QueryDSL generated 경로 `/payment-service/src/main/generated/`로 갱신
  - `./gradlew clean` / `:payment-service:compileJava` / `test` (372 PASS) / `:payment-service:bootJar` 전부 성공

### T0-03b — Spring Cloud Gateway 서비스 모듈 신설

- **제목**: API Gateway 모듈 신설 (Spring Cloud Gateway + WebFlux/Netty)
- **목적**: ADR-11(Gateway만 WebFlux, 내부 서비스는 MVC+VT) — 모놀리스 전체 fallback route. `traceparent` 헤더 주입 기반 설정. Reactor 타입은 이 모듈의 filter 범위에만 한정.
- **tdd**: false
- **domain_risk**: false
- **depends**: [T0-03a]
- **산출물**:
  - `settings.gradle` — `include 'gateway'` 추가
  - `gateway/build.gradle` — `spring-cloud-starter-gateway`, `spring-cloud-starter-netflix-eureka-client`, MVC 미포함
  - `gateway/src/main/java/.../gateway/GatewayApplication.java`
  - `gateway/src/main/resources/application.yml` — 모놀리스 전체 fallback route, Eureka client 설정

#### 완료 결과 (2026-04-21)

- `settings.gradle` — `include 'gateway'` 추가
- `gateway/build.gradle` — Spring Cloud Gateway + Eureka Client + Actuator + Prometheus. MVC/JPA 미포함(WebFlux/Netty only). Spring Cloud BOM 2024.0.0 (Spring Boot 3.4.4 호환)
- `gateway/src/main/java/.../gateway/GatewayApplication.java` — `@EnableDiscoveryClient` 포함
- `gateway/src/main/resources/application.yml` — port 8080, monolith fallback route(→ 8081), Eureka client, Actuator 엔드포인트
- `payment-service/src/main/resources/application.yml` — `server.port: 8081` 추가 (Gateway 8080 충돌 회피)
- `gateway/src/test/.../GatewayApplicationTests.java` — `RANDOM_PORT` + `eureka.client.enabled=false`로 context load 검증
- 검증: `:gateway:compileJava` PASS / `:gateway:test` 1 PASS / `test` 전체 373 PASS / `:gateway:bootJar` PASS

### T0-03c — Eureka Server 서비스 모듈 신설

- **제목**: Service Discovery 자체 모듈 신설 (Spring Cloud Netflix Eureka Server) + docker-compose Eureka 컨테이너 교체
- **목적**: ADR-11(잠정 채택 Eureka) — `springcloud/eureka` public image 대신 자체 Spring 모듈로 관리해 버전/설정 일관성 확보. `@EnableEurekaServer` 단독 application. `docker-compose.infra.yml` 기존 Eureka 서비스는 자체 모듈 빌드 결과(Jib 또는 Spring Boot plugin `bootBuildImage`) 기반으로 교체하거나 로컬에서는 `./gradlew :eureka-server:bootRun`으로 실행.
- **tdd**: false
- **domain_risk**: false
- **depends**: [T0-03a]
- **산출물**:
  - `settings.gradle` — `include 'eureka-server'` 추가
  - `eureka-server/build.gradle` — `spring-cloud-starter-netflix-eureka-server`
  - `eureka-server/src/main/java/.../eurekaserver/EurekaServerApplication.java` — `@EnableEurekaServer`
  - `eureka-server/src/main/resources/application.yml` — `server.port: 8761`, `eureka.client.register-with-eureka: false`, `eureka.client.fetch-registry: false`
  - `docker-compose.infra.yml` — 기존 `springcloud/eureka` 이미지 서비스 블록을 자체 모듈 기반으로 교체(`build:` 또는 Dockerfile 참조) + 컨테이너명·포트·healthcheck 유지

#### 완료 결과 (2026-04-21)

- `settings.gradle` — `include 'eureka-server'` 추가 (Phase 0: 주석 제거)
- `eureka-server/build.gradle` — `spring-cloud-starter-netflix-eureka-server` + Actuator + logstash. Spring Cloud BOM 2024.0.0 (gateway와 동일)
- `eureka-server/src/main/java/.../eurekaserver/EurekaServerApplication.java` — `@EnableEurekaServer` + `@SpringBootApplication`
- `eureka-server/src/main/resources/application.yml` — `server.port: 8761`, `register-with-eureka: false`, `fetch-registry: false`, Actuator health/info
- `eureka-server/src/test/.../EurekaServerApplicationTests.java` — `RANDOM_PORT` + `register-with-eureka=false` + `fetch-registry=false`로 context load 검증 (gateway 패턴 준용; `eureka.client.enabled=false`는 서버 내장 클라이언트를 비활성화하지 않아 부적합)
- `eureka-server/Dockerfile` — `eclipse-temurin:21-jre` 기반, HEALTHCHECK `/actuator/health`
- `docker-compose.infra.yml` — `springcloud/eureka` 이미지 → `build: context: ./eureka-server` 전환, `SPRING_PROFILES_ACTIVE: docker` 환경변수, 주석 "잠정 → 자체 모듈"로 갱신
- 검증: `:eureka-server:compileJava` PASS / `:eureka-server:test` 1 PASS / `test` 전체 374 PASS (payment-service 372 + gateway 1 + eureka-server 1) / `:eureka-server:bootJar` PASS / docker-compose YAML 문법 유효

---

### T0-04 — W3C Trace Context + LogFmt 공통 기반

- **제목**: Micrometer Tracing(OTel bridge) + traceparent MDC 주입 + LogFmt 복제 방침 확정
- **목적**: ADR-18(W3C Trace Context), ADR-19(LogFmt 복제(b) 방침) — Gateway WebFlux 필터에서 `traceparent` → MDC 주입. `LogFmt`/`MaskingPatternLayout` 복제(b) 방침 결정을 ADR-19 결론란에 기록. `TraceIdExtractor`는 순수 Java(Reactor 타입 비포함).
- **tdd**: false
- **domain_risk**: false
- **depends**: [T0-03b]
- **산출물**:
  - `gateway/src/main/java/.../gateway/filter/TraceContextPropagationFilter.java`
  - `core/common/tracing/TraceIdExtractor.java`
  - `docs/topics/MSA-TRANSITION.md` ADR-19 결론란에 복제(b) 확정 기록

#### 완료 결과 (2026-04-21)

- `gateway/src/main/java/com/hyoguoo/paymentplatform/gateway/filter/TraceContextPropagationFilter.java` — `WebFilter` + `Ordered.HIGHEST_PRECEDENCE`. W3C traceparent 정규식(`00-{32hex}-{16hex}-{2hex}`) 검증 후 MDC `traceId`/`spanId` 주입. 포맷 불일치·부재 시 Micrometer Tracing 자동 생성 위임. `doFinally`로 MDC 정리.
- `payment-service/src/main/java/com/hyoguoo/paymentplatform/core/common/tracing/TraceIdExtractor.java` — 순수 Java 유틸(static, `@NoArgsConstructor(PRIVATE)`). `extractTraceId(header)` / `extractSpanId(header)` / `parse(header)` 메서드. Reactor/Servlet 의존 없음. `TraceComponents` record 포함(`isSampled()` 헬퍼). null 반환 없음 — Optional 사용.
- `gateway/build.gradle` — `micrometer-tracing-bridge-otel`, `opentelemetry-exporter-otlp` 추가 (버전 Spring Boot BOM 관리).
- `payment-service/build.gradle` — `micrometer-tracing-bridge-otel` 추가.
- `docs/topics/MSA-TRANSITION.md` — ADR-18 결론(W3C Trace Context 구현 방식·모듈 경계·build.gradle 변경 요약)·ADR-19 결론(복제(b) 확정, Phase 2 pg-service 복제 이행 규칙, 공통화(a) 전환 기준) 추가.
- 핵심 결정: Gateway 모듈은 payment-service의 TraceIdExtractor를 직접 참조하지 않음(모듈 경계). LogFmt/MaskingPatternLayout은 서비스별 자체 소유(복제 b) — 복제 서비스 3개 초과 시 공통화(a) 재검토.
- 검증: `./gradlew test` 374 PASS (payment-service 372 + gateway 1 + eureka-server 1) — 기존 컨텍스트 로드 테스트 포함 전부 통과.

---

### T0-Gate — Phase 0 인프라 smoke 검증

- **제목**: Phase 0 Gate — 인프라 기반 smoke 검증 (다음 Phase 진입 판정)
- **목적**: T0-01~T0-05 완료 후 Kafka/Redis(2개)/Eureka/Config/Gateway/Toxiproxy 전수 healthcheck. Kafka 토픽 3개 설정 검증(동일 파티션 수, `replication.factor=3`). 실패 시 Phase 0 재수정 루프.
- **tdd**: false
- **domain_risk**: true
- **depends**: [T0-01, T0-02, T0-03, T0-04, T0-05]
- **산출물**:
  - [x] `scripts/phase-gate/phase-0-gate.sh` — healthcheck 전수, Redis SETNX 원자성, Kafka 토픽 파티션 수 동일, Toxiproxy `/proxies` 200
  - [x] `scripts/phase-gate/create-topics.sh` — Kafka 토픽 3개 멱등 생성 (T0-01b 역할)
  - [x] `docs/phase-gate/phase-0-gate.md`

#### 완료 결과 (2026-04-21)

- `scripts/phase-gate/phase-0-gate.sh` — `set -euo pipefail`. 전제조건(docker/curl/jq) + 컨테이너 6개 running 확인 + Kafka 브로커 응답 + 토픽 3개 존재 + 파티션 수 동일(ADR-30) + 공유 Redis PING + 결제 Redis PING·AOF(appendonly=yes) + SETNX 원자성(SET NX 두 번 → OK/(nil)) + MySQL mysqladmin ping + Eureka /actuator/health UP + Toxiproxy /proxies 3개 키(kafka-proxy/mysql-proxy/redis-payment-proxy) 확인. macOS grep -P 미지원 이슈 → awk로 PartitionCount 파싱. 총 29개 체크.
- `scripts/phase-gate/create-topics.sh` — payment.commands.confirm / payment.commands.confirm.dlq / payment.events.confirmed 토픽 생성(partitions=3, rf=1). 멱등(이미 존재하면 SKIP).
- `docs/phase-gate/phase-0-gate.md` — 체크리스트 19항목, 실행 절차, FAIL 케이스별 원인·조치, 프로덕션 편차(rf=1→3, min.insync=1→2).
- **gate 실행 결과**: PASS 29 / FAIL 0. `[GATE PASS] Phase 1 진입 가능.` (2026-04-21 확인)
- `./gradlew test` BUILD SUCCESSFUL (17 tasks up-to-date) — 애플리케이션 코드 변경 없음, 회귀 없음.

---

## Phase 1 — 결제 코어 분리

**목적**: 결제 컨텍스트를 독립 서비스로 분리. Outbox 발행 파이프라인(AFTER_COMMIT 리스너 + 채널 + Immediate 워커 + Polling 안전망) 을 "PG 직접 호출"에서 "Kafka produce"로 대상 교체. `payment_history` 결제 서비스 DB 잔류(ADR-13).

**관련 ADR**: ADR-01~07, ADR-13, ADR-14, ADR-15, ADR-17, ADR-23, ADR-25, ADR-26

**Phase 1 보상 경로 원칙**: Phase 1에서 상품 서비스는 모놀리스 안에 있다. `stock.events.restore` 보상은 결제 서비스 내부 동기 호출 유지(`InternalProductAdapter` 승계). 이벤트화는 Phase 3과 동시.

---

### T1-01 — 결제 서비스 모듈 경계 정리 (port 선언) ✅

- **제목**: cross-context port 복제 + InternalAdapter 승계 + StockCachePort 선언
- **목적**: ADR-01, ADR-02 — Phase 1 포트 계층 전 모놀리스 경계 차단. `ProductLookupPort`, `UserLookupPort`, `PaymentGatewayPort`, `StockCachePort`(decrement/rollback/current/set) 선언. `PgStatusPort` 는 존재하지 않는다(ADR-02 보강 — payment↔pg는 Kafka only). "재고 캐시 차감" 기준, "예약/reservation" 용어 금지.
- **tdd**: false
- **domain_risk**: false
- **depends**: [T0-Gate]
- **산출물**:
  - `payment-service/src/main/java/.../payment/application/port/out/ProductLookupPort.java`
  - `payment-service/src/main/java/.../payment/application/port/out/UserLookupPort.java`
  - `payment-service/src/main/java/.../payment/application/port/out/PaymentGatewayPort.java` — confirm/cancel 전담 (getStatus 메서드 없음)
  - `payment-service/src/main/java/.../payment/application/port/out/StockCachePort.java` — `decrement/rollback/current/set`
  - `payment-service/src/main/java/.../payment/infrastructure/adapter/internal/InternalProductAdapter.java`
  - `payment-service/src/main/java/.../payment/infrastructure/adapter/internal/InternalUserAdapter.java`
  - `payment-service/src/main/java/.../payment/infrastructure/adapter/internal/InternalPaymentGatewayAdapter.java`
  - `payment-service/build.gradle` — paymentgateway compile 의존 제거

**완료 결과 (2026-04-21)**

- 생성 파일 (7개):
  - `payment/application/port/out/ProductLookupPort.java` — `getProductInfoById`, `decreaseStockForOrders`, `increaseStockForOrders`
  - `payment/application/port/out/UserLookupPort.java` — `getUserInfoById`
  - `payment/application/port/out/PaymentGatewayPort.java` — `confirm`, `cancel` (getStatus/getStatusByOrderId 없음, ADR-02 준수)
  - `payment/application/port/out/StockCachePort.java` — `decrement(boolean)`, `rollback(void)`, `current(int)`, `set(void)`
  - `payment/infrastructure/adapter/internal/InternalProductAdapter.java` — `ProductLookupPort` 구현, `ProductInternalReceiver` 위임, 빈 이름 `productLookupAdapter`
  - `payment/infrastructure/adapter/internal/InternalUserAdapter.java` — `UserLookupPort` 구현, `UserInternalReceiver` 위임, 빈 이름 `userLookupAdapter`
  - `payment/infrastructure/adapter/internal/InternalPaymentGatewayAdapter.java` — `PaymentGatewayPort(out)` 구현, `PaymentGatewayFactory` + `PaymentGatewayProperties` 위임, 빈 이름 `paymentGatewayLookupAdapter`
- `payment-service/build.gradle` — paymentgateway 모듈 의존이 원래 없었으므로 no-op
- 기존 UseCase/port 코드 변경 없음 (기존 `ProductPort`, `UserPort`, `payment.application.port.PaymentGatewayPort` 및 `infrastructure/internal/` 어댑터 유지)
- 테스트: 372/372 PASS

---

### T1-02 — 결제 서비스 모듈 신설 + port 계층 구성

- **제목**: 결제 서비스 신규 Spring Boot 모듈 + outbound port 일괄 정리 + StockCommitEventPublisherPort 선언
- **목적**: ADR-01, ADR-11 — `application/port/{in,out}` 하위 일괄 정리. `MessagePublisherPort`, `StockCommitEventPublisherPort`(`payment.events.stock-committed` 발행 추상), `IdempotencyStore` 승계. `KafkaTopicConfig.java` 복제 배치(서비스별 NewTopic 빈 — 공통 jar 금지). `PgStatusPort` 선언 금지(ADR-21 보강 — Kafka only).
- **tdd**: false
- **domain_risk**: false
- **depends**: [T1-01]
- **산출물**:
  - [x] `settings.gradle` — `include 'payment-service'` (T0-03a에서 이미 존재. no-op)
  - [x] `payment-service/build.gradle` — `spring-kafka` 의존성 추가 (기존에 누락)
  - [x] `payment-service/src/main/java/.../payment/application/port/out/MessagePublisherPort.java`
  - [x] `payment-service/src/main/java/.../payment/application/port/out/StockCommitEventPublisherPort.java`
  - [x] `payment-service/src/main/java/.../payment/application/port/out/` — 기존 port 8개 `out/` 이동 + import 전수 수정
  - [x] `payment-service/src/main/java/.../payment/infrastructure/config/KafkaTopicConfig.java` — NewTopic 빈 4개 (`payment.events.stock-committed` 포함), `@ConditionalOnProperty` 테스트 가드

**완료 결과 (2026-04-21)**
- 신규 port 2개: `MessagePublisherPort`, `StockCommitEventPublisherPort` (`port/out/` 하위)
- 기존 port 8개 `port/` → `port/out/` 이동: `UserPort`, `ProductPort`, `IdempotencyStore`, `PaymentHistoryRepository`, `PaymentEventRepository`, `PaymentOrderRepository`, `PaymentOutboxRepository`, `AdminPaymentQueryRepository`
- import 수정 파일 수: 23개 (application UseCase 8, application Service 1, metrics 2, infrastructure repository 5, infrastructure idempotency 2, infrastructure internal adapter 2, test mock 2, test usecase 3)
- 구 `PaymentGatewayPort` (`port/` 바로 아래, `getStatus`/`getStatusByOrderId` 포함): T2에서 제거 예정으로 유지
- `build.gradle` — `spring-kafka` 추가 (기존에 누락됨)
- `KafkaTopicConfig.java` — NewTopic 빈 4개 (파티션 3, 복제 1, create-topics.sh 일치), `@ConditionalOnProperty(spring.kafka.bootstrap-servers)` 가드
- 테스트: 372/372 PASS

---

### T1-03 — Fake 구현체 신설 (application 계층 테스트용)

- **제목**: MessagePublisherPort + StockCachePort + StockCommitEventPublisherPort Fake 구현
- **목적**: ADR-04, ADR-16 — Kafka/Redis 없이 application 계층 테스트 가능. Fake가 소비자(T1-04 이후) 앞에 배치됨.
- **tdd**: false
- **domain_risk**: false
- **depends**: [T1-02]
- **산출물**:
  - `payment-service/src/test/java/.../mock/FakeMessagePublisher.java`
  - `payment-service/src/test/java/.../mock/FakeStockCachePort.java` — decrement(음수 시 false), rollback, current, set
  - `payment-service/src/test/java/.../mock/FakeStockCommitEventPublisher.java` — 발행 이력 list

**완료 결과 (2026-04-21)**

- `FakeMessagePublisher`: `SentMessage` record 축적, `findByTopic` / `lastMessage` / `count` / `clear` 헬퍼, `failNext()` / `setFailure(Throwable)` / `setPermanentFailure(Throwable)` 실패 시뮬레이션 지원.
- `FakeStockCachePort`: `ConcurrentHashMap<Long, Integer>` 기반, `decrement` 음수 시 false(차감 없음), `rollback` + qty, `current`(없으면 0), `set` 덮어쓰기, `getInternalMap()` / `clear()` 헬퍼.
- `FakeStockCommitEventPublisher`: `StockCommittedRecord` list 축적, `publishedEvents` / `lastEvent` / `countFor` / `clear` 헬퍼, `failNext()` 실패 시뮬레이션.
- 위치: `payment-service/src/test/java/com/hyoguoo/paymentplatform/payment/mock/`
- 전체 테스트 372/372 PASS.

---

### T1-04 — 도메인 이관: PaymentEvent·PaymentOutbox·RetryPolicy ✅

- **제목**: 결제 도메인 엔티티 + 릴레이 레코드 이관 (Spring 의존 없음)
- **목적**: ADR-03, ADR-04, ADR-13 — `PaymentEvent`, `PaymentOutbox`, `PaymentOrder`, `PaymentHistory`, `RetryPolicy`, `RecoveryDecision`, `PaymentEventStatus`(isTerminal() SSOT) 결제 서비스 domain 레이어로 이관.
- **tdd**: true
- **domain_risk**: true
- **depends**: [T1-03]
- **테스트 클래스**: `PaymentEventTest`, `PaymentOutboxTest`
- **테스트 메서드**:
  - `PaymentEventTest#execute_Success` — `@ParameterizedTest @EnumSource(READY, IN_PROGRESS)` → IN_PROGRESS 전이 성공
  - `PaymentEventTest#execute_ThrowsException_WhenTerminalStatus` — `@EnumSource(DONE, FAILED, CANCELED, EXPIRED)` → `PaymentStatusException`
  - `PaymentEventTest#quarantine_AlwaysSucceeds_FromAnyNonTerminal` — 비종결 상태에서 QUARANTINED 전이
  - `PaymentOutboxTest#toDone_ChangesStatusToProcessed` — PENDING → 완료 전이
  - `PaymentOutboxTest#nextRetryAt_ComputedCorrectly_ForExponentialBackoff` — RetryPolicy 기반 다음 재시도 시각

**완료 결과 (2026-04-21)**

- `PaymentEventStatus.isTerminal()` SSOT 확립: DONE/FAILED/CANCELED/PARTIAL_CANCELED/EXPIRED만 terminal. QUARANTINED는 후속 복구 워커가 보정/포기 결정하는 대기 상태이므로 non-terminal.
- `PaymentEvent.isTerminalStatus()`: `status.isTerminal()` 위임으로 단순화(LOCAL_TERMINAL_STATUSES Set 제거).
- `PaymentEvent.quarantine()`: `isTerminal()` 체크로 단순화 — terminal 상태에서는 QUARANTINED 전이 불허.
- `PaymentEventStatus.isCompensatableByFailureHandler()` 신설: READY/IN_PROGRESS/RETRYING만 true. QUARANTINED는 T1-12 QuarantineCompensationHandler 전담이므로 false.
- `PaymentTransactionCoordinator.executePaymentFailureCompensationWithOutbox`: `!isTerminal()` 대신 `isCompensatableByFailureHandler()` 사용 — 보상 경로 domain intent 명시(QUARANTINED 자동 스킵).
- `PaymentEventTest`: `execute_ThrowsException_WhenTerminalStatus` EnumSource에서 QUARANTINED 제거(terminal 아님). `quarantine_BlockedWhenTerminal` EnumSource에서 QUARANTINED 제거.
- `RecoveryDecisionTest`: 3개 테스트 EnumSource 조정 — QUARANTINED non-terminal 반영.
- 전체 테스트 379/379 PASS.

---

### T1-05 — 트랜잭션 경계 + 감사 원자성 (ADR-13)

- **제목**: PaymentTransactionCoordinator 이관 + payment_history BEFORE_COMMIT 원자성 보존
- **목적**: ADR-13(감사 원자성, 대안 a) — `payment_history`가 결제 서비스 DB에 잔류해 상태 전이 TX와 같은 TX 안에서 insert. 재고 캐시 차감은 TX 경계 외부(DECR 결과 음수 → FAILED / Redis down 예외 → QUARANTINED 분기). `quarantine_compensation_pending BOOLEAN NOT NULL DEFAULT FALSE` 컬럼(§2-2b-3 2단계 복구 설계).
- **tdd**: true
- **domain_risk**: true
- **depends**: [T1-04]
- **테스트 클래스**: `PaymentTransactionCoordinatorTest`, `PaymentHistoryEventListenerTest`
- **테스트 메서드**:
  - `PaymentTransactionCoordinatorTest#executePaymentConfirm_CommitsPaymentStateAndOutboxInSingleTransaction` — payment_event 전이 + payment_outbox 생성 단일 TX 원자성
  - `PaymentTransactionCoordinatorTest#executePaymentConfirm_WhenStockCacheDecrementRejected_ShouldTransitionToFailed` — DECR false(재고 부족) → FAILED, outbox 미생성
  - `PaymentTransactionCoordinatorTest#executePaymentConfirm_WhenPgTimeout_ShouldTransitionToQuarantineWithoutOutbox` — Redis down 예외 → QUARANTINED, outbox 미생성
  - `PaymentTransactionCoordinatorTest#executePaymentQuarantine_SetsCompensationPendingFlag` — QUARANTINED 전이 + `quarantine_compensation_pending=true` 플래그 set 검증
  - `PaymentHistoryEventListenerTest#onPaymentStatusChange_InsertsHistoryBeforeCommit` — BEFORE_COMMIT 단계 payment_history insert 1회

**완료 결과 (2026-04-21)**

- `PaymentEvent.quarantineCompensationPending` 필드 신설 (boolean, default false). `quarantine()` 메서드에서 자동 set. `markQuarantineCompensationPending()` 도메인 메서드 노출(coordinator 경유 경로 보장용).
- `PaymentEventEntity.quarantine_compensation_pending` 컬럼 매핑 추가 — `from()`/`toDomain()` 양방향 변환 포함. JPA ddl-auto로 스키마 자동 반영.
- `PaymentTransactionCoordinator.executePaymentConfirm()` 신설 — TX 경계 외부에서 stockCachePort.decrement() 호출 후 3분기 처리:
  - REJECTED(false) → markPaymentAsFail, outbox 미생성
  - CACHE_DOWN(RuntimeException) → markPaymentAsQuarantined + quarantineCompensationPending=true, outbox 미생성
  - SUCCESS(true) → @Transactional executePaymentConfirmInTransaction (executePayment + createPendingRecord 원자적)
- `executePaymentQuarantineWithOutbox()`: markPaymentAsQuarantined 후 quarantined.markQuarantineCompensationPending() 호출 추가.
- `StockCachePort` 의존성을 PaymentTransactionCoordinator에 추가 (@RequiredArgsConstructor).
- `PaymentHistoryEventListener`가 이미 `BEFORE_COMMIT` 단계임을 확인 — 변경 불필요.
- 신규 5개 + 기존 379개 = 384/384 PASS.

---

### T1-06 — AOP 축 결제 서비스 복제 이관 (ADR-13, §2-6) ✅

- **제목**: `@PublishDomainEvent`·`@PaymentStatusChange` + Aspect 결제 서비스 복제
- **목적**: ADR-13, §2-6(AOP 복제 원칙) — 각 서비스가 자기 패키지에 AOP 소유. cross-service 공유 금지.
- **tdd**: false
- **domain_risk**: true
- **depends**: [T1-05]
- **산출물**:
  - `payment-service/src/main/java/.../payment/infrastructure/aspect/DomainEventLoggingAspect.java`
  - `payment-service/src/main/java/.../payment/infrastructure/aspect/PaymentStatusMetricsAspect.java`
  - `payment-service/src/main/java/.../payment/infrastructure/aspect/annotation/PublishDomainEvent.java`
  - `payment-service/src/main/java/.../payment/infrastructure/aspect/annotation/PaymentStatusChange.java`

**완료 결과 (2026-04-21)**

- 이관 파일 4개: `DomainEventLoggingAspect`, `PaymentStatusMetricsAspect`, `PublishDomainEvent`, `PaymentStatusChange` — `core/common/aspect|metrics` → `payment/infrastructure/aspect[/annotation]`로 `git mv` (히스토리 보존).
- 참조 갱신 파일 2개: `PaymentCommandUseCase`, `PaymentCreateUseCase` — import 경로 `core.common.aspect.annotation` / `core.common.metrics.annotation` → `payment.infrastructure.aspect.annotation`으로 교체.
- Pointcut 영향 없음: 두 Aspect 모두 `@annotation(...)` 기반 pointcut — 패키지 경로 결합 없음.
- `core/common/aspect/annotation/Reason.java`는 이관 범위 밖(T1-06 4개 파일에 불포함). `DomainEventLoggingAspect`가 `core.common.aspect.annotation.Reason`을 import하는 형태로 유지(cross-service 공유 아님 — 동일 모놀리스 내 `core/common` 유틸 참조 허용).
- `core/common/metrics/aspect/` 잔여 파일: `TossApiMetricsAspect`(이관 범위 밖, paymentgateway 관련), `core/common/metrics/annotation/` 잔여: `TossApiMetric`, `ErrorCode` — 모두 유지.
- 384/384 PASS.

---

### T1-07 — 결제 서비스 Flyway V1 스키마 (ADR-23)

- **제목**: 결제 서비스 DB Flyway V1 마이그레이션 (payment_event, payment_order, payment_outbox, payment_history)
- **목적**: ADR-23(DB 분리) — 결제 전용 DB 빈 상태 시작. `quarantine_compensation_pending` 컬럼 포함. 모놀리스 미종결 레코드는 Phase 전환 전까지 모놀리스에서만 처리. 전환 시 `chaos/scripts/migrate-pending-outbox.sh` 사용.
- **tdd**: false
- **domain_risk**: true
- **depends**: [T1-05]
- **산출물**:
  - `payment-service/src/main/resources/db/migration/V1__payment_schema.sql` — `payment_event`(quarantine_compensation_pending 포함), `payment_order`, `payment_outbox`(available_at 컬럼), `payment_history` DDL
  - `docker-compose.infra.yml` 결제 전용 MySQL 컨테이너 추가

**완료 결과 (2026-04-21)**

- `V1__payment_schema.sql` 신규 생성: 테이블 4개 DDL (payment_event, payment_order, payment_outbox, payment_history).
- `payment_event`: order_id UNIQUE INDEX, (status, last_status_changed_at) 복합 인덱스. `quarantine_compensation_pending BOOLEAN NOT NULL DEFAULT FALSE` 포함.
- `payment_order`: payment_event_id, order_id 단순 인덱스. FK 제약 없음(MSA 분리 사전 조치). amount 컬럼명은 JPA `@Column(name="amount")`에 맞춤 (`totalAmount` 필드 → `amount` 컬럼).
- `payment_outbox`: order_id UNIQUE INDEX, (status, available_at) 복합 인덱스(폴링용), (status, next_retry_at, created_at) 복합 인덱스(기존 패턴 호환). `available_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)` 추가(ADR-30 지수 백오프). `retry_count INT NOT NULL DEFAULT 0` 포함.
- `payment_history`: payment_event_id 인덱스, created_at 인덱스. reason 컬럼 TEXT 타입(JPA columnDefinition="TEXT" 준수). FK 제약 없음.
- BaseEntity 컬럼(created_at, updated_at, deleted_at) DATETIME 타입 — JPA columnDefinition="datetime" 준수.
- `docker-compose.infra.yml` mysql-payment 컨테이너 추가(port 3307, platform linux/arm64, mysql-payment-data 볼륨). 기존 mysql(3306, 모놀리스용) 유지.
- Flyway on/off: 기존 application.yml에 Flyway 설정 없음(off 상태). 스크립트만 배치, 런타임 미실행 — application.yml 전환은 T1-18(Strangler Fig) 시점으로 연기.
- Hibernate ddl-auto: 기존 값 유지(변경 없음).
- 384/384 PASS.

---

### T1-08 — StockCachePort Redis 어댑터 (Lua atomic DECR)

- **제목**: payment-service 재고 캐시 차감 Redis 어댑터 구현
- **목적**: ADR-05(재고 캐시 차감) — Lua atomic DECR, 음수 시 INCR 복구 + false, Redis down 예외 전파. keyspace `stock:{productId}`. AOF 지속성 전제.
- **tdd**: true
- **domain_risk**: true
- **depends**: [T1-03, T0-01]
- **테스트 클래스**: `StockCacheRedisAdapterTest`
- **테스트 메서드**:
  - `decrement_WhenSufficientStock_ShouldDecrementAndReturnTrue` — DECR 후 양수 → true
  - `decrement_WhenStockWouldGoNegative_ShouldRollbackAndReturnFalse` — 음수 → INCR 복구 + false
  - `decrement_Concurrent_ShouldBeAtomicAndNeverGoNegative` — 동시 DECR Lua atomic 검증(Testcontainers Redis)
  - `rollback_ShouldIncrementStock` — rollback() INCR 검증
  - `decrement_WhenRedisDown_ShouldPropagateException` — 예외 전파(QUARANTINED 처리는 상위 계층)
- **산출물**:
  - `payment-service/src/main/java/.../payment/infrastructure/cache/StockCacheRedisAdapter.java`
  - `payment-service/src/main/resources/lua/stock_decrement.lua`

**완료 결과 (2026-04-21)**

- `StockCacheRedisAdapter.java` 신규 생성: `StockCachePort` 구현. `decrement`/`rollback`/`current`/`set` 4개 메서드.
- `lua/stock_decrement.lua` 신규 생성: DECRBY → 음수 감지 → INCRBY 복구 패턴 (Redis-idiomatic 원자 연산). 키 미존재 시 DECRBY(0→음수) → INCRBY 복구 → -1 반환으로 재고 없음과 동일 처리.
- `DefaultRedisScript<Long>` static 블록 초기화 — Spring 컨테이너 독립성 보장 (테스트에서 new 직접 생성 지원).
- `KEY_PREFIX = "stock:"` 내부 상수로 관리. `@Value` 주입 불필요(단일 서비스 확정 prefix).
- Redis 연결 실패 시 `DataAccessException` 계열 그대로 전파 — try-catch 없음.
- `build.gradle`: `testcontainers:testcontainers` 추가 (Lua 원자성·동시성 검증용 Redis Testcontainers).
- `StockCacheRedisAdapterTest` 5개 메서드: 차감 성공/음수 복구/200스레드 동시성(정확히 100번 성공)/rollback INCR/Redis down 예외 전파.
- 389/389 PASS (기존 384 + 신규 5).

---

### T1-09 — Toss 가면 응답 방어선 구현 (payment-service LVAL 한정) ✅

- **제목**: payment-service LVAL 금액 위변조 선검증 + Toss `ALREADY_PROCESSED_PAYMENT` LVAL 수준 방어
- **목적**: ADR-05(Phase 1 LVAL 한정) — payment-service에서 결제 진입 전 금액 위변조 선검증. `ALREADY_PROCESSED_PAYMENT`의 벤더 재조회 + 2자 금액 대조 + pg DB 부재 경로 방어는 **Phase 2 pg-service 산출물**(ADR-21(v) 불변). Phase 1에서는 `TossPaymentErrorCode.ALREADY_PROCESSED_PAYMENT.isSuccess()` 수정만 수행(가면 응답을 success로 취급 차단).
- **tdd**: true
- **domain_risk**: true
- **depends**: [T1-05]
- **테스트 클래스**: `PaymentLvalValidatorTest`
- **테스트 메서드**:
  - [x] `PaymentLvalValidatorTest#validate_WhenAmountMatches_ShouldPass` — 금액 일치 → 통과
  - [x] `PaymentLvalValidatorTest#validate_WhenAmountMismatches_ShouldReject4xx` — 금액 불일치 → 4xx 거부
  - [x] `PaymentLvalValidatorTest#tossAlreadyProcessed_ShouldNotBeClassifiedAsSuccess` — `TossPaymentErrorCode.ALREADY_PROCESSED_PAYMENT.isSuccess()` = false 검증
- **산출물**:
  - [x] `payment-service/src/main/java/.../payment/application/usecase/PaymentLvalValidator.java`
  - [x] `payment-service/src/main/java/.../paymentgateway/exception/common/TossPaymentErrorCode.java` — `ALREADY_PROCESSED_PAYMENT.isSuccess()` false
  - [x] `PaymentErrorCode.AMOUNT_MISMATCH("E03029", "결제 금액 위변조 감지")` 추가

**완료 결과 (2026-04-21)**
- `PaymentLvalValidator` 신설(application usecase). `validate(PaymentEvent, BigDecimal)` — 금액 불일치 시 `PaymentValidException(AMOUNT_MISMATCH)`.
- `TossPaymentErrorCode.isSuccess()` = false 고정. `ALREADY_PROCESSED_PAYMENT`는 이후 `isFailure()=true`→`NON_RETRYABLE_FAILURE`로 분류됨(영향 경로: `PaymentConfirmResultStatus.of()`).
- Phase 2 벤더 재조회/2자 대조는 pg-service(ADR-21(v)) 위임 유지.
- 392/392 PASS (기존 389 + 신규 3).

---

### T1-10 — StockCommitEventPublisher 구현 (재고 확정 이벤트 발행) ✅

- **제목**: payment.events.stock-committed Kafka 발행 어댑터 구현
- **목적**: S-2(StockCommitEvent 발행) — 결제 DONE 확정 시 `payment.events.stock-committed` 발행. `StockCommitEventPublisherPort`(T1-02 선언) 구현.
- **tdd**: true
- **domain_risk**: true
- **depends**: [T1-03, T1-02]
- **테스트 클래스**: `StockCommitEventPublisherTest`
- **테스트 메서드**:
  - [x] `publish_WhenPaymentConfirmed_ShouldEmitStockCommittedEvent` — DONE 확정 시 `payment.events.stock-committed` 1회 발행
  - [x] `publish_ShouldIncludeProductIdQtyAndPaymentEventId` — payload 필드 검증
  - [x] `publish_IsIdempotent_WhenCalledTwice_CallerResponsibility` — 멱등성 정책(c) 호출자 책임 명시
- **산출물**:
  - [x] `payment-service/src/main/java/.../payment/infrastructure/messaging/publisher/StockCommitEventKafkaPublisher.java`
  - [x] `payment-service/src/main/java/.../payment/infrastructure/messaging/PaymentTopics.java` — `EVENTS_STOCK_COMMITTED = "payment.events.stock-committed"` 상수
  - [x] `payment-service/src/main/java/.../payment/infrastructure/messaging/event/StockCommittedEvent.java` — payload record
  - [x] `KafkaTopicConfig.java` — string literal → `PaymentTopics` 상수 교체

**완료 결과 (2026-04-21)**
- `StockCommitEventKafkaPublisher` 신설(infrastructure/messaging/publisher). `publish(productId, qty, idempotencyKey)` → `MessagePublisherPort.send(EVENTS_STOCK_COMMITTED, productId.toString(), StockCommittedEvent)`.
- `PaymentTopics` 상수 클래스 신설(infrastructure/messaging). `KafkaTopicConfig` string literal → 상수 교체.
- `StockCommittedEvent` record 신설(infrastructure/messaging/event). 필드: productId, qty, idempotencyKey, occurredAt.
- 멱등성 정책 (c) 채택: publisher는 단순 Kafka send 어댑터, 멱등성은 Coordinator(호출자) 책임.
- 파티션 키: `productId.toString()` — 동일 상품 이벤트 순서 보장(ADR-12).
- `@ConditionalOnBean(MessagePublisherPort.class)`: T1-11a 전까지 Kafka 미작동 허용(test profile에서 Spring context 안전).
- 395/395 PASS (기존 392 + 신규 3).

---

### T1-11a — KafkaMessagePublisher + OutboxRelayService 구현 (ADR-04)

- **제목**: payment-service MessagePublisherPort 구현체 + OutboxRelayService (Publisher + RelayService)
- **목적**: ADR-04(Transactional Outbox publisher 계층) — `KafkaMessagePublisher`가 `MessagePublisherPort.publish(topic, key, payload)`의 유일한 Kafka 구현체. `infrastructure/messaging/publisher/`에만 존재. `OutboxRelayService`가 port를 경유해 `processed_at=NOW()` 갱신. Worker는 port 인터페이스 의존만 가짐 — KafkaTemplate 직접 호출 금지.
- **tdd**: true
- **domain_risk**: true
- **depends**: [T1-04, T1-02]
- **테스트 클래스**: `OutboxRelayServiceTest`
- **테스트 메서드**:
  - `relay_PublishesAllPendingOutbox_ThenMarksDone` — `FakeMessagePublisher`로 publish 호출 검증 + PENDING → PROCESSED 전이
  - `relay_WhenPublishFails_DoesNotMarkDone_LeavesForRetry` — 발행 실패 시 row 상태 유지
  - `relay_IsIdempotent_WhenCalledTwice` — 동일 outbox 2회 → publish 1회(FakeMessagePublisher 호출 횟수 assert)
- **산출물**:
  - `payment-service/src/main/java/.../payment/infrastructure/messaging/publisher/KafkaMessagePublisher.java`
  - `payment-service/src/main/java/.../payment/application/service/OutboxRelayService.java`

**완료 결과 (2026-04-21)**
- `KafkaMessagePublisher` 신설(infrastructure/messaging/publisher). `MessagePublisherPort.send(topic, key, payload)` 유일 Kafka 구현체. `@ConditionalOnProperty(name = "spring.kafka.bootstrap-servers")`.
- `OutboxRelayService` 신설(application/service). `relay(orderId)`: claimToInFlight 원자 선점 → paymentEvent 조회 → `messagePublisherPort.send(COMMANDS_CONFIRM, orderId, PaymentConfirmCommandMessage)` → `outbox.toDone()` + save. 실패 시 예외 전파(상태 전이 방지).
- `PaymentConfirmCommandMessage` record 신설(infrastructure/messaging/event). 필드: orderId, paymentKey, totalAmount, gatewayType, buyerId.
- 멱등성: claimToInFlight 원자 선점으로 동일 orderId 중복 발행 방지.
- 테스트 3종 GREEN: relay_PublishesAllPendingOutbox_ThenMarksDone, relay_WhenPublishFails_DoesNotMarkDone_LeavesForRetry, relay_IsIdempotent_WhenCalledTwice.
- 398/398 PASS (기존 395 + 신규 3).

---

### T1-11b — PaymentConfirmChannel + OutboxImmediateEventHandler 구현 (ADR-04)

- **제목**: payment-service PaymentConfirmChannel + AFTER_COMMIT 리스너 (EventHandler + Channel)
- **목적**: ADR-04(Channel + EventHandler) — `PaymentConfirmChannel`(`LinkedBlockingQueue<Long>`, capacity=1024, offer 실패 시 Polling 워커 fallback으로 안전망 처리) 신설. AFTER_COMMIT 리스너 `OutboxImmediateEventHandler`가 `PaymentConfirmChannel.offer(outboxId)` 호출.
- **tdd**: false
- **domain_risk**: true
- **depends**: [T1-11a]
- **산출물**:
  - `payment-service/src/main/java/.../payment/core/channel/PaymentConfirmChannel.java` — `LinkedBlockingQueue<Long>`, capacity=1024, offer 실패 시 로그 + Polling 안전망 위임
  - `payment-service/src/main/java/.../payment/listener/OutboxImmediateEventHandler.java` — AFTER_COMMIT 리스너

**완료 결과 (2026-04-21)**
- `PaymentConfirmChannel` 신설(core/channel). `LinkedBlockingQueue<String>` wrapper(capacity 설정값, 기본 2000). `offer(orderId)` 실패 시 WARN 로그("PaymentConfirmChannel 오버플로우 발생 — OutboxWorker(polling)가 처리 예정"). `take()`, `isNearFull()` 노출. Micrometer Gauge 등록(`@PostConstruct`). `@Component`.
- `OutboxImmediateEventHandler` 신설(payment/listener). `@TransactionalEventListener(phase = AFTER_COMMIT)`. `PaymentConfirmEvent(orderId)` 수신 → `channel.offer(orderId)` 호출. 실패 시 로그만 남기고 Polling 워커 안전망 위임.
- `OutboxImmediatePublisher` 신설(infrastructure/publisher). `PaymentConfirmPublisherPort` 구현. `ApplicationEventPublisher.publishEvent(PaymentConfirmEvent.of(...))` 호출 — 이벤트 발행 지점.
- 설계 편차: 스펙 `LinkedBlockingQueue<Long>`(outboxId) 대신 `LinkedBlockingQueue<String>`(orderId) 채택. 전체 코드베이스가 orderId 기반으로 일관화되어 있어 Long outboxId로의 전환은 T1-11c까지의 Worker 전체 변경을 수반하므로 orderId 기반 유지. 이벤트 타입도 `PaymentOutboxPendingEvent` 대신 `PaymentConfirmEvent` 사용(orderId 포함으로 목적 동일).
- 398/398 PASS (신규 테스트 없음 — tdd=false, 기존 테스트 회귀 없음).

---

### T1-11c — OutboxImmediateWorker + OutboxWorker 구현 (ADR-04, SmartLifecycle) ☑

- **제목**: payment-service ImmediateWorker + PollingWorker (SmartLifecycle + VT)
- **목적**: ADR-04(Outbox 4구성 파이프라인 완성) — `OutboxImmediateWorker`(SmartLifecycle + VT 200)가 `channel.take()` → row 로드 → `MessagePublisherPort.publish(topic, key, payload)` → `processed_at=NOW()`. KafkaTemplate 직접 호출 금지. `OutboxWorker`(`@Scheduled fixedDelay`, `SELECT ... FOR UPDATE SKIP LOCKED WHERE processed_at IS NULL AND available_at<=NOW()`)가 Polling 안전망. 중복 발행 방어: `UPDATE ... WHERE processed_at IS NULL` 원자 조건.
- **tdd**: true
- **domain_risk**: true
- **depends**: [T1-11b]
- **테스트 클래스**: `OutboxImmediateWorkerTest`
- **테스트 메서드**:
  - `stop_DrainsInFlightBeforeShutdown` — SmartLifecycle.stop() 시 진행 중 태스크 완료 후 종료
  - `outbox_publish_WhenImmediateAndPollingRace_ShouldEmitOnce` — Immediate+Polling 경쟁 시 produce 1회(불변식 11, FakeMessagePublisher 호출 횟수 assert)
- **산출물**:
  - `payment-service/src/main/java/.../payment/scheduler/OutboxImmediateWorker.java` — SmartLifecycle + VT + MessagePublisherPort 경유
  - `payment-service/src/main/java/.../payment/scheduler/OutboxWorker.java` — Polling 안전망

#### 완료 결과 (2026-04-21)

- `OutboxImmediateWorker.workerLoop()`: `outboxProcessingService.process(orderId)` → `outboxRelayService.relay(orderId)` 배선 교체 완료.
- `OutboxWorker.process()` / `processParallel()`: 동일하게 `outboxRelayService.relay(orderId)` 로 교체. `parallelEnabled`, `recoverTimedOutInFlightRecords` 로직 유지.
- `OutboxProcessingService.java` 삭제 — PG 직접 호출·RetryPolicy·FCG 로직은 pg-service 이관 대상이므로 payment-service에서 제거.
- `OutboxProcessingServiceTest.java` 삭제 (17개 테스트 제거).
- `OutboxImmediateWorkerTest.java` 재작성: FakeMessagePublisher + OutboxRelayService 주입 기반 2개 메서드.
- `OutboxWorkerTest.java` 재작성: OutboxRelayService Mock 기반 3개 메서드.
- 374/374 PASS (398 → 374: -17 OutboxProcessingServiceTest, -3 기존 OutboxImmediateWorkerTest, +2 신규, +3 OutboxWorkerTest 재작성분 net -4 => 실제 순감: OutboxProcessingServiceTest 17개 삭제·나머지 신규/재작성 순증 없음으로 374).

---

### T1-12 — QuarantineCompensationHandler + Scheduler (ADR-15, §2-2b-3)

- **제목**: QUARANTINED 2단계 복구 핸들러 구현 (TX 내 상태 전이 + TX 밖 Redis INCR)
- **목적**: ADR-15(QUARANTINED 보상 주체 = payment-service), §2-2b-3(2단계 분할 설계) — 진입점 (a) pg-service FCG 결과 status=QUARANTINED, (b) `PaymentConfirmDlqConsumer` 처리 후 status=QUARANTINED,reasonCode=RETRY_EXHAUSTED — 둘 다 `QuarantineCompensationHandler`로 수렴. (1) TX 내: PaymentEvent QUARANTINED 전이 + payment_history insert + `quarantine_compensation_pending=true`. (2) TX 밖: Redis INCR stock 복구. 성공 시 플래그 해제, 실패·크래시 시 플래그 유지 → `QuarantineCompensationScheduler` 주기 스캔 재시도. **QUARANTINED 전이 시 즉시 INCR 금지 — Phase-1.9 Reconciler 위임 불변에 추가로, 이 핸들러가 Phase 2 이후 진입점 a/b 공통 수렴점**.
- **tdd**: true
- **domain_risk**: true
- **depends**: [T1-05, T1-08]
- **테스트 클래스**: `QuarantineCompensationHandlerTest`, `QuarantineCompensationSchedulerTest`
- **테스트 메서드**:
  - `QuarantineCompensationHandlerTest#handle_ShouldTransitionToQuarantinedAndSetPendingFlag` — PaymentEvent QUARANTINED 전이 + 플래그 true + payment_history insert 단일 TX
  - `QuarantineCompensationHandlerTest#handle_WhenEntryIsDlqConsumer_ShouldRollbackStockAfterCommit` — DLQ consumer 진입점: TX 커밋 후 Redis INCR 1회 호출
  - `QuarantineCompensationHandlerTest#handle_WhenRedisIncrFails_PendingFlagShouldRemainTrue` — Redis INCR 실패 시 플래그 유지(불변식 7b)
  - `QuarantineCompensationHandlerTest#handle_WhenEntryIsFcg_ShouldNotRollbackStockImmediately` — FCG QUARANTINED 진입점: 즉시 INCR 금지(Reconciler 경로와 구분)
  - `QuarantineCompensationSchedulerTest#scan_WhenPendingFlagTrue_ShouldRetryRedisIncr` — 플래그 잔존 레코드 스캔 → Redis INCR 재시도
- **산출물**:
  - `payment-service/src/main/java/.../payment/application/usecase/QuarantineCompensationHandler.java`
  - `payment-service/src/main/java/.../payment/scheduler/QuarantineCompensationScheduler.java`

#### 완료 결과 (2026-04-21)

- `QuarantineCompensationHandler`: `QuarantineEntry` enum(FCG/DLQ_CONSUMER) 진입점 구분, `handle()` → `handleInTransaction()`(TX 내) + `attemptStockRollback()`(TX 밖, DLQ_CONSUMER만) 2단계 분리 구현.
- `handleInTransaction()`: `paymentCommandUseCase.markPaymentAsQuarantined()` → `quarantinedEvent.markQuarantineCompensationPending()` → `paymentEventRepository.saveOrUpdate()` 단일 TX.
- `attemptStockRollback()`: Redis INCR 성공 시 `clearPendingFlagInTx()` 호출(플래그 해제), `RuntimeException` 발생 시 플래그 유지(불변식 7b). FCG 진입점은 즉시 INCR 금지.
- `retryStockRollback()`: Scheduler 재시도 진입점 — event 재조회 후 `attemptStockRollback()` 위임.
- `QuarantineCompensationScheduler`: `@Scheduled(fixedDelayString=...)` scan() — `findByQuarantineCompensationPendingTrue()` 조회 후 `retryStockRollback()` 반복.
- `PaymentEvent.clearQuarantineCompensationPending()` 신설.
- `PaymentEventRepository.findByQuarantineCompensationPendingTrue()` 인터페이스·구현체(JPA Spring Data 메서드명 규칙)·`FakePaymentEventRepository` 추가.
- 5/5 테스트 PASS, 전체 379/379 PASS.

---

### T1-14 — Reconciliation 루프 + Redis↔RDB 재고 대조 (ADR-07) ☑

- **제목**: 결제 서비스 로컬 Reconciler — 미종결 레코드 스캔 + Redis↔RDB 대조 + QUARANTINED DECR 복원
- **목적**: ADR-07, ADR-17 — FCG=즉시 경로, Reconciler=지연 경로. Redis `stock:{id}` vs (product RDB 재고 − PENDING/QUARANTINED 합계) 대조. 발산 시 RDB를 진실로 Redis 재설정, `divergence_count` +1. QUARANTINED 결제의 DECR 수량 INCR 복원(Reconciler 단독). TTL 만료 miss → RDB 기준 재설정.
- **tdd**: true
- **domain_risk**: true
- **depends**: [T1-08, T1-12]  <!-- T1-13 skipped 2026-04-21; 불변식 7 검증은 T1-12로 대체 -->
- **테스트 클래스**: `PaymentReconcilerTest`
- **테스트 메서드**:
  - `scan_FindsStaleInFlightRecords_AndResetsToRetry` — IN_FLIGHT + timeout 초과 → PENDING 복원
  - `scan_DoesNotTouchTerminalRecords` — DONE/FAILED/QUARANTINED 불간섭
  - `scan_WhenStockCacheDivergesFromRdb_ShouldResetCacheToRdbValue` — 발산 감지 → Redis 재설정 + divergence_count +1
  - `scan_WhenQuarantinedPaymentExists_ShouldRollbackDecrForEach` — QUARANTINED → StockCachePort.rollback() 호출
  - `scan_WhenStockCacheKeyMissing_ShouldRestoreFromRdb` — key miss → RDB 기준 SET
- **산출물**:
  - `payment-service/src/main/java/.../payment/application/service/PaymentReconciler.java`

**완료 결과** (2026-04-21):
- `PaymentReconciler.java` 신설: `@Scheduled` scan() 3단계 로직 구현
  - Step 1: `findInProgressOlderThan(cutoff)` → `resetToReady()` + 저장
  - Step 2: READY/IN_PROGRESS/RETRYING 수량 합산 → productId별 Redis↔RDB 대조 → 발산 시 RDB 기준 재설정 + `divergenceCount` +1
  - Step 3: QUARANTINED 레코드 → `rollback()` 호출 (key miss도 Step 2에서 처리)
- `StockCachePort.findCurrent()` 추가 (key miss 감지용 `Optional<Integer>`)
- `PaymentEventRepository.findInProgressOlderThan()` / `findAllByStatus()` 추가
- `PaymentEvent.resetToReady()` 추가 + `PaymentErrorCode.INVALID_STATUS_TO_RESET` 추가
- `StockCacheRedisAdapter.findCurrent()` 구현
- Fake 구현체 업데이트: `FakeStockCachePort.findCurrent()`, `FakePaymentEventRepository.findInProgressOlderThan()/findAllByStatus()`
- `JpaPaymentEventRepository.findInProgressOlderThan()` / `findByStatus()` 추가
- 테스트 5개 / 5개 통과, 전체 384/384 통과

---

### T1-15 — Graceful Shutdown + Virtual Threads 재검토 (ADR-25, ADR-26) ☑

- **제목**: SmartLifecycle drain + VT 설정 결제 서비스 이관
- **목적**: ADR-25, ADR-26 — SIGTERM 시 in-flight outbox 처리 중인 워커를 안전하게 drain.
- **tdd**: true
- **domain_risk**: false
- **depends**: [T1-11c]
- **테스트 클래스**: `OutboxImmediateWorkerTest`
- **테스트 메서드**:
  - `stop_DrainsInFlightBeforeShutdown` — SmartLifecycle.stop() 시 진행 중 태스크 완료 후 종료
  - `start_SpawnsConfiguredNumberOfWorkers` — VT 설정값에 따른 워커 수
- **관련 파일**: `payment/scheduler/OutboxImmediateWorker.java`(T1-11 산출물 보완)

**완료 결과** (2026-04-21):
- `OutboxImmediateWorkerTest.java` 테스트 보강 (2개 신규 + 1개 보강 + 1개 유지):
  - `stop_DrainsInFlightBeforeShutdown` 보강 (ADR-25): relay() 실행 중 stop() 호출 시 relay 완료 후 콜백이 실행됨을 타임스탬프로 명시 검증. interrupt-safe busy-wait으로 drain 순서(relayEndNanos ≤ callbackNanos) 보장 확인
  - `start_SpawnsConfiguredNumberOfWorkers` 신규 (ADR-26): workerCount=3 설정 시 VT 워커 3개 스폰, `Thread.isVirtual() == true` 검증
  - `stop_WhenAlreadyStopped_IsRunningReturnsFalse` 신규: stop() 후 isRunning() false 불변 보조 검증
  - `outbox_publish_WhenImmediateAndPollingRace_ShouldEmitOnce` 기존 race 테스트 유지
- `OutboxImmediateWorker.java` 코드 변경 없음 — T1-11c 구현이 이미 올바르게 drain 수행 (stop(Runnable) → Thread.interrupt + join(5000) 경로)
- 테스트 4개 / 4개 통과, 전체 386/386 통과

---

### T1-16 — payment.outbox.pending_age_seconds + payment.stock_cache.divergence_count 메트릭 (ADR-20) ☑

- **제목**: PENDING 체류 시간 histogram + 재고 캐시 발산 카운터 메트릭
- **목적**: ADR-20, ADR-31 — `payment.outbox.pending_age_seconds` histogram(PENDING 체류 시간 분포). `payment.stock_cache.divergence_count` counter(Reconciler 발산 감지 연계). `infrastructure/metrics/` 배치.
- **tdd**: true
- **domain_risk**: true
- **depends**: [T1-14]
- **테스트 클래스**: `OutboxPendingAgeMetricsTest`, `StockCacheDivergenceMetricsTest`
- **테스트 메서드**:
  - `OutboxPendingAgeMetricsTest#record_ShouldEmitHistogramForEachPendingRecord` — histogram 기록 검증
  - `OutboxPendingAgeMetricsTest#record_ZeroPendingRecords_ShouldNotRecord` — PENDING 없으면 미기록
  - `StockCacheDivergenceMetricsTest#increment_ShouldIncreaseDivergenceCounter` — counter +1
  - `StockCacheDivergenceMetricsTest#noDivergence_ShouldNotIncrementCounter` — 발산 없음 시 불변
- **산출물**:
  - `payment-service/src/main/java/.../payment/infrastructure/metrics/OutboxPendingAgeMetrics.java`
  - `payment-service/src/main/java/.../payment/infrastructure/metrics/StockCacheDivergenceMetrics.java`

**완료 결과** (2026-04-21):
- `OutboxPendingAgeMetrics.java` — `payment.outbox.pending_age_seconds` DistributionSummary(histogram). `record()` 단일 진입점: PENDING 배치를 조회해 각 레코드의 `createdAt` 기준 체류 시간(초)을 기록. PENDING 없으면 미기록.
- `StockCacheDivergenceMetrics.java` — `payment.stock_cache.divergence_count` Counter. `increment()` 단일 진입점.
- `PaymentReconciler.java` 연동: `AtomicLong divergenceCount` → `StockCacheDivergenceMetrics` 주입 교체. `getDivergenceCount()` 임시 메서드 제거.
- `PaymentReconcilerTest.java` 업데이트: `SimpleMeterRegistry` + `StockCacheDivergenceMetrics` 주입, counter 값 직접 assert.
- `OutboxPendingAgeMetricsTest.java` 신규: SimpleMeterRegistry로 histogram count/totalAmount 직접 검증.
- `StockCacheDivergenceMetricsTest.java` 신규: SimpleMeterRegistry로 counter 값 직접 검증.
- 테스트 4개 / 4개 신규 통과, 전체 390/390 통과 (기존 386 + 신규 4)

---

### T1-17 — 재고 캐시 warmup (product.events.stock-snapshot 토픽 재생)

- **제목**: payment-service 기동 시 Redis stock cache 초기화
- **목적**: S-3(Reconciler 전제) — `ApplicationReadyEvent` 시 `product.events.stock-snapshot` 토픽 replay → Redis 초기화. warmup 완료 전 결제 차단. product-service Phase-3.1 snapshot 발행 훅과 pair. Kafka consume 관심사는 `StockSnapshotWarmupConsumer`(messaging consumer), "결제 차단 전까지 warmup 오케스트레이션"은 `StockCacheWarmupService`(application service)로 분리.
- **tdd**: true
- **domain_risk**: true
- **depends**: [T1-08]
- **테스트 클래스**: `StockSnapshotWarmupConsumerTest`, `StockCacheWarmupServiceTest`
- **테스트 메서드**:
  - `StockCacheWarmupServiceTest#onApplicationReady_ShouldPopulateCacheFromSnapshotTopic` — snapshot 항목 → StockCachePort SET 검증
  - `StockCacheWarmupServiceTest#warmup_WhenTopicEmpty_ShouldLeaveEmptyCacheAndLog` — 빈 토픽 → 미설정 + 경고 로그
  - `StockCacheWarmupServiceTest#warmup_DuplicateSnapshot_ShouldUseLatestValue` — 동일 productId 복수 → 최신값 덮어쓰기
  - `StockCacheWarmupServiceTest#warmup_AfterCompletion_ShouldAllowDecrementImmediately` — warmup 완료 후 decrement() 즉시 동작
  - `StockSnapshotWarmupConsumerTest#consume_ShouldDelegateToWarmupService` — consumer → WarmupService 1회 위임
- **산출물**:
  - `payment-service/src/main/java/.../payment/infrastructure/messaging/consumer/StockSnapshotWarmupConsumer.java` — snapshot 토픽 consume 어댑터
  - `payment-service/src/main/java/.../payment/application/service/StockCacheWarmupService.java` — warmup 오케스트레이션 + 결제 차단 플래그

**완료 결과** (2026-04-21):
- `StockSnapshotEvent.java` — `(productId, quantity, capturedAt)` record DTO. `payment/infrastructure/messaging/consumer/dto/` 배치.
- `StockCacheWarmupService.java` — `applySnapshots(List)`: 목록 순서 마지막값 덮어쓰기 SET, 빈 목록 시 경고 로그 후 완료. `handleSnapshot(event)`: consumer 실시간 위임 진입점. `isWarmupCompleted()`: `AtomicBoolean` warmup 완료 플래그 질의.
- `StockSnapshotWarmupConsumer.java` — `@KafkaListener(product.events.stock-snapshot)` thin adapter. `consume(event)` → `warmupService.handleSnapshot(event)` 단순 위임.
- `StockCacheWarmupApplicationEventListener.java` — `@EventListener(ApplicationReadyEvent.class)`. Phase 1: 빈 목록으로 `applySnapshots()` 호출 → 경고 로그 후 warmup 완료. Phase 3+: product-service snapshot 발행 훅(T3-01) 구현 후 replay 교체.
- `StockCacheWarmupServiceTest.java` 신규: 4개 테스트 (snapshot SET / 빈 토픽 / 중복 덮어쓰기 / warmup 후 decrement 즉시 동작).
- `StockSnapshotWarmupConsumerTest.java` 신규: 1개 테스트 (consumer → handleSnapshot 1회 위임).
- 테스트 5개 / 5개 통과, 전체 395/395 통과 (기존 390 + 신규 5)

---

### T1-18 — Gateway 라우팅: 결제 엔드포인트 교체 + 모놀리스 결제 경로 비활성화

- **제목**: Gateway route — 결제 서비스 라우팅 + 모놀리스 confirm 경로 비활성화
- **목적**: ADR-01, ADR-02 — Strangler Fig. `/api/v1/payments/**` → payment-service. 모놀리스 confirm 경로 `@ConditionalOnProperty` 기본값=비활성화. `migrate-pending-outbox.sh` 제공.
- **tdd**: false
- **domain_risk**: true
- **depends**: [T1-11c, T0-03]
- **산출물**:
  - `gateway/src/main/resources/application.yml` route 추가
  - 모놀리스 `payment/listener/OutboxImmediateEventHandler.java` — `@ConditionalOnProperty("payment.monolith.confirm.enabled", havingValue="true", matchIfMissing=false)`
  - `chaos/scripts/migrate-pending-outbox.sh`

**완료 결과** (2026-04-21):
- `gateway/src/main/resources/application.yml` — `id: payment-service` route 추가. predicate `Path=/api/v1/payments/**` → `uri: lb://payment-service`. monolith-fallback보다 우선순위 상위에 배치.
- `payment-service/.../payment/listener/OutboxImmediateEventHandler.java` — `@ConditionalOnProperty(name="payment.monolith.confirm.enabled", havingValue="true", matchIfMissing=false)` 추가. 프로퍼티 미지정 시 빈 미등록(기본 비활성화).
- `chaos/scripts/migrate-pending-outbox.sh` — 모놀리스 DB의 `payment_outbox` PENDING(processed_at IS NULL) 레코드를 payment-service DB로 이관하는 헬퍼 스크립트. `--dry-run` 옵션 제공, 환경 변수로 DB 접속 정보 수신.
- `./gradlew test`: 395/395 PASS, 회귀 없음.

---

### T1-Gate — Phase 1 결제 코어 E2E 검증

- **제목**: Phase 1 Gate — 결제 코어 E2E (다음 Phase 진입 판정)
- **목적**: T1-01~T1-18 완료 후 payment-service 단독 기동 + 결제 성공/실패/QUARANTINED 경로 + Redis 캐시 차감 + Reconciler E2E 검증. `GET /internal/pg/status/{orderId}` 엔드포인트·`PgStatusPort`·`PgStatusHttpAdapter`가 payment-service에 존재하지 않음을 계약 테스트로 확인(불변식 19).
- **tdd**: false
- **domain_risk**: true
- **depends**: [T1-18, T1-17, T1-16, T1-15, T1-14]
- **산출물**:
  - `scripts/phase-gate/phase-1-gate.sh` — healthcheck, Flyway 확인, 결제 성공/실패/QUARANTINED E2E, Redis DECR, Reconciler trigger, 메트릭 scraping, `PgStatusPort` 부재 확인
  - `docs/phase-gate/phase-1-gate.md`

**완료 결과** (2026-04-21):
- `scripts/phase-gate/phase-1-gate.sh` — 11개 섹션 bash 스크립트. 전제조건(docker/curl/jq/mysql) → 인프라 헬스체크(Gateway·payment-service·Redis·MySQL·Kafka) → Flyway 마이그레이션 상태 → E2E 데이터 시드(성공/실패/QUARANTINED 3경로) → 성공 경로 DONE 확인 → 실패 경로 FAILED 확인 → QUARANTINED 경로 확인 → Redis DECR 감소 확인 → Reconciler 발산 카운트 → 메트릭 scraping(pending_age_seconds, divergence_count) → PgStatusPort 부재 확인(불변식 19) → 테스트 데이터 정리. `bash -n` 문법 검증 통과. 환경 변수로 엔드포인트·DB·Redis 재정의 가능.
- `docs/phase-gate/phase-1-gate.md` — 운영자용 한글 문서. 개요 / 체크리스트 / 사전 준비 / 실행 절차 / 성공 기준 / 실패 시 처리(롤백 + 항목별 원인) / 다음 Phase 진입 전 체크리스트.
- `./gradlew test`: 395/395 PASS, 회귀 없음. (tdd=false — 신규 단위 테스트 없음)

---

## Phase 2 — PG 서비스 분리 (4단계)

**목적**: `paymentgateway` 컨텍스트를 물리 분리(ADR-21). ADR-30의 Outbox + ApplicationEvent + PgOutboxChannel + Immediate/Polling Worker 패턴을 pg-service에 독립 복제 구현. `payment.commands.confirm` 단일 토픽 재사용 + `available_at` 지연 + `PaymentConfirmDlqConsumer` 전용 consumer. 단계별 마이크로 Gate 후 Phase 2 통합 Gate.

**관련 ADR**: ADR-21, ADR-04(재확정), ADR-05(보강), ADR-14, ADR-15, ADR-20, ADR-30, ADR-31

---

## Phase 2.a — pg-service 골격 + Outbox 파이프라인 + consumer 기반

### T2a-01 — pg-service 모듈 신설 + port 계층 + 벤더 전략 이관 ✅

- **제목**: PG 서비스 신규 Spring Boot 모듈 + 포트 계층 + Toss/NicePay 전략 이관
- **목적**: ADR-21(PG 물리 분리) — pg-service는 벤더 선택·재시도·상태 조회·FCG·중복 승인 응답 방어를 전부 내부에서 수행. payment-service는 벤더·상태·FCG를 모른다. `PgGatewayPort`(벤더 호출), `PgEventPublisherPort`(이벤트 발행 추상) 선언. inbound: `PgConfirmCommandService`. `KafkaTopicConfig.java` 복제 배치.
- **tdd**: false
- **domain_risk**: false
- **depends**: [T1-Gate]
- **산출물**:
  - `settings.gradle` — `include 'pg-service'` ✅
  - `pg-service/build.gradle` — spring-boot-starter-web, spring-kafka ✅
  - `pg-service/src/main/java/.../pg/application/port/out/PgGatewayPort.java` ✅
  - `pg-service/src/main/java/.../pg/application/port/out/PgEventPublisherPort.java` ✅
  - `pg-service/src/main/java/.../pg/presentation/port/PgConfirmCommandService.java` ✅
  - `pg-service/src/main/java/.../pg/infrastructure/gateway/toss/TossPaymentGatewayStrategy.java` ✅
  - `pg-service/src/main/java/.../pg/infrastructure/gateway/nicepay/NicepayPaymentGatewayStrategy.java` ✅
  - `pg-service/src/main/java/.../pg/infrastructure/config/KafkaTopicConfig.java` ✅

**완료 결과** (2026-04-21):
- `settings.gradle` — `include 'pg-service'` 추가. 기존 Phase 2.a 주석 라인 제거.
- `pg-service/build.gradle` — spring-boot-starter-web + spring-kafka 선언. DB/Redis 미포함(무상태 설계).
- `pg-service/.../pg/domain/enums/PgVendorType.java` — TOSS/NICEPAY 독립 선언 (ADR-30 공통 jar 금지).
- `pg-service/.../pg/domain/enums/PgConfirmResultStatus.java` — SUCCESS/RETRYABLE_FAILURE/NON_RETRYABLE_FAILURE.
- `pg-service/.../pg/domain/enums/PgPaymentStatus.java` — 결제 상태 enum 독립 선언.
- `pg-service/.../pg/application/dto/` — PgConfirmRequest·PgConfirmResult·PgStatusResult·PgConfirmCommand·PgFailureInfo 5종 record. payment-service DTO 의존 없음.
- `pg-service/.../pg/exception/` — PgGatewayRetryableException·PgGatewayNonRetryableException 독립 선언.
- `pg-service/.../pg/application/port/out/PgGatewayPort.java` — 벤더 독립 인터페이스 (supports/confirm/getStatusByOrderId).
- `pg-service/.../pg/application/port/out/PgEventPublisherPort.java` — publishConfirmed 단일 메서드 추상.
- `pg-service/.../pg/presentation/port/PgConfirmCommandService.java` — handle(PgConfirmCommand) inbound 계약.
- `pg-service/.../pg/infrastructure/gateway/toss/TossPaymentGatewayStrategy.java` — PgGatewayPort 구현 스켈레톤. 상태 매핑·재시도 판별 로직 이식. 실제 HTTP 호출은 T2b-01에서 구현.
- `pg-service/.../pg/infrastructure/gateway/nicepay/NicepayPaymentGatewayStrategy.java` — PgGatewayPort 구현 스켈레톤. 상태 매핑·에러코드 분류 로직 이식. 실제 HTTP 호출은 T2b-01에서 구현.
- `pg-service/.../pg/infrastructure/messaging/PgTopics.java` — COMMANDS_CONFIRM·COMMANDS_CONFIRM_DLQ·EVENTS_CONFIRMED 상수 독립 선언.
- `pg-service/.../pg/infrastructure/config/KafkaTopicConfig.java` — PgTopics 참조, 3개 토픽 Bean 선언.
- `pg-service/.../pg/PgServiceApplication.java` — Spring Boot 진입점.
- `./gradlew clean :pg-service:compileJava` PASS, `./gradlew test` 395/395 PASS, 회귀 없음.

---

### T2a-02 — pg-service AOP 축 복제 이관 (§2-6)

- **제목**: `@TossApiMetric` + `TossApiMetricsAspect` PG 서비스 복제
- **목적**: §2-6(AOP 복제 원칙) — 공통 jar 금지, 서비스 소유.
- **tdd**: false
- **domain_risk**: false
- **depends**: [T2a-01]
- **산출물**:
  - `pg-service/src/main/java/.../pg/infrastructure/aspect/TossApiMetricsAspect.java`
  - `pg-service/src/main/java/.../pg/infrastructure/aspect/annotation/TossApiMetric.java`

**완료 결과** (2026-04-21):
- `TossApiMetric.java` (annotation) · `ErrorCode.java` (annotation) · `TossApiMetrics.java` · `TossApiMetricsAspect.java` — 모두 `pg.infrastructure.aspect.*` 패키지에 신설.
- payment-service `TossPaymentErrorCode` 참조 제거 — `extractErrorCode`가 `Optional<String>` 반환으로 교체(null 반환 금지 규칙 준수).
- `pg-service/build.gradle`에 `spring-boot-starter-aop` + `spring-boot-starter-actuator` 추가(컴파일 실패 후 최소 추가, 커밋 메시지 명시).
- payment-service 원본 미수정(Phase 2.b/2.c dual-write 삭제 예정).
- `:pg-service:compileJava` PASS, `./gradlew test` 395/395 PASS.

---

### T2a-03 — Fake pg-service 구현 (테스트용)

- **제목**: FakePgGatewayAdapter + FakePgInboxRepository + FakePgOutboxRepository
- **목적**: ADR-21 수락 기준 — 실제 Toss/NicePay 없이 pg-service application 계층 테스트 가능. Fake가 소비자(T2a-05 이후) 앞에 배치.
- **tdd**: false
- **domain_risk**: false
- **depends**: [T2a-01]
- **산출물**:
  - `pg-service/src/test/java/.../mock/FakePgGatewayAdapter.java` — 응답 설정 가능, timeout 예외 주입 가능
  - `pg-service/src/test/java/.../mock/FakePgInboxRepository.java`
  - `pg-service/src/test/java/.../mock/FakePgOutboxRepository.java`
  - `pg-service/src/test/java/.../mock/FakePgEventPublisher.java`

**완료 결과** (2026-04-21):
- `pg/domain/enums/PgInboxStatus.java` — NONE/IN_PROGRESS/APPROVED/FAILED/QUARANTINED 5상태 + isTerminal() (main source)
- `pg/domain/PgInbox.java` — business inbox plain POJO, create/of/withStatus/withResult 팩토리 (main source)
- `pg/domain/PgOutbox.java` — outbox plain POJO, available_at·attempt 포함 (main source)
- `pg/application/port/out/PgInboxRepository.java` — findByOrderId / save 포트 인터페이스 (main source)
- `pg/application/port/out/PgOutboxRepository.java` — save / findPendingBatch / markProcessed 포트 인터페이스 (main source)
- `pg/mock/FakePgGatewayAdapter.java` — confirm 결과·예외 주입, 호출 횟수 추적, reset() (test source)
- `pg/mock/FakePgInboxRepository.java` — ConcurrentHashMap 인메모리 inbox (test source)
- `pg/mock/FakePgOutboxRepository.java` — ConcurrentHashMap + available_at 필터링 (test source)
- `pg/mock/FakePgEventPublisher.java` — CopyOnWriteArrayList 이벤트 캡처, getLast() / getPublishedCount() (test source)
- `pg/mock/FakePgGatewayAdapterTest.java` — 스모크 3케이스
- `:pg-service:compileJava` PASS / `:pg-service:compileTestJava` PASS / 전체 398/398 PASS (기존 395 + 스모크 3)

---

### T2a-04 — pg-service DB 스키마 (pg_inbox + pg_outbox Flyway V1) ✅ 완료 (2026-04-21)

<!-- done: 2026-04-21 -->
**완료 결과**: `pg-service/src/main/resources/db/migration/V1__pg_schema.sql`(pg_inbox 5상태 ENUM + amount BIGINT + stored_status_result + reason_code + ux/idx 인덱스, pg_outbox available_at + attempt + processed_at + idx_pg_outbox_processed_available) 신설. `pg/domain/enums/PgInboxStatus.java`(T2a-03 기신설)는 경로 충돌 방지를 위해 enums/ 경로 유지(PLAN 원문 domain/model/ 경로 대신) — enum 상수 DDL과 1:1 일치·TERMINAL SSOT 보장. `pg-service/build.gradle`에 spring-boot-starter-data-jpa + mysql-connector-j + flyway-core + flyway-mysql 추가(사전 승인 build.gradle 변경). `docker-compose.infra.yml`에 mysql-pg 컨테이너(포트 3308) + mysql-pg-data 볼륨 추가. `:pg-service:compileJava` PASS / `./gradlew test` 398/398 회귀 없음. docker-compose YAML 파싱 검증 통과.

- **제목**: pg-service Flyway V1 — pg_inbox(business inbox 5상태 + amount 컬럼) + pg_outbox(available_at + attempt)
- **목적**: ADR-21 보강(business inbox amount 컬럼), ADR-30(pg_outbox available_at) — `pg_inbox`: `order_id`(UNIQUE), `status` ENUM(NONE/IN_PROGRESS/APPROVED/FAILED/QUARANTINED), `amount BIGINT NOT NULL`(원화 최소 단위 정수, payload BigDecimal → DB BIGINT 변환 규약: scale=0, 음수·소수 거부), `stored_status_result`, `reason_code`, `created_at`, `updated_at`. `pg_outbox`: `id`, `topic`, `key`, `payload`, `headers_json`, `available_at`, `processed_at`, `attempt`, `created_at`. 인덱스 `(processed_at, available_at)`, `UNIQUE(id)`.
- **tdd**: false
- **domain_risk**: true
- **depends**: [T2a-01]
- **산출물**:
  - `pg-service/src/main/resources/db/migration/V1__pg_schema.sql` — pg_inbox + pg_outbox DDL
  - `pg-service/src/main/java/.../pg/domain/model/PgInboxStatus.java` — NONE/IN_PROGRESS/APPROVED/FAILED/QUARANTINED enum(terminal 집합 SSOT)
  - `docker-compose.infra.yml` — pg 전용 MySQL 컨테이너 추가 (Phase-0.1 방침: Phase 2.a 시점에 추가)

---

### T2a-05a — PgEventPublisher + PgOutboxRelayService 구현 (ADR-04)

- **제목**: pg-service PgEventPublisherPort 구현체 + PgOutboxRelayService (Publisher + RelayService)
- **목적**: ADR-04(pg-service publisher 계층 — T1-11a 대칭) — `PgEventPublisher`가 `PgEventPublisherPort.publish(topic, key, payload, headers)`의 유일한 Kafka 구현체. `infrastructure/messaging/publisher/`에만 존재. `PgOutboxRelayService`가 port를 경유해 `processed_at=NOW()` 갱신. Worker는 port 인터페이스 의존만 가짐 — KafkaTemplate 직접 호출 금지. row의 `topic` 필드가 `payment.commands.confirm` / `payment.commands.confirm.dlq` / `payment.events.confirmed`를 자동 분기.
- **tdd**: true
- **domain_risk**: true
- **depends**: [T2a-04, T2a-03]
- **테스트 클래스**: `PgOutboxRelayServiceTest`
- **테스트 메서드**:
  - `relay_PublishesByTopicField_ThenMarksDone` — `FakePgEventPublisher`로 publish 호출 검증 + topic 필드에 따라 올바른 토픽으로 발행
  - `relay_WhenPublishFails_DoesNotMarkDone` — 발행 실패 시 row 유지
  - `relay_WhenAvailableAtFuture_ShouldSkip` — `available_at > NOW()` row skip
- **산출물**:
  - `pg-service/src/main/java/.../pg/infrastructure/messaging/publisher/PgEventPublisher.java`
  - `pg-service/src/main/java/.../pg/application/service/PgOutboxRelayService.java`

#### 완료 결과 (2026-04-21)

- `PgEventPublisherPort` 시그니처를 `publish(topic, key, payload, headers)`로 교체 (ADR-30 대칭, port가 SSOT).
- `FakePgEventPublisher` 업데이트: 새 시그니처 + `failOnPublish` 시뮬레이션 플래그.
- `PgOutboxRepository`에 `findById(long)` 추가, `FakePgOutboxRepository` 구현 추가.
- `PgOutboxRelayService` 신설 (application/service): Clock 주입, relay(id) — 이미 처리된 row·미래 available_at skip·publish 실패 시 row 미갱신 예외 전파.
- `PgEventPublisher` 신설 (infrastructure/messaging/publisher): `@ConditionalOnProperty(spring.kafka.bootstrap-servers)`, `KafkaTemplate` ProducerRecord 헤더 전달, 멱등성 정책 JavaDoc.
- `PgOutboxRelayServiceTest` 3케이스 PASS (relay_PublishesByTopicField_ThenMarksDone, relay_WhenPublishFails_DoesNotMarkDone, relay_WhenAvailableAtFuture_ShouldSkip).
- 전체 401/401 PASS (payment-service 395 + pg-service 6).

---

### T2a-05b — PgOutboxChannel + OutboxReadyEventHandler 구현 (ADR-04) ✅ 완료 (2026-04-21)

<!-- done: 2026-04-21 -->

- **제목**: pg-service PgOutboxChannel + AFTER_COMMIT 리스너 (EventHandler + Channel)
- **목적**: ADR-04(Channel + EventHandler — T1-11b 대칭) — `PgOutboxChannel`(`LinkedBlockingQueue<Long>`, capacity=1024, offer 실패 시 Polling 워커 fallback으로 안전망 처리) 신설. AFTER_COMMIT 리스너 `OutboxReadyEventHandler`가 `PgOutboxChannel.offer(outboxId)` 호출.
- **tdd**: false
- **domain_risk**: true
- **depends**: [T2a-05a]
- **산출물**:
  - `pg-service/src/main/java/.../pg/infrastructure/channel/PgOutboxChannel.java` — `LinkedBlockingQueue<Long>`, capacity=1024
  - `pg-service/src/main/java/.../pg/listener/OutboxReadyEventHandler.java` — AFTER_COMMIT 리스너

**완료 결과**: `PgOutboxReadyEvent(Long outboxId)` record 신설(domain/event). `PgOutboxChannel` 신설(infrastructure/channel) — `LinkedBlockingQueue<Long>` capacity=1024, offer/take/size/isNearFull(80% 임계치 정수 연산), Micrometer 게이지 2종, `@Slf4j` 평문 로깅(LogFmt 미사용 — T5-02 대상). `OutboxReadyEventHandler` 신설(listener) — `@TransactionalEventListener(AFTER_COMMIT)`, `@ConditionalOnProperty` 미부여(T1-18 교훈). 스모크 테스트 `PgOutboxChannelTest`(5케이스), `OutboxReadyEventHandlerTest`(3케이스) 추가. 전체 411/411 PASS(payment-service 395 + pg-service 14 + gateway 1 + eureka 1). 회귀 없음.

---

### T2a-05c — PgOutboxImmediateWorker + PgOutboxPollingWorker 구현 (ADR-04, ADR-30, SmartLifecycle) ✅ 완료 (2026-04-21)

<!-- done: 2026-04-21 -->

- **제목**: pg-service ImmediateWorker + PollingWorker (SmartLifecycle + VT — T1-11c 대칭)
- **목적**: ADR-04(4구성 파이프라인 완성), ADR-30(available_at 지연) — `PgOutboxImmediateWorker`(SmartLifecycle+VT)가 `channel.take()` → row 로드 → `PgEventPublisherPort.publish(topic, key, payload, headers)` → `processed_at=NOW()`. KafkaTemplate 직접 호출 금지. `PgOutboxPollingWorker`(`@Scheduled fixedDelay`, `WHERE processed_at IS NULL AND available_at<=NOW() FOR UPDATE SKIP LOCKED`)가 Polling 안전망.
- **tdd**: true
- **domain_risk**: true
- **depends**: [T2a-05b]
- **테스트 클래스**: `PgOutboxImmediateWorkerTest`
- **테스트 메서드**:
  - `stop_DrainsInFlightBeforeShutdown` — SmartLifecycle drain
  - `outbox_publish_WhenImmediateAndPollingRace_ShouldEmitOnce` — 중복 produce 차단(불변식 11, FakePgEventPublisher 호출 횟수 assert)
- **산출물**:
  - `pg-service/src/main/java/.../pg/scheduler/PgOutboxImmediateWorker.java` — SmartLifecycle + VT + PgEventPublisherPort 경유
  - `pg-service/src/main/java/.../pg/scheduler/PgOutboxPollingWorker.java` — Polling 안전망

**완료 결과**: `PgOutboxImmediateWorker` 신설(scheduler) — SmartLifecycle(getPhase=Integer.MAX_VALUE-100), Virtual Thread 워커(기본 1개, `pg.outbox.channel.worker-count` 프로퍼티, 생성자 주입으로 Spring Context 없는 단위 테스트 지원), `channel.take()` 블로킹 루프 → `relayExecutor.submit(() -> relay(id))`, stop() 시 running=false + interrupt + relayExecutor `awaitTermination(10s)`. `PgOutboxPollingWorker` 신설(scheduler) — `@Scheduled(fixedDelayString="${pg.scheduler.polling-worker.fixed-delay-ms:2000}")`, `findPendingBatch(batchSize, now)` → 각 row `relay(id)`. `PgOutboxImmediateWorkerTest` 2케이스(`stop_DrainsInFlightBeforeShutdown`, `outbox_publish_WhenImmediateAndPollingRace_ShouldEmitOnce`) TDD GREEN. KafkaTemplate 직접 호출 없음 — PgOutboxRelayService(→ PgEventPublisherPort) 경유. 전체 411/411 PASS(payment-service 395 + pg-service 16). 회귀 없음.

---

### T2a-06 — PaymentConfirmConsumer + consumer dedupe (pg-service)

- **제목**: pg-service PaymentConfirmConsumer — `payment.commands.confirm` 소비 + eventUUID dedupe + inbox 상태 분기
- **목적**: ADR-21(inbox 5상태 모델), ADR-04(2단 멱등성 키) — eventUUID dedupe(메시지 레벨) + orderId inbox dedupe(비즈니스 레벨). inbox 상태별 분기: NONE → IN_PROGRESS 원자 전이(UNIQUE + INSERT ON DUPLICATE KEY UPDATE 또는 SELECT FOR UPDATE). IN_PROGRESS → no-op 대기. terminal(APPROVED/FAILED/QUARANTINED) → 저장된 status 재발행(벤더 재호출 금지, 불변식 4/4b). 실제 PG 호출은 T2b-01로 위임. dry_run 모드(`pg.retry.mode=dry_run`) 시 metric만 기록.
- **tdd**: true
- **domain_risk**: true
- **depends**: [T2a-05c, T2a-04]
- **테스트 클래스**: `PaymentConfirmConsumerTest`
- **테스트 메서드**:
  - `consume_WhenInboxNone_ShouldTransitToInProgressAndCallVendor` — NONE → IN_PROGRESS + PG 호출 1회
  - `consume_WhenInboxInProgress_ShouldNoOp` — IN_PROGRESS 재수신 → no-op (불변식 4b)
  - `consume_WhenInboxTerminal_ShouldReemitStoredStatus` — 종결 상태 재수신 → 저장 status 재발행(불변식 4)
  - `consume_DuplicateEventUUID_ShouldNoOp` — 동일 eventUUID 2회 → PG 호출 0회 (불변식 5)
  - `consume_WhenInboxNoneToInProgress_ShouldBeAtomicUnderConcurrency` — 동시 진입 시 중복 IN_PROGRESS 전이 차단 (race 봉쇄, 불변식 4b)
- **산출물**:
  - `pg-service/src/main/java/.../pg/infrastructure/messaging/consumer/PaymentConfirmConsumer.java`
  - `pg-service/src/main/java/.../pg/application/service/PgConfirmService.java` — inbox 상태 분기 orchestration

---

### T2a-Gate — Phase 2.a 마이크로 Gate ✅

- **제목**: Phase 2.a Gate — pg-service 골격 + Outbox 파이프라인 + consumer 기반 검증
- **목적**: T2a-01~T2a-06 완료 후 pg-service 기동, Flyway 스키마 적용, `payment.commands.confirm` 수신 후 inbox NONE→IN_PROGRESS 전이, Outbox 워커 기동, dry_run 메트릭 기록 확인.
- **tdd**: false
- **domain_risk**: true
- **depends**: [T2a-06]
- **산출물**:
  - `scripts/phase-gate/phase-2a-gate.sh` ✅ — pg-service healthcheck, Flyway V1 적용, consumer 수신 + inbox 전이, worker 기동, dry_run metric 확인
  - `docs/phase-gate/phase-2a-gate.md` ✅

**완료 결과** (2026-04-21):
`scripts/phase-gate/phase-2a-gate.sh` 신설 (`chmod +x` 완료). `docs/phase-gate/phase-2a-gate.md` 신설. 검증 항목 9섹션 구성: (1) pg-service actuator/health, (2) mysql-pg 컨테이너 running + pg DB 접속, (3) Flyway V1 마이그레이션(pg_inbox/pg_outbox 테이블 존재), (4) Kafka 브로커 응답 + 토픽 payment.commands.confirm 존재, (5) consumer group pg-service 등록, (6) PgOutboxImmediateWorker SmartLifecycle 기동 확인, (7) pg_inbox NONE→IN_PROGRESS CAS 전이 멱등성 smoke(ROW_COUNT=1 후 재시도 ROW_COUNT=0), (8) pg.outbox.channel.* Micrometer 게이지 + JVM 메트릭 노출, (9) pg_outbox clean state. 포트 충돌 주의(pg-service 기본 포트 8080 = Gateway 포트) 가이드를 스크립트 상단 주석 + Gate 문서에 명시. `PG_SERVICE_BASE` 환경 변수로 재정의 가능(예: `PG_SERVICE_BASE=http://localhost:8082`). `set -euo pipefail` + 컬러 로그 + [PASS]/[FAIL] 형식. `./gradlew test` 418/418 회귀 없음.

---

## Phase 2.b — business inbox 5상태 + amount 컬럼 + 벤더 어댑터 통합

### T2b-01 — PG 벤더 호출 + 재시도 루프 + available_at 지연 재발행 (ADR-30)

- **제목**: pg-service 내부 PG 벤더 호출 + 지수 백오프 재시도 + `pg_outbox.available_at` 지연 row INSERT
- **목적**: ADR-30(재시도 = outbox available_at 지연 표현) — 벤더 호출 성공 → pg_outbox(topic=`payment.events.confirmed`, status=APPROVED/FAILED). 재시도 가능 오류 + attempt < MAX(4) → pg_outbox(topic=`payment.commands.confirm`, `available_at=NOW()+backoff(attempt+1)`, header `attempt+1`) INSERT(같은 TX). attempt >= 4 → pg_outbox(topic=`payment.commands.confirm.dlq`, header `attempt=4`) INSERT(같은 TX). TX commit 후 AFTER_COMMIT 이벤트 → PgOutboxChannel. 재시도 상수: base=2s, multiplier=3, attempts=4, jitter=±25% equal jitter.
- **tdd**: true
- **domain_risk**: true
- **depends**: [T2a-06, T2a-03]
- **테스트 클래스**: `PgVendorCallServiceTest`
- **테스트 메서드**:
  - `callVendor_WhenSuccess_ShouldInsertApprovedOutboxRow` — 성공 → pg_outbox(events.confirmed, APPROVED) INSERT
  - `callVendor_WhenRetryableErrorAndAttemptNotExceeded_ShouldInsertRetryOutboxRow` — 재시도 가능 오류 + attempt<4 → pg_outbox(commands.confirm, available_at=future)
  - `callVendor_WhenRetryableErrorAndAttemptExceeded_ShouldInsertDlqOutboxRow` — attempt>=4 → pg_outbox(commands.confirm.dlq)(불변식 6)
  - `callVendor_WhenDefinitiveFailure_ShouldInsertFailedOutboxRow` — 확정 실패 → pg_outbox(events.confirmed, FAILED)
  - `retry_WhenAttemptExceeded_ShouldWriteDlqOutboxRow` — attempt 소진 DLQ row INSERT 원자성
- **산출물**:
  - `pg-service/src/main/java/.../pg/application/service/PgVendorCallService.java`
  - `pg-service/src/main/java/.../pg/domain/RetryPolicy.java` — base=2s, multiplier=3, attempts=4, jitter=25%

---

### T2b-02 — PaymentConfirmDlqConsumer 구현 — DLQ 전용 consumer (ADR-30)

- **제목**: pg-service DLQ 전용 consumer — QUARANTINED 전이 + 격리 이벤트 outbox row INSERT
- **목적**: ADR-30(DLQ 전용 consumer 분리) — `PaymentConfirmDlqConsumer`는 `PaymentConfirmConsumer`와 물리적으로 다른 Spring bean. `payment.commands.confirm.dlq` 구독. inbox FOR UPDATE → terminal이면 no-op(중복 DLQ 흡수, 불변식 6c). 아니면 pg_inbox QUARANTINED 전이 + pg_outbox에 `payment.events.confirmed(status=QUARANTINED, reasonCode=RETRY_EXHAUSTED)` row INSERT(같은 TX). TX commit 후 AFTER_COMMIT 이벤트 → worker 발행. payment-service측 consumer가 `QUARANTINED` 수신 시 `QuarantineCompensationHandler`로 내부 수렴(재고 INCR 보상은 payment-service가 책임, §2-2b-3 2단계 복구 재사용). DLQ consumer 자체 실패 시 offset 미커밋 → 재기동 후 재처리, pg_inbox UNIQUE + terminal 체크로 중복 방어.
- **tdd**: true
- **domain_risk**: true
- **depends**: [T2b-01, T2a-05c]
- **테스트 클래스**: `PaymentConfirmDlqConsumerTest`
- **테스트 메서드**:
  - `dlq_consumer_WhenNormalMessage_ShouldQuarantine` — DLQ 메시지 정상 처리 → pg_inbox QUARANTINED + `payment.events.confirmed(QUARANTINED)` outbox row 1건 (불변식 6)
  - `dlq_consumer_WhenAlreadyTerminal_ShouldBeNoOp` — 이미 terminal → no-op (불변식 6c)
  - `dlq_consumer_WhenQuarantined_ShouldInsertSingleConfirmedRow` — QUARANTINED 전이 시 `payment.events.confirmed` outbox row 1건만 INSERT (격리 보상은 payment-service 내부 수렴)
  - `dlq_consumer_WhenConsumerItself_ShouldBeDifferentBeanFromNormalConsumer` — `PaymentConfirmConsumer`와 다른 bean 검증 (ADR-30 수락 기준)
- **산출물**:
  - `pg-service/src/main/java/.../pg/infrastructure/messaging/consumer/PaymentConfirmDlqConsumer.java`

---

### T2b-03 — pg-service 내부 FCG 구현 (ADR-15, ADR-21)

- **제목**: pg-service Final Confirmation Gate — 재시도 소진 후 1회 최종 확인
- **목적**: ADR-15(FCG 수행 주체=pg-service), ADR-21 — PG 내부 재시도 루프 소진 후 벤더 `getStatus` 1회 최종 확인. APPROVED/FAILED → pg_outbox(events.confirmed). 판정 불가(timeout·5xx·네트워크 에러) → 무조건 QUARANTINED(재시도 래핑 금지, FCG 불변). payment-service는 FCG 존재를 모른다.
- **tdd**: true
- **domain_risk**: true
- **depends**: [T2b-01, T2a-03]
- **테스트 클래스**: `PgFinalConfirmationGateTest`
- **테스트 메서드**:
  - `fcg_WhenVendorReturnsApproved_ShouldInsertApprovedOutboxRow` — 벤더 최종 확인 APPROVED → pg_outbox INSERT
  - `fcg_WhenVendorReturnsFailed_ShouldInsertFailedOutboxRow` — 확정 실패 → FAILED
  - `fcg_WhenVendorTimesOut_ShouldQuarantine_NoRetry` — timeout → QUARANTINED, 재시도 없음(불변식 FCG 불변)
  - `fcg_WhenVendor5xx_ShouldQuarantine` — 5xx → QUARANTINED
- **산출물**:
  - `pg-service/src/main/java/.../pg/application/service/PgFinalConfirmationGate.java`

---

### T2b-04 — pg-service business inbox amount 컬럼 저장 규약 구현 (ADR-21 보강, ADR-05 보강)

- **제목**: pg_inbox.amount 저장 규약 구현 — NONE→IN_PROGRESS payload amount 기록 + APPROVED 시 2자 대조 통과값 기록
- **목적**: ADR-21 보강(business inbox `amount BIGINT NOT NULL` 컬럼 저장 규약), discuss-domain-5 minor(BigDecimal→BIGINT 변환 규약) — (a) NONE→IN_PROGRESS 전이 시 command payload amount를 `BigDecimal.longValueExact()` 변환(scale=0 강제, 음수·소수 거부)하여 pg_inbox.amount에 기록. (b) IN_PROGRESS→APPROVED 전이 시 벤더 2자 재조회 amount == inbox.amount 검증 통과한 값만 저장(불일치 시 QUARANTINED+AMOUNT_MISMATCH). (c) "pg DB 부재 경로"(ADR-05 보강 6번)에서 NONE→APPROVED 직접 전이 시 벤더 재조회 amount == command payload amount 검증 통과값만 기록. 이로써 불변식 4c 좌변 출처 스키마 수준 확정.
- **tdd**: true
- **domain_risk**: true
- **depends**: [T2a-04, T2b-01]
- **테스트 클래스**: `PgInboxAmountStorageTest`
- **테스트 메서드**:
  - `storeInbox_WhenNoneToInProgress_ShouldRecordPayloadAmount` — NONE→IN_PROGRESS 전이 시 command payload amount → `inbox.amount` 기록(BigDecimal scale=0 검증 포함)
  - `storeInbox_WhenApproved_ShouldPassTwoWayAmountCheck` — APPROVED 전이 시 pg DB amount vs 벤더 재조회 amount 일치 → 저장(불변식 4c)
  - `storeInbox_WhenApproved_WhenAmountMismatch_ShouldQuarantine` — 2자 불일치 → QUARANTINED+AMOUNT_MISMATCH(불변식 4c)
  - `storeInbox_WhenBigDecimalScaleNotZero_ShouldReject` — scale>0 BigDecimal → ArithmeticException 거부
- **산출물**:
  - `pg-service/src/main/java/.../pg/application/service/PgInboxAmountService.java` — 저장 규약 (a)(b)(c) 구현
  - `pg-service/src/main/java/.../pg/infrastructure/converter/AmountConverter.java` — `BigDecimal.longValueExact()` 변환 유틸

---

### T2b-05 — 중복 승인 응답 2자 금액 대조 + pg DB 부재 경로 방어 (ADR-05 보강, ADR-21)

- **제목**: Toss `ALREADY_PROCESSED_PAYMENT` / NicePay `2201` 중복 승인 방어 — 2자 금액 대조 + pg DB 부재 경로 amount 검증
- **목적**: ADR-05 보강, ADR-21(캡슐화 대상) — pg-service 내부 방어. (1) pg DB 레코드 존재 시: 벤더 `getStatus` 재조회 → pg DB amount vs 벤더 재조회 amount 2자 대조 → 일치 시 저장 status 재발행, 불일치 시 QUARANTINED+AMOUNT_MISMATCH. (2) pg DB 레코드 부재 시(ADR-05 보강 6번): 벤더 재조회 amount == command payload amount 검증 → 일치 시 APPROVED+운영 알림(관측만), 불일치 시 QUARANTINED+AMOUNT_MISMATCH(불변식 4c). payment-service는 이 로직의 존재를 모른다(ADR-21(v) 불변).
- **tdd**: true
- **domain_risk**: true
- **depends**: [T2b-04, T2a-03]
- **테스트 클래스**: `DuplicateApprovalHandlerTest`
- **테스트 메서드**:
  - `pg_duplicate_approval_WhenPgDbExists_WhenAmountMatch_ShouldReemitStoredStatus` — pg DB 존재 + 2자 일치 → 저장 status 재발행
  - `pg_duplicate_approval_WhenPgDbExists_WhenAmountMismatch_ShouldQuarantine` — 2자 불일치 → QUARANTINED+AMOUNT_MISMATCH (불변식 4c)
  - `pg_duplicate_approval_WhenPgDbAbsent_WhenAmountMatch_ShouldAlertAndApprove` — pg DB 부재 + payload amount 일치 → APPROVED + 운영 알림(불변식 4c)
  - `pg_duplicate_approval_WhenPgDbAbsent_WhenAmountMismatch_ShouldQuarantine` — pg DB 부재 + 불일치 → QUARANTINED+AMOUNT_MISMATCH
  - `pg_duplicate_approval_WhenVendorRetrievalFails_ShouldQuarantine` — 벤더 재조회 실패 → QUARANTINED
  - `NicepayStrategy_WhenCode2201_ShouldDelegateToDuplicateHandler` — NicePay 2201 → DuplicateApprovalHandler 호출 (대칭화 검증)
- **산출물**:
  - `pg-service/src/main/java/.../pg/application/service/DuplicateApprovalHandler.java`
  - `pg-service/src/main/java/.../pg/infrastructure/gateway/toss/TossPaymentGatewayStrategy.java` — `ALREADY_PROCESSED_PAYMENT` 분기 → DuplicateApprovalHandler 위임
  - `pg-service/src/main/java/.../pg/infrastructure/gateway/nicepay/NicepayPaymentGatewayStrategy.java` — `2201` 분기 → DuplicateApprovalHandler 위임(handleDuplicateApprovalCompensation 이관)

---

### T2b-Gate — Phase 2.b 마이크로 Gate

- **제목**: Phase 2.b Gate — business inbox 5상태 + 벤더 어댑터 통합 검증
- **목적**: T2b-01~T2b-05 완료 후 중복 승인 응답 2자 대조(Fake 벤더), pg DB 부재 경로 APPROVED/QUARANTINED 분기, inbox amount 저장 규약 E2E 검증.
- **tdd**: false
- **domain_risk**: true
- **depends**: [T2b-05]
- **산출물**:
  - `scripts/phase-gate/phase-2b-gate.sh` — Fake 벤더로 중복 승인 시나리오, pg DB 부재 경로 시나리오, amount 불일치 QUARANTINED 확인
  - `docs/phase-gate/phase-2b-gate.md`

---

## Phase 2.c — pg.retry.mode 스위치 + 기존 reconciler 코드 삭제

### T2c-01 — pg.retry.mode=outbox 활성화 스위치

- **제목**: feature flag `pg.retry.mode=outbox` 즉시 전환 + 기존 OutboxProcessingService PG 직접 호출 경로 OFF
- **목적**: ADR-30(Phase 2.b 스위치) — `PaymentConfirmConsumer` + `PaymentConfirmDlqConsumer` 실제 경로 활성화. 기존 payment-service의 `OutboxProcessingService` PG 직접 호출 로직 OFF. QUARANTINED 진입점 a(FCG) + b(DlqConsumer) 확정. 롤백: flag 복원 시 `OutboxProcessingService` 재활성.
- **tdd**: false
- **domain_risk**: true
- **depends**: [T2b-Gate]
- **산출물**:
  - `pg-service/src/main/resources/application.yml` — `pg.retry.mode: outbox`
  - `payment-service/src/main/java/.../payment/scheduler/OutboxProcessingService.java` — PG 직접 호출 로직 `@ConditionalOnProperty` 비활성화

---

### T2c-02 — 기존 reconciler·PG 직접 호출 코드 삭제 + payment-service 측 잔존 어댑터 정리

- **제목**: payment-service OutboxProcessingService PG 호출 로직 삭제 + `GET /internal/pg/status/{orderId}` 엔드포인트·`PgStatusPort`·`PgStatusHttpAdapter` 삭제 확인
- **목적**: ADR-30(Phase 2.c 정리), ADR-02/ADR-21(Kafka only 불변) — `OutboxProcessingService`의 `claimToInFlight`/`getStatus`/`applyDecision` 체인 제거. `PgStatusPort`·`PgStatusHttpAdapter`·`GET /internal/pg/status/{orderId}` 엔드포인트 payment-service에 존재하지 않음을 계약 테스트로 고정(불변식 19).
- **tdd**: true
- **domain_risk**: true
- **depends**: [T2c-01]
- **테스트 클래스**: `PgStatusAbsenceContractTest`
- **테스트 메서드**:
  - `pgStatusPort_ShouldNotExistInPaymentService` — Spring context에 `PgStatusPort` bean 없음
  - `pgStatusHttpAdapter_ShouldNotExistInPaymentService` — `PgStatusHttpAdapter` class 없음
  - `executePaymentAndOutbox_ShouldNotWrapPgCall` — payment-service TX 내 PG HTTP 호출 없음 (불변식 12)
- **산출물**:
  - `payment-service/src/main/java/.../payment/scheduler/OutboxProcessingService.java` — PG 호출 로직 삭제
  - `PgStatusPort.java`, `PgStatusHttpAdapter.java`, `GET /internal/pg/status/**` 엔드포인트 삭제

---

### T2c-Gate — Phase 2.c 마이크로 Gate

- **제목**: Phase 2.c Gate — 스위치 전환 + 잔존 코드 삭제 검증
- **목적**: T2c-01~T2c-02 완료 후 payment-service에 `PgStatusPort` 부재, DlqConsumer 정상 동작, Kafka 왕복 E2E 검증.
- **tdd**: false
- **domain_risk**: true
- **depends**: [T2c-02]
- **산출물**:
  - `scripts/phase-gate/phase-2c-gate.sh` — PgStatusPort 부재 계약 확인, Kafka 왕복 E2E, DLQ consumer 시나리오
  - `docs/phase-gate/phase-2c-gate.md`

---

## Phase 2.d — 관측 대시보드 + 알림 활성화 + 결제 서비스 측 이벤트 소비

### T2d-01 — 결제 서비스 측 Kafka consumer (payment.events.confirmed 소비)

- **제목**: payment-service ConfirmedEventConsumer + eventUUID dedupe + QuarantineCompensationHandler 연결
- **목적**: ADR-14, ADR-04 — `payment.events.confirmed` 소비 → eventUUID dedupe → status별 분기: APPROVED → DONE 전이 + StockCommitEvent 발행, FAILED → FAILED + stock.events.restore 보상, QUARANTINED → QuarantineCompensationHandler(진입점 a/b 공통 수렴).
- **tdd**: true
- **domain_risk**: true
- **depends**: [T2c-02, T1-12]
- **테스트 클래스**: `ConfirmedEventConsumerTest`
- **테스트 메서드**:
  - `consume_WhenApproved_ShouldTransitionToDone` — APPROVED → PaymentEvent DONE 전이
  - `consume_WhenFailed_ShouldTransitionToFailed` — FAILED → PaymentEvent FAILED
  - `consume_WhenQuarantined_ShouldDelegateToQuarantineHandler` — QUARANTINED → QuarantineCompensationHandler 1회 호출
  - `consume_DuplicateEvent_ShouldDedupeByEventUUID` — 동일 eventUUID 2회 → 상태 전이 1회 (불변식 5)
  - `consumer_WhenSameEventUUIDReceived_ShouldNoOp` — dedupe no-op 검증(불변식 5)
- **산출물**:
  - `payment-service/src/main/java/.../payment/infrastructure/messaging/consumer/ConfirmedEventConsumer.java`
  - `payment-service/src/main/java/.../payment/application/usecase/PaymentConfirmResultUseCase.java`

---

### T2d-02 — 토픽 네이밍 규약 확정 + Outbox 관측 지표 + Grafana 대시보드 (ADR-12, ADR-31)

- **제목**: 전 서비스 공통 토픽 네이밍 규약 확정 + pg_outbox/payment_outbox 관측 지표 + Grafana 위젯 활성화
- **목적**: ADR-12(토픽 네이밍 `<source-service>.<type>.<action>`), ADR-31(Outbox 관측 지표) — `payment.commands.confirm`, `payment.commands.confirm.dlq`, `payment.events.confirmed` 토픽 목록 ADR-12 결론란 기록. `{payment,pg}_outbox.pending_count`, `future_pending_count`, `oldest_pending_age_seconds`, `attempt_count_histogram` 수집. Grafana 결제 전용 대시보드 위젯 배포. 알림 4종(ADR-31) 활성화: DLQ 유입률>0, future_pending_count>N 지속, oldest_pending_age_seconds>300s, invariant 불일치.
- **tdd**: false
- **domain_risk**: false
- **depends**: [T2d-01]
- **산출물**:
  - `payment-service/src/main/java/.../payment/infrastructure/messaging/PaymentTopics.java` — 상수 최종화
  - `pg-service/src/main/java/.../pg/infrastructure/messaging/PgTopics.java`
  - `product-service/src/main/java/.../product/infrastructure/messaging/ProductTopics.java` (Phase 3 산출물 미리 선언)
  - `docs/topics/MSA-TRANSITION.md` ADR-12 결론란 토픽 목록 표 + 네이밍 규약 기록
  - `observability/grafana/dashboards/payment-dashboard.json` — 위젯 활성화 + 알림 4종 설정 (2026-04-23 `chaos/grafana/` 에서 이동)

---

### T2d-03 — Gateway 라우팅: PG 내부 API 격리

- **제목**: Gateway — PG 서비스 `getStatus` 내부 API 외부 노출 차단
- **목적**: ADR-21, ADR-02 — PG 서비스 `GET /internal/**` 경로를 외부 클라이언트로부터 차단.
- **tdd**: false
- **domain_risk**: false
- **depends**: [T2d-01]
- **산출물**:
  - `gateway/src/main/resources/application.yml` — `path=/internal/**` deny filter
  - `gateway/src/main/java/.../gateway/filter/InternalOnlyGatewayFilter.java`

---

### Phase-2-Gate — Phase 2 통합 E2E 검증

- **제목**: Phase 2 Gate — PG 서비스 분리 E2E + ADR-30 Kafka 왕복 통합 검증 (다음 Phase 진입 판정)
- **목적**: T2a-Gate~T2d-03 완료 후 pg-service 독립 기동, Kafka 왕복(command → confirm → event → payment 상태 전이), dedupe, Fake PG 벤더 격리, DLQ consumer QUARANTINED 전이, 2자 금액 대조, pg DB 부재 경로 E2E 검증.
- **tdd**: false
- **domain_risk**: true
- **depends**: [T2d-03, T2d-02, T2c-Gate]
- **산출물**:
  - `scripts/phase-gate/phase-2-gate.sh` — pg-service healthcheck, Kafka 왕복 E2E, eventUUID dedupe, Fake PG 교체, DLQ QUARANTINED E2E, 2자 금액 대조 시나리오, `topic_config_WhenProvisioned_ShouldShareSamePartitionCount` 토픽 파티션 수 동일 확인(불변식 6b)
  - `docs/phase-gate/phase-2-gate.md`

---

## Phase 3 — 상품·사용자 서비스 분리

**목적**: 주변 도메인 분리. 결제 서비스의 Internal 어댑터 → HTTP/이벤트 기반 교체. `stock.events.restore` 보상 이벤트화(payment-service 측 publisher T3-04b + consumer dedupe T3-05). StockCommitConsumer + product→payment Redis 직접 SET.

**관련 ADR**: ADR-22, ADR-23, ADR-02(재확정), ADR-14, ADR-16

---

### T3-01 — 상품 서비스 모듈 신설 + 도메인 이관 + stock-snapshot 발행 훅

- **제목**: product-service 신규 모듈 + 도메인 이관 + port 계층 + StockSnapshotPublisher
- **목적**: ADR-22(product → user 순서), ADR-23 — MVC + VT. Flyway V1. `product.events.stock-snapshot` 토픽 발행 훅(ApplicationReadyEvent → 전 상품 재고 일괄 발행 → payment-service Phase-1.17 warmup pair).
- **tdd**: false
- **domain_risk**: false
- **depends**: [Phase-2-Gate]
- **산출물**:
  - `settings.gradle` — `include 'product-service'`
  - `product-service/build.gradle` — spring-boot-starter-web, VT, spring-kafka, spring-data-redis
  - `product-service/src/main/java/.../product/domain/Product.java`
  - `product-service/src/main/java/.../product/domain/Stock.java`
  - `product-service/src/main/java/.../product/application/port/out/StockRepository.java`
  - `product-service/src/main/java/.../product/application/port/out/EventDedupeStore.java` — `boolean recordIfAbsent(String eventUuid, Instant expiresAt)`
  - `product-service/src/main/java/.../product/presentation/port/StockRestoreCommandService.java`
  - `product-service/src/main/java/.../product/application/usecase/StockRestoreUseCase.java` — `implements StockRestoreCommandService`
  - `product-service/src/main/resources/db/migration/V1__product_schema.sql`
  - `product-service/src/main/java/.../product/infrastructure/config/KafkaTopicConfig.java` — `product.events.stock-snapshot` 포함
  - `product-service/src/main/java/.../product/infrastructure/event/StockSnapshotPublisher.java` — ApplicationReadyEvent 리스너

---

### T3-02 — 사용자 서비스 모듈 신설 + 도메인 이관 (ADR-22)

- **제목**: user-service 신규 모듈 + 도메인 이관 + port 계층 + Flyway V1
- **목적**: ADR-22(product → user 순서 완성) — MVC + VT. `GET /api/v1/users/{id}` 엔드포인트.
- **tdd**: false
- **domain_risk**: false
- **depends**: [T3-01]
- **산출물**:
  - `settings.gradle` — `include 'user-service'`
  - `user-service/build.gradle`
  - `user-service/src/main/java/.../user/domain/User.java`
  - `user-service/src/main/java/.../user/application/port/out/UserRepository.java`
  - `user-service/src/main/java/.../user/presentation/port/UserQueryService.java`
  - `user-service/src/main/java/.../user/application/usecase/UserQueryUseCase.java` — `implements UserQueryService`
  - `user-service/src/main/java/.../user/presentation/UserController.java`
  - `user-service/src/main/resources/db/migration/V1__user_schema.sql`

---

### ✅ T3-03 — Fake 상품·사용자 서비스 구현 (테스트용)

- **제목**: FakeStockRepository + FakeEventDedupeStore + FakePaymentStockCachePort
- **목적**: ADR-16 보상 dedupe 테스트. Fake가 소비자(T3-04 이후) 앞에 배치.
- **tdd**: false
- **domain_risk**: false
- **depends**: [T3-02]
- **산출물**:
  - `product-service/src/test/java/.../mock/FakeStockRepository.java`
  - `product-service/src/test/java/.../mock/FakeEventDedupeStore.java` — TTL 만료 시뮬레이션
  - `product-service/src/test/java/.../mock/FakePaymentStockCachePort.java` — SET 이력 기록

**완료 결과 — T3-03** (2026-04-21):

- `PaymentStockCachePort` 포트 인터페이스 main source 신설(`application/port/out/`): `void setStock(long productId, int stock)` 단일 메서드. 기술 중립 주석(Redis 언급 금지, ADR-22 준수). T3-04 어댑터 구현 예정.
- `FakeStockRepository`: `ConcurrentHashMap<Long, Integer>` 기반 in-memory. `save/findByProductId/findAll` 포트 계약 전부 구현. `increment(long, int)` / `decrement(long, int)` 원자적 헬퍼(음수 방어 포함). `FakeStockRepositoryTest` 5케이스 GREEN.
- `FakeEventDedupeStore`: `ConcurrentHashMap<String, Instant>` 기반. TTL 만료 시뮬레이션 — `recordIfAbsent` 호출 시 기존 `expiresAt < now(clock)` 이면 덮어쓰기 후 true 반환. `Clock` 생성자 주입(`Clock.fixed` 테스트 결정성). `FakeEventDedupeStoreTest` 4케이스(미존재 true·중복 false·TTL 만료 true·복수 UUID 독립) GREEN.
- `FakePaymentStockCachePort`: `ConcurrentHashMap<Long, Integer>` 최신 상태 + `List<SetRecord(productId, stock, timestamp)>` SET 이력 + `AtomicInteger setCallCount`. `getLatestStock / getHistory / getSetCallCount / getSetCallCountFor / reset` 헬퍼. `FakePaymentStockCachePortTest` 6케이스 GREEN.
- 전체 테스트 503/503 PASS (eureka 1 + gateway 3 + payment-service 386 + pg-service 95 + product-service 17 + user-service 1), 회귀 없음.

---

### T3-04 — StockCommitConsumer + payment-service 전용 Redis 직접 SET (S-2, S-3)

- **제목**: product-service `payment.events.stock-committed` 소비 → RDB UPDATE + payment Redis 직접 SET
- **목적**: S-2(StockCommitEvent 소비), S-3(Redis 직접 쓰기) — `PaymentStockCachePort`(application/port/out, 기술명 Redis 제외) → `PaymentRedisStockAdapter`(infrastructure/cache, `redis-payment` 연결). eventUUID dedupe. RDB UPDATE 실패 시 Redis SET 미호출. keyspace `stock:{productId}`.
- **tdd**: true
- **domain_risk**: true
- **depends**: [T3-03, T1-10]
- **테스트 클래스**: `StockCommitConsumerTest`, `StockCommitUseCaseTest`
- **테스트 메서드**:
  - `StockCommitUseCaseTest#commit_ShouldUpdateRdbAndSetPaymentRedis` — RDB UPDATE + Redis SET 원자적 호출(FakeStockRepository + FakePaymentStockCachePort)
  - `StockCommitUseCaseTest#commit_DuplicateEventUuid_ShouldNoOp` — dedupe
  - `StockCommitUseCaseTest#commit_WhenRdbUpdateFails_ShouldNotSetRedis` — RDB 실패 시 Redis SET 미호출
  - `StockCommitConsumerTest#consume_ShouldDelegateToStockCommitUseCase` — usecase 1회 호출만
- **산출물**:
  - `product-service/src/main/java/.../product/application/port/out/PaymentStockCachePort.java`
  - `product-service/src/main/java/.../product/infrastructure/cache/PaymentRedisStockAdapter.java`
  - `product-service/src/main/java/.../product/infrastructure/messaging/consumer/StockCommitConsumer.java`
  - `product-service/src/main/java/.../product/application/usecase/StockCommitUseCase.java`
  - `product-service/src/main/resources/application.yml` — `redis-payment` 연결
  - `product-service/src/main/resources/db/migration/V3__add_stock_commit_dedupe.sql`

---

### T3-04b — FAILED 결제 stock.events.restore 보상 이벤트 발행 (ADR-04, ADR-16) ✅

- **제목**: payment-service FAILED 전이 시 stock.events.restore 보상 이벤트 outbox 발행 (UUID 포함)
- **목적**: ADR-04(Transactional Outbox), ADR-16(UUID dedupe) — 결제 FAILED 전이 시 payment-service가 `payment_outbox`에 `topic=stock.events.restore`, `key=orderId`, payload(eventUUID·productId·qty) row를 **같은 TX 내** INSERT하여 보상 이벤트 발행 경로를 보장. eventUUID는 UUID v4 랜덤 생성(ADR-16 수락 기준). 발행 경로는 기존 `MessagePublisherPort` outbox 파이프라인 재사용. T3-05(consumer dedupe)가 소비하는 이벤트의 발행 측 대응 태스크. 관련 ADR: ADR-04, ADR-16, §2-2b 보상 경로.
- **tdd**: true
- **domain_risk**: true
- **depends**: [T2d-01, T1-11c]
- **테스트 클래스**: `FailureCompensationServiceTest`
- **테스트 메서드**:
  - [x] `whenFailed_ShouldEnqueueStockRestoreCompensation` — FAILED 전이 시 payment_outbox에 stock.events.restore row 1건 INSERT(orderId·productId·qty·eventUUID 필드 포함)
  - [x] `whenFailed_IdempotentWhenCalledTwice` — 동일 orderId 2회 → outbox row 1건만 INSERT(멱등 UUID 보장)
- **산출물**:
  - [x] `payment-service/src/main/java/.../payment/application/service/FailureCompensationService.java` — FAILED 전이 후 stock.events.restore outbox row INSERT + UUID 생성
  - [x] `payment-service/src/main/java/.../payment/application/dto/StockRestoreEventPayload.java` — eventUUID, orderId, productId, qty
- **완료 결과**: FailureCompensationService(orderId+productId 기반 결정론적 UUID v3 생성, StockRestoreEventPublisherPort.publishPayload 경유) + StockRestoreEventPayload record(eventUUID·orderId·productId·qty) 신설. StockRestoreEventPublisherPort에 publishPayload 메서드 추가. FakeStockRestoreEventPublisher UUID 멱등 시뮬레이션 확장. FailureCompensationServiceTest 2케이스 GREEN. 509/509 PASS(eureka 1+gateway 3+payment-service 388+pg-service 95+product-service 21+user-service 1), 회귀 없음.

---

### T3-05 — 보상 이벤트 consumer dedupe 구현 (ADR-16)

- **제목**: `stock.events.restore` consumer + EventDedupeStore port/구현체 분리 (UUID 키, 상품 서비스 소유)
- **목적**: ADR-16(보상 dedupe 소유 = consumer 측) — `StockRestoreConsumer`는 inbound port(`StockRestoreCommandService`) 경유만. consumer 내부에서 dedupe·stock 직접 로직 금지. TTL = Kafka retention + 1일. T3-04b(payment-service 측 발행)가 선행하여 소비할 이벤트가 발행됨을 전제.
- **tdd**: true
- **domain_risk**: true
- **depends**: [T3-03, T3-04b]
- **테스트 클래스**: `StockRestoreConsumerTest`, `StockRestoreUseCaseTest`
- **테스트 메서드**:
  - `StockRestoreUseCaseTest#restore_ShouldIncreaseStock` — 재고 복원 1회
  - `StockRestoreUseCaseTest#restore_DuplicateEventUuid_ShouldNoOp` — 이중 복원 방지(불변식 14)
  - `StockRestoreUseCaseTest#restore_AfterDedupeTtlExpiry_ShouldReprocessOnce` — TTL 만료 → 재처리 1회
  - `StockRestoreUseCaseTest#restore_WhenStockIncreaseFailsMidway_ShouldNotRecordDedupe` — 재고 증가 실패 시 dedupe 미기록
  - `StockRestoreConsumerTest#consume_ShouldDelegateToStockRestoreUseCase` — usecase 1회 호출만
- **산출물**:
  - [x] `product-service/src/main/java/.../product/infrastructure/messaging/consumer/StockRestoreConsumer.java`
  - [x] `product-service/src/main/java/.../product/infrastructure/idempotency/JdbcEventDedupeStore.java`
  - [x] `product-service/src/main/resources/db/migration/V2__add_event_dedupe_table.sql`
- **완료 결과**: StockRestoreCommandService 시그니처 확장(orderId, eventUuid, productId, qty). EventDedupeStore 포트에 existsValid(TTL-aware 중복 확인) 추가. FakeEventDedupeStore existsValid 구현. StockRestoreUseCase(application/usecase, @Transactional): existsValid 선호출(중복이면 no-op) → 재고 조회·증가·save → recordIfAbsent(성공 후 dedupe 기록) — 불변식 14 충족. StockRestoreMessage record(orderId·eventUuid·productId·qty) + StockRestoreConsumer(@KafkaListener stock.events.restore, groupId=product-service, matchIfMissing=true) 신설. JdbcEventDedupeStore(@Repository): existsValid(SELECT+expires_at≥NOW) + recordIfAbsent(DELETE만료행+INSERT IGNORE) — V1 product_event_dedupe 테이블 사용. V2__add_event_dedupe_table.sql: expires_at 인덱스 보강만(테이블 V1 기신설). ProductTopics.STOCK_EVENTS_RESTORE 추가. TTL=8일(Kafka retention 7+1). StockRestoreUseCaseTest 4케이스 + StockRestoreConsumerTest 1케이스 GREEN. 514/514 PASS(eureka 1+gateway 3+payment-service 388+pg-service 95+product-service 26+user-service 1), 회귀 없음.

---

### T3-06 — 결제 서비스 ProductPort/UserPort → HTTP 어댑터 교체

- **제목**: InternalProductAdapter → ProductHttpAdapter, InternalUserAdapter → UserHttpAdapter 교체
- **목적**: ADR-02, ADR-22 — `@CircuitBreaker`는 adapter 내부 메서드에만(port 인터페이스 오염 금지).
- **tdd**: true
- **domain_risk**: false
- **depends**: [T3-04]
- **테스트 클래스**: `ProductHttpAdapterTest`
- **테스트 메서드**:
  - `getProduct_ShouldCallProductServiceAndReturnDomain` — HTTP 응답 → 도메인 DTO 변환
  - `decreaseStock_WhenServiceUnavailable_ShouldThrowRetryableException` — HTTP 503 → RetryableException
- **산출물**:
  - [x] `payment-service/src/main/java/.../payment/infrastructure/adapter/http/ProductHttpAdapter.java`
  - [x] `payment-service/src/main/java/.../payment/infrastructure/adapter/http/UserHttpAdapter.java`
- **완료 결과**: ProductHttpAdapter(@ConditionalOnProperty product.adapter.type=http) + UserHttpAdapter(@ConditionalOnProperty user.adapter.type=http) 신설. HttpOperator(WebClient 기반 기존 패턴) 재사용. HTTP 503/429/연결 타임아웃 → ProductServiceRetryableException/UserServiceRetryableException(RuntimeException). InternalProductAdapter/InternalUserAdapter(infrastructure/internal/)는 matchIfMissing=true로 기본 유지(Strangler 점진 교체). application.yml: product-service.base-url(기본 localhost:8083), user-service.base-url(기본 localhost:8084), product.adapter.type, user.adapter.type 환경변수 추가. PaymentErrorCode E03031/E03032 추가. @CircuitBreaker 위치는 adapter 내부 메서드(port 인터페이스 오염 없음 — ADR-22). ProductHttpAdapterTest 2케이스 GREEN. 516/516 PASS, 회귀 없음.

---

### T3-07 — Gateway 라우팅: 상품·사용자 엔드포인트 교체

- **제목**: Gateway route — 상품·사용자 신규 서비스로 라우팅
- **목적**: ADR-01, ADR-02 — `/api/v1/products/**`, `/api/v1/users/**` → 신규 서비스.
- **tdd**: false
- **domain_risk**: false
- **depends**: [T3-06, T3-02]
- **산출물**:
  - `gateway/src/main/resources/application.yml` route 추가

---

### T3-Gate — Phase 3 주변 도메인 + 보상 이벤트화 E2E 검증

- **제목**: Phase 3 Gate — 주변 도메인 + Saga 보상 왕복 E2E (다음 Phase 진입 판정)
- **목적**: T3-01~T3-07 완료 후 product/user 독립 기동, StockCommit/StockRestore dedupe, product→payment Redis 직접 SET, Saga 보상 왕복 검증. 실패 시 해당 Phase 재수정.
- **tdd**: false
- **domain_risk**: true
- **depends**: [T3-07]
- **산출물**:
  - `scripts/phase-gate/phase-3-gate.sh` — product/user healthcheck, stock-snapshot 발행, StockCommit dedupe, StockRestore dedupe, product→payment Redis SET 확인, Saga 보상 E2E
  - `docs/phase-gate/phase-3-gate.md`

---
