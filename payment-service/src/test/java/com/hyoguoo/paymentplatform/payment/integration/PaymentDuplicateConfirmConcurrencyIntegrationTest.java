package com.hyoguoo.paymentplatform.payment.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.messaging.PaymentTopics;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentGatewayType;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentOutboxDuplicateException;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentEventEntity;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentOrderEntity;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentOutboxEntity;
import com.hyoguoo.paymentplatform.payment.infrastructure.repository.JpaPaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.infrastructure.repository.JpaPaymentOrderRepository;
import com.hyoguoo.paymentplatform.payment.infrastructure.repository.JpaPaymentOutboxRepository;
import com.hyoguoo.paymentplatform.payment.presentation.port.PaymentConfirmService;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

/**
 * 같은 주문에 대한 confirm 두 건을 실제 시점에 맞춰 동시에 태워, 주문 단위 선점에서 진 쪽이
 * 결제 상태를 건드리지 않고 예외 없이 물러나며 이긴 쪽만 정상 확정되는지 확인한다.
 *
 * <p>확정 진입은 상품 반복에 앞서 {@code stock:order-lock:orderId} 를 SETNX 로 선점한다 — 못
 * 잡으면 재고 판정 자체를 보지 않고 즉시 물러난다. 두 스레드를 같은 시점에 풀어야 이 경합이
 * 실제로 발생한다({@code CountDownLatch}), 스케줄링 편차를 상쇄하기 위해 반복한다.
 *
 * <p>선점이 동시 중복 요청을 하나로 수렴시키므로, 예전처럼 outbox UNIQUE 제약에서 진 쪽이 갈리는
 * 경로는 이 시나리오에서 더 이상 도달하지 않는다 — 진 쪽은 재고 캐시도, 결제 상태도 건드리지
 * 않고 {@link PaymentOutboxDuplicateException} 없이 조용히 물러난다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
@EmbeddedKafka(
        partitions = 1,
        topics = {
                PaymentTopics.COMMANDS_CONFIRM,
                PaymentTopics.COMMANDS_CONFIRM_DLQ
        },
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@DisplayName("같은 주문 동시 승인 경합 통합 테스트")
class PaymentDuplicateConfirmConcurrencyIntegrationTest {

    private static final Long PRODUCT_ID = 300L;
    private static final int INITIAL_STOCK = 10;
    private static final int ORDER_QUANTITY = 2;
    private static final Long BUYER_ID = 1L;
    private static final BigDecimal UNIT_PRICE = BigDecimal.valueOf(5000);
    private static final String UNRECOVERED_METRIC_NAME = "stock_retention_unrecovered_total";

    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL_CONTAINER =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("payment-dup-confirm-test")
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
        // @Container 가 stop() 을 강제하면 withReuse(true) 에도 불구하고 컨테이너가 종료된다.
        MYSQL_CONTAINER.start();
        REDIS_CONTAINER.start();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL_CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL_CONTAINER::getUsername);
        registry.add("spring.datasource.password", MYSQL_CONTAINER::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("scheduler.enabled", () -> "false");
        // outbox 행이 PENDING 그대로 남아야 "이긴 쪽 정상 확정"을 확정적으로 관찰할 수 있다 —
        // 즉시 relay(AFTER_COMMIT 비동기)를 켜 두면 검증 시점 전에 DONE 으로 넘어가 버려
        // 경합 검증과 무관한 타이밍에 테스트가 흔들린다.
        registry.add("payment.monolith.confirm.enabled", () -> "false");
        registry.add("payment.cache.stock-redis.host", REDIS_CONTAINER::getHost);
        registry.add("payment.cache.stock-redis.port",
                () -> String.valueOf(REDIS_CONTAINER.getMappedPort(6379)));
        registry.add("spring.kafka.listener.auto-startup", () -> "true");
    }

    @Autowired
    private PaymentConfirmService paymentConfirmService;

    @Autowired
    private JpaPaymentEventRepository jpaPaymentEventRepository;

    @Autowired
    private JpaPaymentOrderRepository jpaPaymentOrderRepository;

    @Autowired
    private JpaPaymentOutboxRepository jpaPaymentOutboxRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    private StringRedisTemplate redisTemplate;
    private LettuceConnectionFactory connectionFactory;

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

        redisTemplate.opsForValue().set("stock:{" + PRODUCT_ID + "}", String.valueOf(INITIAL_STOCK));
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
        jpaPaymentOutboxRepository.deleteAllInBatch();
        jpaPaymentOrderRepository.deleteAllInBatch();
        jpaPaymentEventRepository.deleteAllInBatch();
    }

    @RepeatedTest(50)
    @DisplayName("같은 주문 동시 승인 2건 — 진 쪽은 선점 실패로 예외 없이 물러나고, 이긴 쪽만 정상 확정된다")
    void 같은_주문_동시_승인에서_진_쪽은_선점_실패로_예외_없이_물러난다() throws Exception {
        // given — 반복마다 새 주문 번호를 써야 앞선 반복이 남긴 outbox 행 때문에 경합이
        // 무력화되지 않는다.
        String orderId = "order-dup-confirm-" + UUID.randomUUID();
        String paymentKey = "pay-key-" + orderId;
        saveReadyPayment(orderId);

        PaymentConfirmCommand command = buildConfirmCommand(orderId, paymentKey);
        double unrecoveredBefore = unrecoveredCounterCount();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Exception> firstFailure = new AtomicReference<>();
        AtomicReference<Exception> secondFailure = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        // when — 두 스레드를 같은 시점에 풀어 주문 단위 선점 경합을 만든다.
        executor.submit(() -> {
            ready.countDown();
            awaitQuietly(start);
            try {
                paymentConfirmService.confirm(command);
            } catch (Exception e) {
                firstFailure.set(e);
            }
        });
        executor.submit(() -> {
            ready.countDown();
            awaitQuietly(start);
            try {
                paymentConfirmService.confirm(command);
            } catch (Exception e) {
                secondFailure.set(e);
            }
        });

        ready.await();
        start.countDown();
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // then — 선점이 동시 요청을 하나로 수렴시키므로 둘 다 예외 없이 끝난다. 진 쪽은
        // 재고 판정 자체를 보지 못하고 물러나 outbox UNIQUE 경합까지 갈 일이 없다.
        assertThat(firstFailure.get()).as("첫 번째 요청은 예외 없이 끝나야 한다").isNull();
        assertThat(secondFailure.get()).as("두 번째 요청은 예외 없이 끝나야 한다").isNull();

        // then — 진 쪽에는 재고 미회수 경보가 남지 않는다(카운터 증가 0).
        assertThat(unrecoveredCounterCount()).isEqualTo(unrecoveredBefore);

        // then — 이긴 쪽만 정상 확정되어 outbox 행이 정확히 1개, PENDING 으로 생성된다.
        PaymentOutboxEntity outboxEntity = jpaPaymentOutboxRepository.findByOrderId(orderId).orElseThrow();
        assertThat(outboxEntity.getStatus()).isEqualTo(PaymentOutboxStatus.PENDING);

        // then — 최종 상태는 이긴 쪽이 커밋한 IN_PROGRESS 하나뿐이고, 진 쪽이 결제 상태를
        // 건드리지 않았으므로 READY 로도, 다른 잔여 상태로도 반쯤 남지 않는다.
        PaymentEventEntity eventEntity = jpaPaymentEventRepository.findByOrderId(orderId).orElseThrow();
        assertThat(eventEntity.getStatus()).isEqualTo(PaymentEventStatus.IN_PROGRESS);
    }

    // ── 내부 헬퍼 ────────────────────────────────────────────────────────────

    private double unrecoveredCounterCount() {
        return meterRegistry.get(UNRECOVERED_METRIC_NAME).counter().count();
    }

    private void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("대기 중 인터럽트", e);
        }
    }

    private void saveReadyPayment(String orderId) {
        PaymentEventEntity event = PaymentEventEntity.builder()
                .buyerId(BUYER_ID)
                .sellerId(2L)
                .orderName("테스트 상품 포함 1건")
                .orderId(orderId)
                .gatewayType(PaymentGatewayType.TOSS)
                .status(PaymentEventStatus.READY)
                .lastStatusChangedAt(Instant.now())
                .build();
        PaymentEventEntity savedEvent = jpaPaymentEventRepository.save(event);

        PaymentOrderEntity order = PaymentOrderEntity.builder()
                .paymentEventId(savedEvent.getId())
                .orderId(orderId)
                .productId(PRODUCT_ID)
                .quantity(ORDER_QUANTITY)
                .totalAmount(UNIT_PRICE.multiply(BigDecimal.valueOf(ORDER_QUANTITY)))
                .status(PaymentOrderStatus.EXECUTING)
                .build();
        jpaPaymentOrderRepository.save(order);
    }

    private PaymentConfirmCommand buildConfirmCommand(String orderId, String paymentKey) {
        return PaymentConfirmCommand.builder()
                .userId(BUYER_ID)
                .orderId(orderId)
                .amount(UNIT_PRICE.multiply(BigDecimal.valueOf(ORDER_QUANTITY)))
                .paymentKey(paymentKey)
                .gatewayType(PaymentGatewayType.TOSS)
                .build();
    }
}
