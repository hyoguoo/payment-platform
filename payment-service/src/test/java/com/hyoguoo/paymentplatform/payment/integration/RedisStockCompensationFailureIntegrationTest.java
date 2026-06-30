package com.hyoguoo.paymentplatform.payment.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.payment.application.dto.event.ConfirmedEventMessage;
import com.hyoguoo.paymentplatform.payment.application.messaging.PaymentTopics;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
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
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
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
 * redis-stock 보상 실패 시 정합 거동을 검증하는 통합테스트.
 *
 * <p>검증 범위:
 * <ul>
 *   <li>FAILED 결과수신 중 {@link StockCachePort#compensateAtomic} 실패(spy doThrow) →
 *       DefaultErrorHandler retry 소진 → events.confirmed.dlq 보존(유실 0).
 *       entry 경로(선차감 실패→QUARANTINED 흡수)와 달리 흡수되지 않고 DLQ 로 도달함을 단정.</li>
 *   <li>보상 실패 후 redis 선차감 잔존 확인: redis 재고(DECR 잔존) &lt; 초기 재고(product RDB 등가)
 *       = 과예약 보수적 방향(over-sell 아님). 자동 복구는 TC-3 위임(가시화 한계).</li>
 * </ul>
 *
 * <p>재현 메커니즘:
 * <ul>
 *   <li>보상 실패는 {@link StockCachePort#compensateAtomic} 를 spy doThrow 로 결정적 유발.
 *       컨테이너 stop 은 Hikari connectionTimeout(30s×5) 으로 비결정적이라 금지.</li>
 *   <li>Redis 초기 재고는 checkout 선차감 완료 상태(INITIAL_STOCK - ORDER_QUANTITY)로 설정해
 *       checkout 이 발생한 상황을 재현한다.</li>
 *   <li>throw 예외({@link RuntimeException})는 not-retryable 화이트리스트
 *       (MessageConversion/IllegalArgument/IllegalState) 밖 — retry → DLQ 경로를 탄다.</li>
 * </ul>
 *
 * <p>범위 밖 알려진 한계:
 * <ul>
 *   <li>선차감 자동 복구 — TC-3(재고 재동기) 위임</li>
 *   <li>보상 실패 후 RDB 상태 자동 전이 — TQ-1(DLQ 재주입) 위임</li>
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
@DisplayName("redis-stock 보상 실패 정합 거동 통합테스트")
class RedisStockCompensationFailureIntegrationTest {

    private static final Long PRODUCT_ID = 200L;
    private static final int INITIAL_STOCK = 10;
    private static final int ORDER_QUANTITY = 3;
    private static final BigDecimal UNIT_AMOUNT = BigDecimal.valueOf(9000);

    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL_CONTAINER =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("payment-redis-fail-test")
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
        // 종료된 Redis/MySQL 컨테이너는 후속 통합테스트의 HikariPool 연결을 끊어 실패시킨다.
        MYSQL_CONTAINER.start();
        REDIS_CONTAINER.start();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        // MySQL — 전용 DB 명(payment-redis-fail-test)으로 다른 통합테스트 컨테이너와 격리
        registry.add("spring.datasource.url", MYSQL_CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL_CONTAINER::getUsername);
        registry.add("spring.datasource.password", MYSQL_CONTAINER::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        // Flyway 활성화 — payment_event_dedupe V2 테이블 필요 (application-test.yml 에서 false)
        registry.add("spring.flyway.enabled", () -> "true");
        // Flyway 와 JPA ddl-auto 충돌 방지 — Flyway 가 스키마를 담당
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        // defer-datasource-initialization 비활성화 — Flyway 활성화 시 circular depends-on 방지
        registry.add("spring.jpa.defer-datasource-initialization", () -> "false");
        // Redis — redis-dedupe(IdempotencyStoreRedisAdapter) + redis-stock(StockCacheRedisAdapter) 모두 동일 컨테이너
        registry.add("spring.data.redis.host", REDIS_CONTAINER::getHost);
        registry.add("spring.data.redis.port",
                () -> String.valueOf(REDIS_CONTAINER.getMappedPort(6379)));
        registry.add("payment.cache.stock-redis.host", REDIS_CONTAINER::getHost);
        registry.add("payment.cache.stock-redis.port",
                () -> String.valueOf(REDIS_CONTAINER.getMappedPort(6379)));
        // 스케줄러 비활성화 — 이 테스트는 시각 제어 불요
        registry.add("scheduler.enabled", () -> "false");
        // KafkaListener auto-startup 활성화 (application-test.yml 에서 false)
        registry.add("spring.kafka.listener.auto-startup", () -> "true");
        // DefaultErrorHandler backoff 단축 — DLQ 도달 시간 단축 (interval 200ms × 5회 = 최소 1s)
        // 5회 retry 동작 자체는 유지 (max-attempts 변경 없음)
        registry.add("payment.kafka.error-handler.backoff.interval", () -> "200");
        registry.add("payment.kafka.error-handler.backoff.max-attempts", () -> "5");
    }

    /** 보상 실패를 결정적으로 유발하기 위해 spy 로 감싼다. */
    @MockitoSpyBean
    private StockCachePort stockCachePort;

    @Autowired
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

    private StringRedisTemplate redisTemplate;
    private LettuceConnectionFactory connectionFactory;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private String bootstrapServers;

    @BeforeEach
    void setUp() {
        // cold-start 방어: consumer group join + partition assignment 가 완료되도록 대기.
        // 이로써 메시지 발행 직후 consumer 가 준비 완료된다.
        for (MessageListenerContainer container : kafkaListenerEndpointRegistry.getListenerContainers()) {
            await().atMost(Duration.ofSeconds(30))
                    .until(() -> container.getAssignedPartitions() != null
                            && !container.getAssignedPartitions().isEmpty());
        }

        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(
                REDIS_CONTAINER.getHost(),
                REDIS_CONTAINER.getMappedPort(6379)
        );
        connectionFactory = new LettuceConnectionFactory(config);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        // checkout 선차감 완료 상태로 초기화.
        // 실제 흐름에서 checkout 시 decrementAtomic 이 INITIAL_STOCK → INITIAL_STOCK - ORDER_QUANTITY 로 감소.
        // 이 테스트는 checkout 완료 후 FAILED 결과수신 시점을 재현하므로 미리 감소된 값으로 설정한다.
        redisTemplate.opsForValue().set(
                "stock:" + PRODUCT_ID,
                String.valueOf(INITIAL_STOCK - ORDER_QUANTITY)
        );

        jpaPaymentOrderRepository.deleteAllInBatch();
        jpaPaymentEventRepository.deleteAllInBatch();
        namedParameterJdbcTemplate.update("DELETE FROM payment_event_dedupe", Collections.emptyMap());

        bootstrapServers = embeddedKafkaBroker.getBrokersAsString();
    }

    @AfterEach
    void tearDown() {
        RedisConnectionFactory factory = redisTemplate.getConnectionFactory();
        if (factory == null) {
            throw new IllegalStateException("RedisConnectionFactory must not be null");
        }
        RedisConnection connection = factory.getConnection();
        if (connection == null) {
            throw new IllegalStateException("RedisConnection must not be null");
        }
        connection.serverCommands().flushAll();
        connectionFactory.destroy();

        jpaPaymentOrderRepository.deleteAllInBatch();
        jpaPaymentEventRepository.deleteAllInBatch();
        namedParameterJdbcTemplate.update("DELETE FROM payment_event_dedupe", Collections.emptyMap());
    }

    // ── 시나리오 ────────────────────────────────────────────────────────────────

    /**
     * redis-stock 보상 실패 → DLQ 유실0 + 선차감 stranded 검증.
     *
     * <p>compensateAtomic spy doThrow 로 결정적 보상 실패 주입 → DefaultErrorHandler 200ms×5 retry
     * 소진 → events.confirmed.dlq 1건 보존(유실 0) 단정.
     *
     * <p>보상 실패로 redis 선차감(DECR) 이 잔존하고 product RDB 는 FAILED 경로에서
     * stock-committed 미발행이라 미차감. redis 재고(INITIAL_STOCK - ORDER_QUANTITY) &lt;
     * 초기 재고(INITIAL_STOCK, product RDB 등가) = 과예약 보수적 방향(over-sell 아님).
     *
     * <p>entry 경로와의 차이: 선차감 실패(decrementAtomic 결과 CACHE_DOWN) 는 QUARANTINED 로
     * 흡수돼 DLQ 로 가지 않는다. 반면 이 경로(결과수신 보상 실패) 는 흡수되지 않고 DLQ 로 도달한다.
     */
    @Test
    @DisplayName("redis-stock 보상 실패 → DLQ 유실0 + 선차감 stranded(redis 재고 < 초기 재고)")
    void redis_stock다운_결과수신보상실패_DLQ유실0() throws Exception {
        // given — IN_PROGRESS 결제 저장 + compensateAtomic 결정적 실패 주입
        String orderId = "order-redis-fail-" + UUID.randomUUID();
        savePaymentInProgress(orderId);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                orderId, "FAILED", "TEST_FAIL", null, null, UUID.randomUUID().toString()
        );
        String payload = objectMapper.writeValueAsString(message);

        // when — events.confirmed 에 FAILED 메시지 발행
        confirmedDlqKafkaTemplate.send(PaymentTopics.EVENTS_CONFIRMED, orderId, payload);

        // then (1) DLQ 유실0 (load-bearing): compensateAtomic 실패 → retry 소진 → DLQ 보존.
        // entry 경로(선차감 실패→QUARANTINED 흡수)와 달리 흡수되지 않고 DLQ 로 도달함을 단정한다.
        List<String> dlqPayloads = pollConfirmedDlq(orderId, Duration.ofSeconds(30));
        assertThat(dlqPayloads)
                .as("events.confirmed.dlq 1건 보존 — 보상 실패 시 흡수 없이 DLQ 도달(유실 0). "
                        + "entry 경로(QUARANTINED 흡수)와 달리 이 경로는 DLQ 로 간다.")
                .hasSize(1);

        // then (2) 선차감 stranded 확인.
        // 보상 실패로 redis 선차감(DECR) 이 잔존하고 product RDB 는 FAILED 경로에서
        // stock-committed 미발행이라 미차감.
        // redis 재고(INITIAL_STOCK - ORDER_QUANTITY) < 초기 재고(INITIAL_STOCK, product RDB 등가)
        // = 과예약 보수적 방향(over-sell 아님). 자동 복구는 TC-3 위임(가시화 한계 — 설계 명시).
        String stockValue = redisTemplate.opsForValue().get("stock:" + PRODUCT_ID);
        assertThat(stockValue)
                .as("보상 실패 후 redis 재고 키 존재 확인")
                .isNotNull();
        int redisStock = Integer.parseInt(stockValue);
        assertThat(redisStock)
                .as("보상 실패 후 redis 재고는 선차감 잔존(복원 안 됨) — stranded 상태 단정. "
                        + "기대값: %d (INITIAL_STOCK %d - ORDER_QUANTITY %d)"
                                .formatted(INITIAL_STOCK - ORDER_QUANTITY, INITIAL_STOCK, ORDER_QUANTITY))
                .isEqualTo(INITIAL_STOCK - ORDER_QUANTITY);
        assertThat(redisStock)
                .as("redis 재고(선차감 잔존 %d) < 초기 재고(product RDB 등가 %d) → 과예약 보수적(over-sell 아님)"
                        .formatted(redisStock, INITIAL_STOCK))
                .isLessThan(INITIAL_STOCK);
    }

    // ── 픽스처 헬퍼 ─────────────────────────────────────────────────────────────

    /**
     * IN_PROGRESS 상태 PaymentEvent + PaymentOrder 1건 저장.
     *
     * <p>FAILED 결과 수신 시 {@link StockCachePort#compensateAtomic} 가 paymentOrderList 를
     * 사용하므로 ORDER_QUANTITY 와 PRODUCT_ID 를 재고 초기화 값과 일치시킨다.
     */
    private void savePaymentInProgress(String orderId) {
        Instant now = Instant.now();
        PaymentEventEntity event = PaymentEventEntity.builder()
                .buyerId(1L)
                .sellerId(2L)
                .orderName("보상실패 테스트 상품 — " + orderId)
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
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-redis-fail-dlq-reader-" + UUID.randomUUID());
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
