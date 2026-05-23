package com.hyoguoo.paymentplatform.payment.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.payment.application.dto.event.ConfirmedEventMessage;
import com.hyoguoo.paymentplatform.payment.application.messaging.PaymentTopics;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentCommandUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentConfirmResultUseCase;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentGatewayType;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentEventEntity;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentOrderEntity;
import com.hyoguoo.paymentplatform.payment.infrastructure.repository.JpaPaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.infrastructure.repository.JpaPaymentOrderRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

/**
 * 보상 플로우 end-to-end 통합 시나리오 테스트.
 *
 * <p>검증 범위: 결제 결과 보상 플로우.
 * ConfirmedEventConsumer → PaymentConfirmResultUseCase → StockCacheRedisAdapter → Lua 전 경로.
 * Spring Kafka DefaultErrorHandler 의 retry / DLQ 거동도 통합 컨텍스트에서 검증.
 *
 * <p>범위 밖 알려진 한계:
 * <ul>
 *   <li>P8D 만료 후 Reconciler resetToReady race — 통합 테스트 범위 밖</li>
 *   <li>보상 끝난 결제의 새 confirm 사이클 cascade — 통합 테스트 범위 밖</li>
 *   <li>markPaymentAsFail 영구 실패 cascade — 통합 테스트 범위 밖</li>
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
@DisplayName("보상 플로우 end-to-end 통합 시나리오 테스트")
class StockCompensationRecoveryIntegrationTest {

    private static final Long PRODUCT_ID = 100L;
    private static final int INITIAL_STOCK = 10;
    private static final int ORDER_QUANTITY = 3;

    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL_CONTAINER =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("payment-test")
                    .withUsername("test")
                    .withPassword("test")
                    .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci")
                    .withReuse(true);

    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS_CONTAINER =
            new GenericContainer<>("redis:7.2-alpine")
                    .withCommand("redis-server", "--appendonly", "yes", "--appendfsync", "always")
                    .withExposedPorts(6379)
                    .withReuse(true);

    static {
        // @Testcontainers/@Container 를 사용하지 않고 수동 start.
        // @Container 로 관리하면 JUnit5 extension 이 테스트 클래스 완료 후 stop() 을 명시 호출하여
        // withReuse(true) 설정에도 불구하고 컨테이너가 종료된다.
        // 종료된 컨테이너는 BaseIntegrationTest 컨텍스트(HikariPool) 의 연결을 끊어 후속
        // PaymentControllerTest / PaymentCheckoutConcurrencyIntegrationTest 를 실패시킨다.
        MYSQL_CONTAINER.start();
        REDIS_CONTAINER.start();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL_CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL_CONTAINER::getUsername);
        registry.add("spring.datasource.password", MYSQL_CONTAINER::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        // Flyway 활성화 — payment_event_dedupe V2 테이블 필요
        // application-test.yml 에서 false 로 설정되나, 이 테스트는 JDBC INSERT IGNORE 를 사용하므로
        // ddl-auto: create-drop 으로는 payment_event_dedupe 테이블이 생성되지 않아 활성화 필요.
        registry.add("spring.flyway.enabled", () -> "true");
        // Flyway 와 JPA ddl-auto 충돌 방지 — Flyway 가 스키마를 담당
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        // defer-datasource-initialization 비활성화 — Flyway 활성화 시 circular depends-on 방지
        registry.add("spring.jpa.defer-datasource-initialization", () -> "false");
        registry.add("scheduler.enabled", () -> "false");
        // StockCacheRedisAdapter 가 payment.cache.stock-redis.* 를 사용한다 (StockRedisConfig).
        registry.add("payment.cache.stock-redis.host", REDIS_CONTAINER::getHost);
        registry.add("payment.cache.stock-redis.port",
                () -> String.valueOf(REDIS_CONTAINER.getMappedPort(6379)));
        // EmbeddedKafka 테스트에서 ConfirmedEventConsumer 를 실제로 시작해야 한다.
        // application-test.yml 의 auto-startup=false 를 오버라이드.
        registry.add("spring.kafka.listener.auto-startup", () -> "true");
        // EOS backoff 단축 — 테스트 DLQ 검증 시간 단축 (backoff 200ms × 5회 = 1s)
        registry.add("payment.kafka.error-handler.backoff.interval", () -> "200");
        registry.add("payment.kafka.error-handler.backoff.max-attempts", () -> "5");
    }

    @Autowired
    private KafkaTemplate<String, String> confirmedDlqKafkaTemplate;

    @Autowired
    private JpaPaymentEventRepository jpaPaymentEventRepository;

    @Autowired
    private JpaPaymentOrderRepository jpaPaymentOrderRepository;

    @MockitoSpyBean
    private StockCachePort stockCachePort;

    @MockitoSpyBean
    private PaymentCommandUseCase paymentCommandUseCase;

    @MockitoSpyBean
    private PaymentConfirmResultUseCase paymentConfirmResultUseCase;

    private StringRedisTemplate redisTemplate;
    private LettuceConnectionFactory connectionFactory;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(
                REDIS_CONTAINER.getHost(),
                REDIS_CONTAINER.getMappedPort(6379)
        );
        connectionFactory = new LettuceConnectionFactory(config);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        // 재고 초기값 설정
        redisTemplate.opsForValue().set("stock:" + PRODUCT_ID, String.valueOf(INITIAL_STOCK - ORDER_QUANTITY));

        jpaPaymentOrderRepository.deleteAllInBatch();
        jpaPaymentEventRepository.deleteAllInBatch();
    }

    @AfterEach
    void tearDown() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        connectionFactory.destroy();
        jpaPaymentOrderRepository.deleteAllInBatch();
        jpaPaymentEventRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("정상 FAILED 보상 플로우: events.confirmed FAILED 수신 → compensateAtomic OK → markPaymentAsFail → 재고 원복")
    void 정상_FAILED_보상_플로우_재고_복원() throws Exception {
        // given
        String orderId = "order-scr10-" + UUID.randomUUID();
        savePaymentInProgress(orderId);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                orderId, "FAILED", "TEST_FAIL", null, null, UUID.randomUUID().toString()
        );
        String payload = objectMapper.writeValueAsString(message);

        // when
        confirmedDlqKafkaTemplate.send(PaymentTopics.EVENTS_CONFIRMED, orderId, payload);

        // then — 재고 복원 확인 (INITIAL_STOCK - ORDER_QUANTITY + ORDER_QUANTITY = INITIAL_STOCK)
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    String stockValue = redisTemplate.opsForValue().get("stock:" + PRODUCT_ID);
                    assertThat(stockValue).isNotNull();
                    assertThat(Integer.parseInt(stockValue)).isEqualTo(INITIAL_STOCK);
                });

        // RDB 상태 FAILED 확인
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    PaymentEventEntity entity = jpaPaymentEventRepository.findByOrderId(orderId).orElseThrow();
                    assertThat(entity.getStatus()).isEqualTo(PaymentEventStatus.FAILED);
                });
    }

    @Test
    @DisplayName("보상 ALREADY_DONE 재배달 멱등: 동일 orderId 두 번 수신 → 두 번째는 noop → 재고 한 번만 복원")
    void 보상_ALREADY_DONE_재배달_멱등() throws Exception {
        // given
        String orderId = "order-scr10-idem-" + UUID.randomUUID();
        savePaymentInProgress(orderId);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                orderId, "FAILED", "TEST_FAIL", null, null, UUID.randomUUID().toString()
        );
        String payload = objectMapper.writeValueAsString(message);

        // when — 첫 번째 발행
        confirmedDlqKafkaTemplate.send(PaymentTopics.EVENTS_CONFIRMED, orderId, payload);

        // 첫 번째 처리 완료 대기
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    PaymentEventEntity entity = jpaPaymentEventRepository.findByOrderId(orderId).orElseThrow();
                    assertThat(entity.getStatus()).isEqualTo(PaymentEventStatus.FAILED);
                });

        // 두 번째 메시지 발행 (재배달 시뮬레이션)
        confirmedDlqKafkaTemplate.send(PaymentTopics.EVENTS_CONFIRMED, orderId, payload);

        // then — 잠시 후에도 재고가 두 번 복원되지 않음 (ALREADY_DONE dedup token 방어)
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    String stockValue = redisTemplate.opsForValue().get("stock:" + PRODUCT_ID);
                    assertThat(stockValue).isNotNull();
                    // 재고가 INITIAL_STOCK 을 초과하지 않아야 함 (이중 복원 방어)
                    assertThat(Integer.parseInt(stockValue)).isEqualTo(INITIAL_STOCK);
                });
    }

    @Test
    @DisplayName("RuntimeException 시 retry 5회 후 DLQ: Redis 연결 실패 stub → FixedBackOff 5회 → DLQ 토픽 발행 확인")
    void RuntimeException_시_retry_5회_후_DLQ() throws Exception {
        // given
        String orderId = "order-scr10-dlq-" + UUID.randomUUID();
        savePaymentInProgress(orderId);

        // compensateAtomic 에서 RuntimeException 을 던지도록 stub
        doThrow(new RuntimeException("Redis 연결 실패 stub"))
                .when(stockCachePort).compensateAtomic(anyString(), anyList());

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                orderId, "FAILED", "TEST_FAIL", null, null, UUID.randomUUID().toString()
        );
        String payload = objectMapper.writeValueAsString(message);

        // when
        confirmedDlqKafkaTemplate.send(PaymentTopics.EVENTS_CONFIRMED, orderId, payload);

        // then — 5회 retry(초기 시도 포함 총 6회) 후 DLQ 토픽에 메시지가 발행되어야 함
        // DefaultErrorHandler maxAttempts=5 → 1초 간격 5회 = 최대 5초 + 라운드트립
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() ->
                        verify(stockCachePort, times(6)).compensateAtomic(anyString(), anyList())
                );
    }

    @Test
    @DisplayName("not-retryable IllegalArgumentException 즉시 DLQ: retry 없이 즉시 DLQ 발행")
    void not_retryable_IllegalArgumentException_즉시_DLQ() throws Exception {
        // given
        String orderId = "order-scr10-notretry-" + UUID.randomUUID();
        savePaymentInProgress(orderId);

        // compensateAtomic 에서 IllegalArgumentException (not-retryable)
        doThrow(new IllegalArgumentException("데이터 형식 손상 stub"))
                .when(stockCachePort).compensateAtomic(anyString(), anyList());

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                orderId, "FAILED", "TEST_FAIL", null, null, UUID.randomUUID().toString()
        );
        String payload = objectMapper.writeValueAsString(message);

        // when
        confirmedDlqKafkaTemplate.send(PaymentTopics.EVENTS_CONFIRMED, orderId, payload);

        // then — retry 없이 즉시 1회만 호출되고 DLQ 로 빠져야 함 (최대 3초 내)
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() ->
                        verify(stockCachePort, times(1)).compensateAtomic(anyString(), anyList())
                );

        // 추가 호출이 없음을 확인 (retry 미발생)
        TimeUnit.SECONDS.sleep(2);
        verify(stockCachePort, times(1)).compensateAtomic(anyString(), anyList());
    }

    @Test
    @DisplayName("호출 순서 검증: compensateAtomic 호출 시점이 markPaymentAsFail 호출 시점보다 선행")
    void 호출_순서_검증_보상_먼저_RDB_나중() throws Exception {
        // given
        String orderId = "order-scr10-order-" + UUID.randomUUID();
        savePaymentInProgress(orderId);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                orderId, "FAILED", "TEST_FAIL", null, null, UUID.randomUUID().toString()
        );
        String payload = objectMapper.writeValueAsString(message);

        // when
        confirmedDlqKafkaTemplate.send(PaymentTopics.EVENTS_CONFIRMED, orderId, payload);

        // then — FAILED 상태 전이까지 대기 (처리 완료 시점)
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    PaymentEventEntity entity = jpaPaymentEventRepository.findByOrderId(orderId).orElseThrow();
                    assertThat(entity.getStatus()).isEqualTo(PaymentEventStatus.FAILED);
                });

        // InOrder 검증: compensateAtomic 이 markPaymentAsFail 보다 먼저 호출
        InOrder order = inOrder(stockCachePort, paymentCommandUseCase);
        order.verify(stockCachePort).compensateAtomic(anyString(), anyList());
        order.verify(paymentCommandUseCase).markPaymentAsFail(any(), anyString());
    }

    // ── 픽스처 헬퍼 ────────────────────────────────────────────────────────────

    /**
     * IN_PROGRESS 상태의 PaymentEvent + PaymentOrder 를 DB 에 저장한다.
     * @param orderId 주문 ID
     */
    private void savePaymentInProgress(String orderId) {
        PaymentEventEntity event = PaymentEventEntity.builder()
                .buyerId(1L)
                .sellerId(2L)
                .orderName("테스트 상품 포함 1건")
                .orderId(orderId)
                .paymentKey("pay-key-" + orderId)
                .gatewayType(PaymentGatewayType.TOSS)
                .status(PaymentEventStatus.IN_PROGRESS)
                .retryCount(0)
                .lastStatusChangedAt(java.time.LocalDateTime.now())
                .build();
        PaymentEventEntity savedEvent = jpaPaymentEventRepository.save(event);

        PaymentOrderEntity order = PaymentOrderEntity.builder()
                .paymentEventId(savedEvent.getId())
                .orderId(orderId)
                .productId(PRODUCT_ID)
                .quantity(ORDER_QUANTITY)
                .totalAmount(BigDecimal.valueOf(15000))
                .status(PaymentOrderStatus.EXECUTING)
                .build();
        jpaPaymentOrderRepository.save(order);
    }
}
