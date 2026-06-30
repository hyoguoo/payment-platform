package com.hyoguoo.paymentplatform.payment.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hyoguoo.paymentplatform.payment.application.dto.event.ConfirmedEventMessage;
import com.hyoguoo.paymentplatform.payment.application.messaging.PaymentTopics;
import com.hyoguoo.paymentplatform.payment.application.port.in.PaymentExpirationService;
import com.hyoguoo.paymentplatform.payment.application.service.PaymentReconciler;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentCommandUseCase;
import com.hyoguoo.paymentplatform.payment.core.test.BaseIntegrationTest;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentGatewayType;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentEventEntity;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentOrderEntity;
import com.hyoguoo.paymentplatform.payment.infrastructure.repository.JpaPaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.infrastructure.repository.JpaPaymentOrderRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

/**
 * confirm 결과수신 중 DB write 실패 시 정합 거동을 검증하는 통합테스트.
 *
 * <p>검증 범위:
 * <ul>
 *   <li>ConfirmedEventConsumer → PaymentConfirmResultUseCase → markPaymentAsDone DB write 실패(spy doThrow)
 *       → retry 소진(error-handler/after-rollback 경로 — 둘 다 공유 DLQ recoverer) → events.confirmed.dlq 보존(유실 0)</li>
 *   <li>DLQ 보존 후 TestClock 제어로 reconciler(IN_PROGRESS→READY) 전이 후 expiration 시도.
 *       EXPIRED 도달 불가(order EXECUTING이 expire 차단) — 실제 거동은 READY 영구 잔류 + 만료 batch poison-pill.
 *       load-bearing 단정: DLQ 증거 생존(비-silence 증거)</li>
 * </ul>
 *
 * <p>재현 메커니즘:
 * <ul>
 *   <li>DB write 실패는 {@link PaymentCommandUseCase#markPaymentAsDone} 를 spy doThrow 로 결정적 유발.
 *       컨테이너 stop / DataSource 단절은 Hikari connectionTimeout(30s×5) 으로 비결정적이라 금지.</li>
 *   <li>시간 제어는 TestClock(@Primary Clock)으로 수행. reconciler/expiration 은 scheduler.enabled=false
 *       후 명시 호출.</li>
 *   <li>throw 예외({@link CannotAcquireLockException})는 not-retryable 화이트리스트
 *       (MessageConversion/IllegalArgument/IllegalState) 밖 — retry → DLQ 경로를 탄다.</li>
 * </ul>
 *
 * <p>범위 밖 알려진 한계:
 * <ul>
 *   <li>EXPIRED 이후 자동 복구 — TQ-1(DLQ 재주입) 위임</li>
 *   <li>no-divergence(redis ≤ RDB) 단정 — 공허 단정(§18 발산 3전제 미충족)이라 제외</li>
 *   <li>신규 가시화 metric — 기존 DLQ 알람(events.confirmed.dlq)으로 탐지됨; test-only metric 발명 금지</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
@EmbeddedKafka(
        partitions = 1,
        topics = {
                PaymentTopics.EVENTS_CONFIRMED,
                PaymentTopics.EVENTS_CONFIRMED_DLQ
        },
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@Import(ConfirmedDbDownIntegrationTest.TestClockConfig.class)
@DisplayName("confirm 결과수신 DB 다운 정합 거동 통합테스트")
class ConfirmedDbDownIntegrationTest {

    private static final Long PRODUCT_ID = 100L;
    private static final int ORDER_QUANTITY = 2;
    private static final BigDecimal UNIT_AMOUNT = BigDecimal.valueOf(10000);

    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL_CONTAINER =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("payment-dbdown-test")
                    .withUsername("test")
                    .withPassword("test")
                    .withCommand(
                            "--character-set-server=utf8mb4",
                            "--collation-server=utf8mb4_unicode_ci"
                    )
                    .withReuse(true);

    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS_CONTAINER =
            new GenericContainer<>("redis:7.2-alpine")
                    .withExposedPorts(6379)
                    .withReuse(true);

    static {
        // @Testcontainers/@Container 를 사용하지 않고 수동 start.
        // @Container 로 관리하면 JUnit5 extension 이 테스트 클래스 완료 후 stop() 을 명시 호출하여
        // withReuse(true) 설정에도 불구하고 컨테이너가 종료된다.
        // 종료된 MySQL 컨테이너는 BaseIntegrationTest 컨텍스트(HikariPool) 의 연결을 끊어
        // 후속 PaymentControllerTest / PaymentCheckoutConcurrencyIntegrationTest 를 실패시킨다.
        MYSQL_CONTAINER.start();
        REDIS_CONTAINER.start();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        // MySQL — 전용 DB 명(payment-dbdown-test)으로 다른 통합테스트 컨테이너와 격리
        registry.add("spring.datasource.url", MYSQL_CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL_CONTAINER::getUsername);
        registry.add("spring.datasource.password", MYSQL_CONTAINER::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        // Flyway 활성화 — payment_event_dedupe V2 테이블 필요 (application-test.yml 에서 false)
        registry.add("spring.flyway.enabled", () -> "true");
        // Flyway 와 JPA ddl-auto 충돌 방지
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.defer-datasource-initialization", () -> "false");
        // Redis — IdempotencyStoreRedisAdapter(dedupe) + StockCacheRedisAdapter(stock) 모두 동일 컨테이너
        registry.add("spring.data.redis.host", REDIS_CONTAINER::getHost);
        registry.add("spring.data.redis.port",
                () -> String.valueOf(REDIS_CONTAINER.getMappedPort(6379)));
        registry.add("payment.cache.stock-redis.host", REDIS_CONTAINER::getHost);
        registry.add("payment.cache.stock-redis.port",
                () -> String.valueOf(REDIS_CONTAINER.getMappedPort(6379)));
        // 스케줄러 비활성화 — reconciler · expiration 은 명시 호출
        registry.add("scheduler.enabled", () -> "false");
        // KafkaListener auto-startup 활성화 (application-test.yml 에서 false)
        registry.add("spring.kafka.listener.auto-startup", () -> "true");
        // DefaultErrorHandler backoff 단축 — DLQ 도달 시간 단축 (interval 200ms × 5회 = 최소 1s)
        // 5회 retry 동작 자체는 유지 (max-attempts 변경 없음)
        registry.add("payment.kafka.error-handler.backoff.interval", () -> "200");
        registry.add("payment.kafka.error-handler.backoff.max-attempts", () -> "5");
    }

    /**
     * TestClock 을 @Primary Clock 빈으로 등록해 application 레이어의 시각 의존을 테스트에서 제어한다.
     * reconciler(cutoff = clock.instant() - 300s) · expiration(cutoff = clock.instant() - 30min) 의
     * IN_FLIGHT / READY 타임아웃 계산이 이 빈의 instant() 를 기준으로 동작한다.
     */
    @TestConfiguration
    static class TestClockConfig {

        @Bean
        @Primary
        public BaseIntegrationTest.TestClock clock() {
            return new BaseIntegrationTest.TestClock();
        }
    }

    /** DB write 실패를 결정적으로 유발하기 위해 spy 로 감싼다. */
    @MockitoSpyBean
    private PaymentCommandUseCase paymentCommandUseCase;

    @Autowired
    private KafkaTemplate<String, String> confirmedDlqKafkaTemplate;

    @Autowired
    private JpaPaymentEventRepository jpaPaymentEventRepository;

    @Autowired
    private JpaPaymentOrderRepository jpaPaymentOrderRepository;

    @Autowired
    private NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    @Autowired
    private PaymentReconciler paymentReconciler;

    @Autowired
    private PaymentExpirationService paymentExpirationService;

    /**
     * TestClockConfig 에서 등록한 TestClock 빈을 직접 주입해 시각 제어에 사용한다.
     * Clock 상위 타입이 아닌 TestClock 타입으로 주입해 타입 안전한 setFixedInstant / reset 호출을 보장한다.
     */
    @Autowired
    private BaseIntegrationTest.TestClock testClock;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @Autowired
    private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private String bootstrapServers;

    @BeforeEach
    void setUp() {
        // cold-start 방어: consumer group join + partition assignment 가 완료되도록 대기.
        // 이로써 첫 번째 시나리오에서 메시지 발행 직후 consumer 가 준비 완료된다.
        for (MessageListenerContainer container : kafkaListenerEndpointRegistry.getListenerContainers()) {
            await().atMost(Duration.ofSeconds(30))
                    .until(() -> container.getAssignedPartitions() != null
                            && !container.getAssignedPartitions().isEmpty());
        }
        jpaPaymentOrderRepository.deleteAllInBatch();
        jpaPaymentEventRepository.deleteAllInBatch();
        namedParameterJdbcTemplate.update("DELETE FROM payment_event_dedupe", Collections.emptyMap());
        testClock.reset();
        bootstrapServers = embeddedKafkaBroker.getBrokersAsString();
    }

    @AfterEach
    void tearDown() {
        jpaPaymentOrderRepository.deleteAllInBatch();
        jpaPaymentEventRepository.deleteAllInBatch();
        namedParameterJdbcTemplate.update("DELETE FROM payment_event_dedupe", Collections.emptyMap());
        testClock.reset();
    }

    // ── 시나리오 1 ─────────────────────────────────────────────────────────────

    /**
     * confirm 결과수신 중 DB write 실패 → DLQ 유실 0 검증.
     *
     * <p>markPaymentAsDone spy doThrow 로 결정적 DB write 실패 주입 → DefaultErrorHandler 1s×5 retry
     * 소진 → events.confirmed.dlq 1건 보존(유실 0) 단정.
     * 시간 무관 단정: 메시지 존재 여부만 확인(retry 소진 후 DLQ 도달을 awaitility 없이 직접 폴링).
     */
    @Test
    @DisplayName("DB 다운 결과수신 중 events.confirmed.dlq 유실 0: markPaymentAsDone 실패 → retry 소진 → DLQ 보존")
    void DB다운_결과수신중_events_confirmed_dlq_유실0() throws Exception {
        // given — IN_PROGRESS 결제 + markPaymentAsDone DB write 결정적 실패 주입
        String orderId = "order-dbdown1-" + UUID.randomUUID();
        savePaymentInProgress(orderId);

        // CannotAcquireLockException: not-retryable 화이트리스트(MessageConversion/IllegalArgument/
        // IllegalState) 밖 → DefaultErrorHandler 가 retry → DLQ 경로를 탄다
        doThrow(new CannotAcquireLockException("DB 잠금 획득 실패 — DB 다운 시뮬레이션"))
                .when(paymentCommandUseCase).markPaymentAsDone(any(PaymentEvent.class), any(Instant.class));

        ConfirmedEventMessage message = approvedMessage(orderId, UNIT_AMOUNT.longValue());
        String payload = objectMapper.writeValueAsString(message);

        // when — events.confirmed 에 APPROVED 메시지 발행
        confirmedDlqKafkaTemplate.send(PaymentTopics.EVENTS_CONFIRMED, orderId, payload);

        // then — DefaultErrorHandler 200ms×5 retry 소진 후 DLQ 에 메시지 보존(유실 0)
        List<String> dlqPayloads = pollConfirmedDlq(orderId, Duration.ofSeconds(30));
        assertThat(dlqPayloads)
                .as("events.confirmed.dlq 에 1건 보존 — DB write 실패 시 유실 없음(기존 DLQ 알람으로 탐지)")
                .hasSize(1);
    }

    // ── 시나리오 2 ─────────────────────────────────────────────────────────────

    /**
     * DLQ 보존 후 전이 시도를 가로질러 DLQ 증거 생존.
     *
     * <p>DLQ 보존 상태에서 TestClock 제어로 reconciler(IN_PROGRESS→READY) 전이 후
     * expireOldReadyPayments() 를 명시 호출한다.
     *
     * <p>실제 거동:
     * <ul>
     *   <li>Reconciler 복원 후 PaymentEvent.status=READY, PaymentOrder.status=EXECUTING(불변식 흠) — 둘 다 단정.</li>
     *   <li>EXPIRED 도달 불가(order EXECUTING이 expire 차단): 이 stranded 건은 만료에 실패하지만,
     *       배치가 건별 독립 트랜잭션 + 실패 격리라 예외를 전파하지 않는다(L-14 poison-pill 격리).</li>
     *   <li>만료 실패 후 재조회 시 event.status 는 여전히 READY(자동 복구 미수행 — TQ-1/TC-3 위임, stranded READY 잔류).</li>
     * </ul>
     *
     * <p>load-bearing 단정: 위 전이 시도를 가로질러 events.confirmed.dlq 1건 보존(DLQ 비-silence 증거).
     *
     * <p>findInProgressOlderThan 은 executedAt 기준으로 IN_PROGRESS 레코드를 찾는다.
     * findReadyPaymentsOlderThan 은 created_at 기준으로 READY 레코드를 찾는다.
     * 두 쿼리가 TestClock 기반 cutoff 를 통해 기대대로 동작하는지도 함께 검증된다.
     */
    @Test
    @DisplayName("stranded 만료 실패 격리를 가로질러 DLQ 증거 생존: order EXECUTING 건 만료 실패 격리(poison-pill 차단) + DLQ 유실 0")
    void 마스킹전이를_가로질러_DLQ증거_생존() throws Exception {
        // given — IN_PROGRESS 결제 + markPaymentAsDone DB write 결정적 실패 주입 → DLQ 보존
        String orderId = "order-dbdown2-" + UUID.randomUUID();
        Instant paymentSavedAt = Instant.now();
        savePaymentInProgress(orderId);

        doThrow(new CannotAcquireLockException("DB 잠금 획득 실패 — DB 다운 시뮬레이션"))
                .when(paymentCommandUseCase).markPaymentAsDone(any(PaymentEvent.class), any(Instant.class));

        ConfirmedEventMessage message = approvedMessage(orderId, UNIT_AMOUNT.longValue());
        String payload = objectMapper.writeValueAsString(message);
        confirmedDlqKafkaTemplate.send(PaymentTopics.EVENTS_CONFIRMED, orderId, payload);

        // DLQ 선행 보존 확인
        List<String> dlqBeforeMasking = pollConfirmedDlq(orderId, Duration.ofSeconds(30));
        assertThat(dlqBeforeMasking)
                .as("DLQ 선행 보존 확인 — 마스킹 전이 기점")
                .hasSize(1);

        // 결제가 IN_PROGRESS 유지 중임을 확인 (retry 소진 후 상태 불변)
        PaymentEventEntity entityAfterDlq = jpaPaymentEventRepository.findByOrderId(orderId).orElseThrow();
        assertThat(entityAfterDlq.getStatus())
                .as("DB write 실패 후 상태 불변(IN_PROGRESS)")
                .isEqualTo(PaymentEventStatus.IN_PROGRESS);

        // when (1단계) — Reconciler: TestClock 을 paymentSavedAt + 310s 로 전진.
        // cutoff = clock.instant() - 300s = paymentSavedAt + 10s.
        // executedAt ≈ paymentSavedAt < cutoff → findInProgressOlderThan 에 해당 → IN_PROGRESS → READY 전이.
        testClock.setFixedInstant(paymentSavedAt.plus(Duration.ofSeconds(310)));
        paymentReconciler.scan();

        PaymentEventEntity entityAfterReconcile = jpaPaymentEventRepository.findByOrderId(orderId)
                .orElseThrow();
        assertThat(entityAfterReconcile.getStatus())
                .as("Reconciler 명시 호출 후 IN_PROGRESS→READY 전이 확인")
                .isEqualTo(PaymentEventStatus.READY);
        // 불변식 흠: PaymentEvent.resetToReady()는 PaymentOrder 상태를 복원하지 않는다.
        // PaymentOrder 는 EXECUTING 그대로 — 이 건은 만료에 실패하지만 배치는 격리되어 계속 진행한다.
        List<PaymentOrderEntity> ordersAfterReconcile =
                jpaPaymentOrderRepository.findByPaymentEventId(entityAfterReconcile.getId());
        assertThat(ordersAfterReconcile)
                .as("Reconciler 후 PaymentOrder 상태는 EXECUTING 그대로(resetToNotStarted 없음)")
                .isNotEmpty()
                .allMatch(o -> o.getStatus() == PaymentOrderStatus.EXECUTING);

        // when (2단계) — Expiration: TestClock 을 추가 31분 전진.
        // cutoff = clock.instant() - 30min = paymentSavedAt + 310s + 1min.
        // created_at ≈ paymentSavedAt < cutoff → findReadyPaymentsOlderThan 에 해당 → expire 시도.
        // PaymentOrder.status=EXECUTING 이 PaymentOrder.expire() 가드를 막아 이 stranded 건은 만료에
        // 실패하지만, 배치가 건별 독립 트랜잭션 + 실패 격리라 예외를 전파하지 않는다(L-14 poison-pill 격리).
        testClock.setFixedInstant(
                paymentSavedAt.plus(Duration.ofSeconds(310)).plus(Duration.ofMinutes(31))
        );
        assertThatCode(() -> paymentExpirationService.expireOldReadyPayments())
                .as("stranded 건 만료 실패가 격리되어 배치 예외 전파 없음 (poison-pill 격리)")
                .doesNotThrowAnyException();

        // 이 건은 만료되지 못하고 READY 잔류 — 자동 복구 미수행(TQ-1/TC-3 위임), 비종결 READY 가 안전 방향.
        PaymentEventEntity entityAfterExpireAttempt =
                jpaPaymentEventRepository.findByOrderId(orderId).orElseThrow();
        assertThat(entityAfterExpireAttempt.getStatus())
                .as("stranded 건은 만료 실패로 READY 잔류 (자동 복구 미수행 — 위임)")
                .isEqualTo(PaymentEventStatus.READY);

        // then — 마스킹 전이 시도를 가로질러 DLQ 메시지 유실 0 보존(비-silence 증거).
        // load-bearing 단정: 상태 전이 실패 여부와 무관하게 DLQ 증거가 생존한다.
        List<String> dlqAfterMasking = pollConfirmedDlq(orderId, Duration.ofSeconds(5));
        assertThat(dlqAfterMasking)
                .as("전이 시도 후에도 events.confirmed.dlq 메시지 유실 0 — silent하지 않음 증거")
                .hasSize(1);
    }

    // ── 픽스처 헬퍼 ─────────────────────────────────────────────────────────────

    /**
     * IN_PROGRESS 상태 PaymentEvent + PaymentOrder 1건 저장.
     *
     * <p>executedAt 를 명시 설정하는 이유:
     * findInProgressOlderThan 쿼리는 executedAt < :before 조건을 사용하며,
     * executedAt 이 null 이면 MySQL 에서 NULL 비교 결과가 falsy 라 Reconciler 에 감지되지 않는다.
     * 실 결제 흐름에서 IN_PROGRESS 전이 시 executedAt 이 반드시 설정되므로 이를 재현한다.
     */
    private void savePaymentInProgress(String orderId) {
        Instant now = Instant.now();
        PaymentEventEntity event = PaymentEventEntity.builder()
                .buyerId(1L)
                .sellerId(2L)
                .orderName("DB 다운 테스트 상품 — " + orderId)
                .orderId(orderId)
                .paymentKey("pay-key-" + orderId)
                .gatewayType(PaymentGatewayType.TOSS)
                .status(PaymentEventStatus.IN_PROGRESS)
                .executedAt(now)
                .lastStatusChangedAt(now)
                .build();
        PaymentEventEntity savedEvent = jpaPaymentEventRepository.save(event);

        PaymentOrderEntity order = PaymentOrderEntity.builder()
                .paymentEventId(savedEvent.getId())
                .orderId(orderId)
                .productId(PRODUCT_ID)
                .quantity(ORDER_QUANTITY)
                .totalAmount(UNIT_AMOUNT)
                .status(PaymentOrderStatus.EXECUTING)
                .build();
        jpaPaymentOrderRepository.save(order);
    }

    /**
     * APPROVED ConfirmedEventMessage 생성.
     * approvedAt 은 현재 시각 ISO-8601 OffsetDateTime 문자열.
     */
    private static ConfirmedEventMessage approvedMessage(String orderId, Long amount) {
        String approvedAt = OffsetDateTime.now(ZoneOffset.UTC).toString();
        String eventUuid = UUID.randomUUID().toString();
        return new ConfirmedEventMessage(orderId, "APPROVED", null, amount, approvedAt, eventUuid);
    }

    /**
     * payment.events.confirmed.dlq 토픽을 폴링해 지정 orderId 에 해당하는 원본 페이로드 목록을 반환한다.
     *
     * <p>DLQ 는 EOS 외부(confirmedDlqKafkaTemplate 비트랜잭션)이므로 isolation level 설정 불요.
     * orderId 를 메시지 키로 필터링해 동일 클래스 내 다른 시나리오의 DLQ 메시지와 격리한다.
     * 각 테스트마다 고유한 group-id 를 사용해 offset 충돌을 방지한다.
     *
     * @param filterOrderId 필터링할 orderId (각 테스트마다 unique)
     * @param timeout       폴링 대기 최대 시간
     */
    private List<String> pollConfirmedDlq(String filterOrderId, Duration timeout) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-dbdown-dlq-reader-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        List<String> result = new ArrayList<>();

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList(PaymentTopics.EVENTS_CONFIRMED_DLQ));
            long deadline = System.currentTimeMillis() + timeout.toMillis();

            while (System.currentTimeMillis() < deadline && result.isEmpty()) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                records.forEach(record -> {
                    if (filterOrderId.equals(record.key())) {
                        result.add(record.value());
                    }
                });
            }
        }

        return result;
    }
}
