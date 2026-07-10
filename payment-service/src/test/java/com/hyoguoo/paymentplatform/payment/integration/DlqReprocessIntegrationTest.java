package com.hyoguoo.paymentplatform.payment.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hyoguoo.paymentplatform.payment.application.dto.event.ConfirmedEventMessage;
import com.hyoguoo.paymentplatform.payment.application.dto.event.StockCommittedEvent;
import com.hyoguoo.paymentplatform.payment.application.messaging.PaymentTopics;
import com.hyoguoo.paymentplatform.payment.application.port.out.DlqReprocessPort;
import com.hyoguoo.paymentplatform.payment.application.util.StockEventUuidDeriver;
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
import java.util.concurrent.TimeUnit;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

/**
 * DLQ 읽기·재발행 어댑터({@link DlqReprocessPort} Kafka 구현체) 통합 회귀 가드 2 시나리오.
 *
 * <p>검증 범위: {@code events.confirmed.dlq} 에 적재된 메시지를 {@link DlqReprocessPort#reprocess}
 * 로 읽어 원 토픽({@code events.confirmed})으로 재발행했을 때, 기존 EOS 컨슈머
 * ({@code ConfirmedEventConsumer} → {@code PaymentConfirmResultUseCase}) 가 정상 재처리하는지.
 *
 * <p>2 시나리오:
 * <ol>
 *   <li>#1 미종결 재주입 — IN_PROGRESS 결제의 DLQ 메시지 재주입 → 원 토픽 재처리 →
 *       정상 재확정(DONE) + dedupe 1 row + stock-committed 1건</li>
 *   <li>#2 DONE 건 재주입 — 이미 종결(DONE) + dedupe row 존재 상태에서 동일 event_uuid 메시지를
 *       DLQ 에서 재주입 → 종결 가드 재발행 경로로 흡수돼 stock-committed 가 결정적 idempotencyKey 로
 *       정확히 1건만 재발행(product 멱등 전제)</li>
 * </ol>
 *
 * <p>범위 밖 알려진 한계:
 * <ul>
 *   <li>DLQ 재주입 나이 게이트(P8D)·재주입 이력 — {@code DlqReprocessUseCase} 단위 테스트가 커버</li>
 *   <li>product-service 측 결정적 키 멱등 반영 자체 — cross-service 범위 밖, payment-service 측
 *       재발행이 정확히 1건임을 확인하는 선에서 멱등 전제를 검증한다</li>
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
@DisplayName("DLQ 읽기·재발행 어댑터 통합 회귀 가드 2 시나리오")
class DlqReprocessIntegrationTest {

    private static final Long PRODUCT_ID = 100L;
    private static final int ORDER_QUANTITY = 2;
    private static final BigDecimal UNIT_AMOUNT = BigDecimal.valueOf(10000);

    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL_CONTAINER =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("payment-dlqreprocess-test")
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
        // @Testcontainers/@Container 를 사용하지 않고 수동 start — PaymentEosIntegrationTest 와
        // 동일한 이유(withReuse(true) 무력화 방지, 후속 통합테스트의 HikariPool 연결 유지).
        MYSQL_CONTAINER.start();
        REDIS_CONTAINER.start();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL_CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL_CONTAINER::getUsername);
        registry.add("spring.datasource.password", MYSQL_CONTAINER::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.defer-datasource-initialization", () -> "false");
        registry.add("payment.cache.stock-redis.host", REDIS_CONTAINER::getHost);
        registry.add("payment.cache.stock-redis.port",
                () -> String.valueOf(REDIS_CONTAINER.getMappedPort(6379)));
        registry.add("scheduler.enabled", () -> "false");
        registry.add("spring.kafka.listener.auto-startup", () -> "true");
        registry.add("payment.kafka.error-handler.backoff.interval", () -> "200");
        registry.add("payment.kafka.error-handler.backoff.max-attempts", () -> "5");
        registry.add("payment.kafka.after-rollback.backoff.interval", () -> "200");
    }

    @Autowired
    private DlqReprocessPort dlqReprocessPort;

    @Autowired
    @Qualifier("confirmedDlqKafkaTemplate")
    private KafkaTemplate<String, String> confirmedDlqKafkaTemplate;

    @Autowired
    private JpaPaymentEventRepository jpaPaymentEventRepository;

    @Autowired
    private JpaPaymentOrderRepository jpaPaymentOrderRepository;

    @Autowired
    private NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @Autowired
    private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private String bootstrapServers;

    @BeforeEach
    void setUp() {
        // cold-start 방어: consumer group join + partition assignment 완료 대기
        // (PaymentEosIntegrationTest 선례 패턴 — await(15초) 이전에 consumer 준비 보장).
        for (MessageListenerContainer container : kafkaListenerEndpointRegistry.getListenerContainers()) {
            await().atMost(Duration.ofSeconds(30))
                    .until(() -> container.getAssignedPartitions() != null
                            && !container.getAssignedPartitions().isEmpty());
        }

        jpaPaymentOrderRepository.deleteAllInBatch();
        jpaPaymentEventRepository.deleteAllInBatch();
        namedParameterJdbcTemplate.update("DELETE FROM payment_event_dedupe", Collections.emptyMap());
        bootstrapServers = embeddedKafkaBroker.getBrokersAsString();
    }

    @AfterEach
    void tearDown() {
        jpaPaymentOrderRepository.deleteAllInBatch();
        jpaPaymentEventRepository.deleteAllInBatch();
        namedParameterJdbcTemplate.update("DELETE FROM payment_event_dedupe", Collections.emptyMap());
    }

    // ── 시나리오 #1 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("#1 미종결 재주입: DLQ 적재 메시지 재발행 → events.confirmed 원 토픽 EOS 컨슈머 재처리"
            + " → 정상 재확정(DONE) + dedupe 1 row + stock-committed 1건")
    void shouldReprocessUnterminatedPaymentFromDlq() throws Exception {
        // given — IN_PROGRESS 결제 + events.confirmed.dlq 에 APPROVED 메시지 선(先)적재(유실 시뮬레이션)
        String orderId = "order-dlqreprocess1-" + UUID.randomUUID();
        String eventUuid = UUID.randomUUID().toString();
        savePaymentInProgress(orderId);

        ConfirmedEventMessage message = approvedMessage(orderId, UNIT_AMOUNT.longValue(), eventUuid);
        String payload = objectMapper.writeValueAsString(message);
        confirmedDlqKafkaTemplate.send(PaymentTopics.EVENTS_CONFIRMED_DLQ, orderId, payload)
                .get(10, TimeUnit.SECONDS);

        // when — 관리자 재주입: DLQ 읽기(offset commit 없음) + events.confirmed 원 토픽 재발행
        dlqReprocessPort.reprocess(orderId);

        // then — 원 토픽 EOS 컨슈머가 재처리해 정상 재확정
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> {
                    PaymentEventEntity entity = jpaPaymentEventRepository.findByOrderId(orderId)
                            .orElseThrow();
                    assertThat(entity.getStatus()).isEqualTo(PaymentEventStatus.DONE);
                });
        assertThat(countDedupeRow(eventUuid)).isEqualTo(1);

        List<StockCommittedEvent> stockEvents = pollStockCommitted(orderId, 1, Duration.ofSeconds(10));
        assertThat(stockEvents).hasSize(1);
        assertThat(stockEvents.get(0).productId()).isEqualTo(PRODUCT_ID);
    }

    // ── 시나리오 #2 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("#2 DONE 건 재주입: 종결 가드 재발행 → stock-committed 재발행이 결정적 idempotencyKey 로"
            + " 정확히 1회만 반영(product 멱등 전제)")
    void shouldReprocessDonePaymentIdempotentlyFromDlq() throws Exception {
        // given — 이미 DONE 종결(첫 처리 완료 모사) + dedupe row 존재 상태에서 동일 event_uuid 메시지가
        // DLQ 에 적재된 상황(재발행 유실 후 관리자가 재주입하는 시나리오)을 재현한다.
        String orderId = "order-dlqreprocess2-" + UUID.randomUUID();
        String eventUuid = UUID.randomUUID().toString();
        savePaymentDone(orderId);
        insertDedupeRow(eventUuid, orderId, "APPROVED");

        ConfirmedEventMessage message = approvedMessage(orderId, UNIT_AMOUNT.longValue(), eventUuid);
        String payload = objectMapper.writeValueAsString(message);
        confirmedDlqKafkaTemplate.send(PaymentTopics.EVENTS_CONFIRMED_DLQ, orderId, payload)
                .get(10, TimeUnit.SECONDS);

        // when
        dlqReprocessPort.reprocess(orderId);

        // then — 종결 가드 재발행 경로: stock-committed 정확히 1건 + 결정적 idempotencyKey 매치
        List<StockCommittedEvent> stockEvents = pollStockCommitted(orderId, 1, Duration.ofSeconds(15));
        assertThat(stockEvents).hasSize(1);
        String expectedKey = StockEventUuidDeriver.derive(orderId, PRODUCT_ID, "stock-commit");
        assertThat(stockEvents.get(0).idempotencyKey()).isEqualTo(expectedKey);

        // payment DONE 유지, dedupe row 불변(종결 가드 재발행은 dedupe 를 거치지 않는다)
        PaymentEventEntity entity = jpaPaymentEventRepository.findByOrderId(orderId).orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(PaymentEventStatus.DONE);
        assertThat(countDedupeRow(eventUuid)).isEqualTo(1);
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────────

    /**
     * IN_PROGRESS 상태 PaymentEvent + PaymentOrder 1건 저장.
     */
    private void savePaymentInProgress(String orderId) {
        PaymentEventEntity event = buildPaymentEventEntity(orderId, PaymentEventStatus.IN_PROGRESS);
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
     * DONE(종결) 상태 PaymentEvent + PaymentOrder 1건 저장.
     * 종결 가드 재발행 경로(#2) 검증용 — 첫 처리가 이미 완료된 상태를 모사한다.
     */
    private void savePaymentDone(String orderId) {
        PaymentEventEntity event = buildPaymentEventEntity(orderId, PaymentEventStatus.DONE);
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

    private PaymentEventEntity buildPaymentEventEntity(String orderId, PaymentEventStatus status) {
        return PaymentEventEntity.builder()
                .buyerId(1L)
                .sellerId(2L)
                .orderName("DLQ 재주입 테스트 상품 — " + orderId)
                .orderId(orderId)
                .paymentKey("pay-key-" + orderId)
                .gatewayType(PaymentGatewayType.TOSS)
                .status(status)
                .lastStatusChangedAt(Instant.now())
                .build();
    }

    /**
     * APPROVED ConfirmedEventMessage 생성. approvedAt 은 현재 시각 ISO-8601 OffsetDateTime 문자열.
     */
    private static ConfirmedEventMessage approvedMessage(String orderId, Long amount, String eventUuid) {
        String approvedAt = OffsetDateTime.now(ZoneOffset.UTC).toString();
        return new ConfirmedEventMessage(orderId, "APPROVED", null, amount, approvedAt, eventUuid);
    }

    /**
     * payment_event_dedupe 테이블에 dedupe row 를 직접 삽입한다.
     * 첫 처리 완료 후 dedupe row 가 이미 존재하는 상황(종결 가드 재발행 전제)을 시뮬레이션한다.
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
     * (PaymentEosIntegrationTest 의 동일 헬퍼와 동일한 이유 — 테스트 간 토픽 공유로 인한
     * 선행 메시지 혼입을 orderId 필터로 차단한다.)
     *
     * @param filterOrderId 필터링할 orderId (각 테스트마다 unique)
     * @param expectedCount 기대하는 메시지 수
     * @param timeout       폴링 대기 최대 시간
     */
    private List<StockCommittedEvent> pollStockCommitted(
            String filterOrderId, int expectedCount, Duration timeout) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-dlqreprocess-stock-reader-" + UUID.randomUUID());
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
