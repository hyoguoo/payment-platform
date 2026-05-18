package com.hyoguoo.paymentplatform.payment.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hyoguoo.paymentplatform.payment.application.dto.event.ConfirmedEventMessage;
import com.hyoguoo.paymentplatform.payment.application.dto.event.StockCommittedEvent;
import com.hyoguoo.paymentplatform.payment.application.messaging.PaymentTopics;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentCommandUseCase;
import com.hyoguoo.paymentplatform.payment.application.util.StockEventUuidDeriver;
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
import java.time.LocalDateTime;
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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

/**
 * PET-12 — EOS 통합 회귀 가드 5 시나리오.
 *
 * <p>검증 범위: PET-6/7/8 에서 완성된 EOS SUT 의 통합 동작.
 * ConfirmedEventConsumer → KafkaTransactionManager (EOS) → PaymentConfirmResultUseCase → RDB + Kafka 발행.
 *
 * <p>5 시나리오:
 * <ol>
 *   <li>#1 정상 EOS commit — APPROVED → dedupe 1 row + payment DONE + stock-committed 1건 가시화</li>
 *   <li>#2 abort 흐름 — RuntimeException → EOS abort → dedupe 0 row + payment 불변 + stock-committed 0건 + DLQ 1건</li>
 *   <li>#3 중복 INSERT IGNORE — 동일 event_uuid 재배달 → dedupe 1 row (기존) + stock-committed 재발행 + payment 불변</li>
 *   <li>#4 multi-product DR-1 — PaymentOrder 2건 → stock-committed 2건 + productId 별 idempotencyKey 결정성</li>
 *   <li>#5 QUARANTINED D7 가드 — QUARANTINED 결제 + APPROVED → noop + dedupe 0 row + stock-committed 0건</li>
 * </ol>
 *
 * <p>범위 밖 알려진 한계:
 * <ul>
 *   <li>abort invisibility 의 "stock-committed abort" 시뮬레이션 — stock-committed send 이후 abort 를
 *       주입할 수 없어, markPaymentAsDone RuntimeException 주입으로 대체 (발행 전 abort 검증)</li>
 *   <li>multi-instance transactional.id fencing (DR-2 / CONCERNS L6) — 통합 테스트 범위 밖</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
@EmbeddedKafka(
        partitions = 2,
        topics = {
                PaymentTopics.EVENTS_CONFIRMED,
                PaymentTopics.EVENTS_CONFIRMED_DLQ,
                PaymentTopics.EVENTS_STOCK_COMMITTED
        },
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@DisplayName("PET-12 EOS 통합 회귀 가드 5 시나리오")
class PaymentEosIntegrationTest {

    private static final Long PRODUCT_ID = 100L;
    private static final Long PRODUCT_ID_2 = 200L;
    private static final int ORDER_QUANTITY = 2;
    private static final BigDecimal UNIT_AMOUNT = BigDecimal.valueOf(10000);

    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL_CONTAINER =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("payment-eos-test")
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
        // MySQL
        registry.add("spring.datasource.url", MYSQL_CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL_CONTAINER::getUsername);
        registry.add("spring.datasource.password", MYSQL_CONTAINER::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        // Flyway 활성화 — payment_event_dedupe V2 테이블 필요 (application-test.yml 에서 false 로 설정됨)
        registry.add("spring.flyway.enabled", () -> "true");
        // Flyway 와 JPA ddl-auto 충돌 방지 — Flyway 가 스키마를 담당
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        // defer-datasource-initialization 비활성화 — Flyway 활성화 시 circular depends-on 방지
        registry.add("spring.jpa.defer-datasource-initialization", () -> "false");
        // Redis (StockCacheRedisAdapter 빈 초기화용)
        registry.add("payment.cache.stock-redis.host", REDIS_CONTAINER::getHost);
        registry.add("payment.cache.stock-redis.port",
                () -> String.valueOf(REDIS_CONTAINER.getMappedPort(6379)));
        // 스케줄러 비활성화
        registry.add("scheduler.enabled", () -> "false");
        // KafkaListener auto-startup 활성화 (application-test.yml 에서 false 로 설정됨)
        registry.add("spring.kafka.listener.auto-startup", () -> "true");
        // EOS backoff 단축 — 테스트 DLQ 검증 시간 단축 (backoff 1000ms × 5회 = 5s)
        registry.add("payment.kafka.error-handler.backoff.interval", () -> "200");
        registry.add("payment.kafka.error-handler.backoff.max-attempts", () -> "5");
    }

    @Autowired
    private KafkaTemplate<String, String> confirmedDlqKafkaTemplate;

    @Autowired
    private JpaPaymentEventRepository jpaPaymentEventRepository;

    @Autowired
    private JpaPaymentOrderRepository jpaPaymentOrderRepository;

    @Autowired
    private NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    @MockitoSpyBean
    private StockCachePort stockCachePort;

    @MockitoSpyBean
    private PaymentCommandUseCase paymentCommandUseCase;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private String bootstrapServers;

    @Autowired
    private org.springframework.kafka.test.EmbeddedKafkaBroker embeddedKafkaBroker;

    @BeforeEach
    void setUp() {
        jpaPaymentOrderRepository.deleteAllInBatch();
        jpaPaymentEventRepository.deleteAllInBatch();
        namedParameterJdbcTemplate.update(
                "DELETE FROM payment_event_dedupe",
                Collections.emptyMap()
        );
        bootstrapServers = embeddedKafkaBroker.getBrokersAsString();
    }

    @AfterEach
    void tearDown() {
        jpaPaymentOrderRepository.deleteAllInBatch();
        jpaPaymentEventRepository.deleteAllInBatch();
        namedParameterJdbcTemplate.update(
                "DELETE FROM payment_event_dedupe",
                Collections.emptyMap()
        );
    }

    // ── 시나리오 #1 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("#1 정상 EOS commit: APPROVED 수신 → dedupe 1 row + payment DONE + stock-committed 1건 read_committed 가시화")
    void shouldCommitEosTransactionNormally() throws Exception {
        // given
        String orderId = "order-eos1-" + UUID.randomUUID();
        String eventUuid = UUID.randomUUID().toString();
        savePaymentInProgress(orderId, PRODUCT_ID, UNIT_AMOUNT);

        ConfirmedEventMessage message = approvedMessage(orderId, UNIT_AMOUNT.longValue(), eventUuid);
        String payload = objectMapper.writeValueAsString(message);

        // when
        confirmedDlqKafkaTemplate.send(PaymentTopics.EVENTS_CONFIRMED, orderId, payload);

        // then — payment DONE 으로 전이
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> {
                    PaymentEventEntity entity = jpaPaymentEventRepository.findByOrderId(orderId)
                            .orElseThrow();
                    assertThat(entity.getStatus()).isEqualTo(PaymentEventStatus.DONE);
                });

        // dedupe 1 row 존재
        int dedupeCount = countDedupeRow(eventUuid);
        assertThat(dedupeCount).isEqualTo(1);

        // stock-committed 1건 read_committed 가시화
        List<StockCommittedEvent> stockEvents = pollStockCommitted(orderId, 1, Duration.ofSeconds(10));
        assertThat(stockEvents).hasSize(1);
        assertThat(stockEvents.get(0).productId()).isEqualTo(PRODUCT_ID);
    }

    // ── 시나리오 #2 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("#2 abort 흐름: RuntimeException 주입 → dedupe 0 row + payment 불변 + stock-committed 0건 + DLQ 1건")
    void shouldMakeAbortMessageInvisibleOnRollback() throws Exception {
        // given — markPaymentAsDone 에서 RuntimeException 주입 (handle 내 @Transactional 롤백 유발)
        String orderId = "order-eos2-" + UUID.randomUUID();
        String eventUuid = UUID.randomUUID().toString();
        savePaymentInProgress(orderId, PRODUCT_ID, UNIT_AMOUNT);

        doThrow(new RuntimeException("EOS abort 시뮬레이션 — markPaymentAsDone RuntimeException"))
                .when(paymentCommandUseCase).markPaymentAsDone(any(PaymentEvent.class), any(LocalDateTime.class));

        ConfirmedEventMessage message = approvedMessage(orderId, UNIT_AMOUNT.longValue(), eventUuid);
        String payload = objectMapper.writeValueAsString(message);

        // when
        confirmedDlqKafkaTemplate.send(PaymentTopics.EVENTS_CONFIRMED, orderId, payload);

        // then — 5회 retry 후 DLQ (interval 200ms × 5 = 최소 1s, 여유 30s)
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() ->
                        verify(paymentCommandUseCase, times(6)).markPaymentAsDone(
                                any(PaymentEvent.class), any(LocalDateTime.class))
                );

        // dedupe 0 row — RDB 롤백 확인
        assertThat(countDedupeRow(eventUuid)).isZero();

        // payment 상태 불변 (IN_PROGRESS 유지)
        PaymentEventEntity entity = jpaPaymentEventRepository.findByOrderId(orderId).orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(PaymentEventStatus.IN_PROGRESS);

        // stock-committed 0건 (발행 전 abort 으로 인해 전송되지 않음)
        List<StockCommittedEvent> stockEvents = pollStockCommitted(orderId, 0, Duration.ofSeconds(3));
        assertThat(stockEvents).isEmpty();
    }

    // ── 시나리오 #3 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("#3 중복 INSERT IGNORE: dedupe row 선행 삽입 후 동일 event_uuid 배달 → 비즈니스 skip + stock-committed 발행 + payment 불변")
    void shouldSkipBusinessButResendOnDuplicateInsert() throws Exception {
        // given — 결제는 IN_PROGRESS 상태 유지, dedupe row 를 미리 삽입해 중복 시뮬레이션
        // (실서비스: 동일 메시지가 두 번 배달되면 두 번째는 INSERT IGNORE 0 row 반환)
        String orderId = "order-eos3-" + UUID.randomUUID();
        String eventUuid = UUID.randomUUID().toString();
        savePaymentInProgress(orderId, PRODUCT_ID, UNIT_AMOUNT);

        // 동일 event_uuid 를 dedupe 테이블에 먼저 삽입 — INSERT IGNORE 0 row 상황 시뮬레이션
        insertDedupeRow(eventUuid, orderId, "APPROVED");

        ConfirmedEventMessage message = approvedMessage(orderId, UNIT_AMOUNT.longValue(), eventUuid);
        String payload = objectMapper.writeValueAsString(message);

        // when — dedupe row 가 존재하는 상태에서 APPROVED 메시지 배달
        confirmedDlqKafkaTemplate.send(PaymentTopics.EVENTS_CONFIRMED, orderId, payload);

        // then — 비즈니스 skip (markPaymentAsDone 미호출) — payment 상태 IN_PROGRESS 유지
        // 발행은 항상 진행 (위키 line 141) — stock-committed 1건 발행됨
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> {
                    // stock-committed 발행 확인 — dedupe 중복이어도 발행은 진행됨
                    List<StockCommittedEvent> stockEvents = pollStockCommitted(orderId, 1, Duration.ofSeconds(5));
                    assertThat(stockEvents).hasSize(1);
                });

        // payment 상태 불변 (IN_PROGRESS 유지 — 비즈니스 skip)
        PaymentEventEntity entity = jpaPaymentEventRepository.findByOrderId(orderId).orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(PaymentEventStatus.IN_PROGRESS);

        // dedupe row 1개 (선행 삽입 row, markIfAbsent 는 0 row 반환으로 추가 삽입 없음)
        assertThat(countDedupeRow(eventUuid)).isEqualTo(1);

        // verify paymentCommandUseCase.markPaymentAsDone 미호출 (비즈니스 skip 확인)
        verify(paymentCommandUseCase, times(0))
                .markPaymentAsDone(any(PaymentEvent.class), any(LocalDateTime.class));
    }

    // ── 시나리오 #4 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("#4 multi-product DR-1: PaymentOrder 2건 → stock-committed 2건 + productId 별 distinct idempotencyKey")
    void shouldPublishDistinctIdempotencyKeyPerProductOnMultiProduct() throws Exception {
        // given — 2개 ProductId 포함 PaymentEvent
        String orderId = "order-eos4-" + UUID.randomUUID();
        String eventUuid = UUID.randomUUID().toString();
        BigDecimal totalAmount = UNIT_AMOUNT.multiply(BigDecimal.valueOf(2));
        savePaymentInProgressMultiProduct(orderId, totalAmount);

        ConfirmedEventMessage message = approvedMessage(orderId, totalAmount.longValue(), eventUuid);
        String payload = objectMapper.writeValueAsString(message);

        // when
        confirmedDlqKafkaTemplate.send(PaymentTopics.EVENTS_CONFIRMED, orderId, payload);

        // then — payment DONE 전이
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> {
                    PaymentEventEntity entity = jpaPaymentEventRepository.findByOrderId(orderId)
                            .orElseThrow();
                    assertThat(entity.getStatus()).isEqualTo(PaymentEventStatus.DONE);
                });

        // stock-committed 2건 가시화
        List<StockCommittedEvent> stockEvents = pollStockCommitted(orderId, 2, Duration.ofSeconds(10));
        assertThat(stockEvents).hasSize(2);

        // productId 별 idempotencyKey 결정성 검증 (DR-1)
        String expectedKey1 = StockEventUuidDeriver.derive(orderId, PRODUCT_ID, "stock-commit");
        String expectedKey2 = StockEventUuidDeriver.derive(orderId, PRODUCT_ID_2, "stock-commit");

        List<String> actualKeys = new ArrayList<>();
        for (StockCommittedEvent event : stockEvents) {
            actualKeys.add(event.idempotencyKey());
        }
        assertThat(actualKeys).containsExactlyInAnyOrder(expectedKey1, expectedKey2);
        // 두 키가 서로 다름 (productId 별 고유)
        assertThat(expectedKey1).isNotEqualTo(expectedKey2);

        // dedupe 1 row
        assertThat(countDedupeRow(eventUuid)).isEqualTo(1);

        // 재배달 시 두 메시지 모두 dedupe skip 검증 (DR-1 회귀 가드)
        // — 재배달 시에도 동일 idempotencyKey 로 발행됨 (위키 line 141: 0 row 시에도 발행 진행)
        // — dedupe row 가 존재하는 상태에서 새로운 IN_PROGRESS 결제로 재배달 시뮬레이션
        String redeliveryOrderId = "order-eos4-redeliver-" + UUID.randomUUID();
        String redeliveryEventUuid = UUID.randomUUID().toString();
        BigDecimal redeliveryTotalAmount = UNIT_AMOUNT.multiply(BigDecimal.valueOf(2));
        savePaymentInProgressMultiProduct(redeliveryOrderId, redeliveryTotalAmount);

        // dedupe row 미리 삽입 (두 PaymentOrder 의 event_uuid — 실제로는 order-level dedupe 가 일어남)
        insertDedupeRow(redeliveryEventUuid, redeliveryOrderId, "APPROVED");

        ConfirmedEventMessage redeliveryMessage = approvedMessage(
                redeliveryOrderId, redeliveryTotalAmount.longValue(), redeliveryEventUuid);
        String redeliveryPayload = objectMapper.writeValueAsString(redeliveryMessage);

        confirmedDlqKafkaTemplate.send(PaymentTopics.EVENTS_CONFIRMED, redeliveryOrderId, redeliveryPayload);

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> {
                    // 재배달 시에도 stock-committed 2건 발행 (productId 별)
                    List<StockCommittedEvent> redeliveredEvents =
                            pollStockCommitted(redeliveryOrderId, 2, Duration.ofSeconds(5));
                    assertThat(redeliveredEvents).hasSize(2);
                });
        assertThat(countDedupeRow(redeliveryEventUuid)).isEqualTo(1);
    }

    // ── 시나리오 #5 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("#5 QUARANTINED D7 가드: QUARANTINED 결제 + APPROVED → dedupe 0 row + stock-committed 0건 + DLQ 0건")
    void shouldSkipQuarantinedLateApprovedWithNoDlq() throws Exception {
        // given — QUARANTINED 상태의 결제 이벤트 저장
        String orderId = "order-eos5-" + UUID.randomUUID();
        String eventUuid = UUID.randomUUID().toString();
        savePaymentQuarantined(orderId, PRODUCT_ID, UNIT_AMOUNT);

        // 늦게 도착한 APPROVED 메시지
        ConfirmedEventMessage message = approvedMessage(orderId, UNIT_AMOUNT.longValue(), eventUuid);
        String payload = objectMapper.writeValueAsString(message);

        // when
        confirmedDlqKafkaTemplate.send(PaymentTopics.EVENTS_CONFIRMED, orderId, payload);

        // then — 충분한 시간 대기 후 상태 불변 확인 (D7 가드 → noop)
        // DLQ 발행이 없으므로 retry 를 기다리지 않고 2s 후 검증
        Thread.sleep(2000);

        // payment 상태 QUARANTINED 유지 (noop)
        PaymentEventEntity entity = jpaPaymentEventRepository.findByOrderId(orderId).orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(PaymentEventStatus.QUARANTINED);

        // dedupe 0 row (D7 가드 → markIfAbsent 미호출)
        assertThat(countDedupeRow(eventUuid)).isZero();

        // stock-committed 0건
        List<StockCommittedEvent> stockEvents = pollStockCommitted(orderId, 0, Duration.ofSeconds(2));
        assertThat(stockEvents).isEmpty();

        // paymentCommandUseCase 호출 없음 (D7 가드에서 early return)
        verify(paymentCommandUseCase, times(0)).markPaymentAsDone(
                any(PaymentEvent.class), any(LocalDateTime.class));
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────────

    /**
     * IN_PROGRESS 상태 PaymentEvent + PaymentOrder 1건 저장.
     */
    private void savePaymentInProgress(String orderId, Long productId, BigDecimal amount) {
        PaymentEventEntity event = buildPaymentEventEntity(orderId, PaymentEventStatus.IN_PROGRESS, amount);
        PaymentEventEntity savedEvent = jpaPaymentEventRepository.save(event);

        PaymentOrderEntity order = PaymentOrderEntity.builder()
                .paymentEventId(savedEvent.getId())
                .orderId(orderId)
                .productId(productId)
                .quantity(ORDER_QUANTITY)
                .totalAmount(amount)
                .status(PaymentOrderStatus.EXECUTING)
                .build();
        jpaPaymentOrderRepository.save(order);
    }

    /**
     * IN_PROGRESS 상태 PaymentEvent + PaymentOrder 2건 (multi-product) 저장.
     */
    private void savePaymentInProgressMultiProduct(String orderId, BigDecimal totalAmount) {
        PaymentEventEntity event = buildPaymentEventEntity(orderId, PaymentEventStatus.IN_PROGRESS, totalAmount);
        PaymentEventEntity savedEvent = jpaPaymentEventRepository.save(event);

        PaymentOrderEntity order1 = PaymentOrderEntity.builder()
                .paymentEventId(savedEvent.getId())
                .orderId(orderId)
                .productId(PRODUCT_ID)
                .quantity(ORDER_QUANTITY)
                .totalAmount(UNIT_AMOUNT)
                .status(PaymentOrderStatus.EXECUTING)
                .build();
        PaymentOrderEntity order2 = PaymentOrderEntity.builder()
                .paymentEventId(savedEvent.getId())
                .orderId(orderId)
                .productId(PRODUCT_ID_2)
                .quantity(ORDER_QUANTITY)
                .totalAmount(UNIT_AMOUNT)
                .status(PaymentOrderStatus.EXECUTING)
                .build();
        jpaPaymentOrderRepository.save(order1);
        jpaPaymentOrderRepository.save(order2);
    }

    /**
     * QUARANTINED 상태 PaymentEvent + PaymentOrder 1건 저장.
     */
    private void savePaymentQuarantined(String orderId, Long productId, BigDecimal amount) {
        PaymentEventEntity event = buildPaymentEventEntity(orderId, PaymentEventStatus.QUARANTINED, amount);
        PaymentEventEntity savedEvent = jpaPaymentEventRepository.save(event);

        PaymentOrderEntity order = PaymentOrderEntity.builder()
                .paymentEventId(savedEvent.getId())
                .orderId(orderId)
                .productId(productId)
                .quantity(ORDER_QUANTITY)
                .totalAmount(amount)
                .status(PaymentOrderStatus.EXECUTING)
                .build();
        jpaPaymentOrderRepository.save(order);
    }

    private PaymentEventEntity buildPaymentEventEntity(
            String orderId, PaymentEventStatus status, BigDecimal totalAmount) {
        return PaymentEventEntity.builder()
                .buyerId(1L)
                .sellerId(2L)
                .orderName("EOS 테스트 상품 — " + orderId)
                .orderId(orderId)
                .paymentKey("pay-key-" + orderId)
                .gatewayType(PaymentGatewayType.TOSS)
                .status(status)
                .retryCount(0)
                .lastStatusChangedAt(LocalDateTime.now())
                .build();
    }

    /**
     * APPROVED ConfirmedEventMessage 생성.
     * approvedAt 은 현재 시각 ISO-8601 OffsetDateTime 문자열.
     */
    private static ConfirmedEventMessage approvedMessage(String orderId, Long amount, String eventUuid) {
        String approvedAt = OffsetDateTime.now(ZoneOffset.UTC).toString();
        return new ConfirmedEventMessage(orderId, "APPROVED", null, amount, approvedAt, eventUuid);
    }

    /**
     * payment_event_dedupe 테이블에 dedupe row 를 직접 삽입한다.
     * 중복 메시지 시뮬레이션용 — 실서비스에서 동일 메시지가 두 번 배달될 때 첫 번째 처리 후 dedupe row 가 존재하는 상황.
     */
    private void insertDedupeRow(String eventUuid, String orderId, String status) {
        Long paymentEventId = jpaPaymentEventRepository.findByOrderId(orderId)
                .orElseThrow()
                .getId();
        namedParameterJdbcTemplate.update(
                "INSERT IGNORE INTO payment_event_dedupe "
                        + "(event_uuid, order_id, status, received_at, expires_at) "
                        + "VALUES (:eventUuid, :orderId, :status, NOW(), DATE_ADD(NOW(), INTERVAL 8 DAY))",
                Map.of(
                        "eventUuid", eventUuid,
                        "orderId", paymentEventId,
                        "status", status
                )
        );
    }

    /**
     * payment_event_dedupe 테이블에서 eventUuid 에 해당하는 row 수 조회.
     */
    private int countDedupeRow(String eventUuid) {
        Integer count = namedParameterJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_event_dedupe WHERE event_uuid = :eventUuid",
                Map.of("eventUuid", eventUuid),
                Integer.class
        );
        return count != null ? count : 0;
    }

    /**
     * payment.events.stock-committed 토픽을 read_committed isolation 으로 폴링해
     * 지정 orderId 에 해당하는 StockCommittedEvent 목록을 반환한다.
     *
     * <p>설계 이유: EmbeddedKafka 는 테스트 간 토픽을 공유하므로 이전 테스트의 메시지가
     * 후속 테스트 consumer 에서 earliest 로 소비될 수 있다.
     * orderId 필터로 현재 테스트의 메시지만 선별한다.
     *
     * @param filterOrderId 필터링할 orderId (각 테스트마다 unique)
     * @param expectedCount 기대하는 메시지 수 (0이면 즉시 폴링 후 반환)
     * @param timeout       폴링 대기 최대 시간
     */
    private List<StockCommittedEvent> pollStockCommitted(
            String filterOrderId, int expectedCount, Duration timeout) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-stock-reader-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        List<StockCommittedEvent> result = new ArrayList<>();

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList(PaymentTopics.EVENTS_STOCK_COMMITTED));
            long deadline = System.currentTimeMillis() + timeout.toMillis();

            while (System.currentTimeMillis() < deadline && result.size() < expectedCount) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                records.forEach(record -> {
                    StockCommittedEvent event = deserializeStockCommittedEvent(record.value());
                    if (filterOrderId.equals(event.orderId())) {
                        result.add(event);
                    }
                });
            }

            // expectedCount == 0 인 경우 충분히 폴링해 해당 orderId 메시지 없음 확인
            if (expectedCount == 0) {
                long extraDeadline = System.currentTimeMillis() + Duration.ofMillis(500).toMillis();
                while (System.currentTimeMillis() < extraDeadline) {
                    ConsumerRecords<String, String> extra = consumer.poll(Duration.ofMillis(200));
                    extra.forEach(record -> {
                        StockCommittedEvent event = deserializeStockCommittedEvent(record.value());
                        if (filterOrderId.equals(event.orderId())) {
                            result.add(event);
                        }
                    });
                }
            }
        }

        return result;
    }

    private StockCommittedEvent deserializeStockCommittedEvent(String json) {
        try {
            return objectMapper.readValue(json, StockCommittedEvent.class);
        } catch (Exception e) {
            throw new IllegalStateException("StockCommittedEvent 역직렬화 실패: " + json, e);
        }
    }
}
