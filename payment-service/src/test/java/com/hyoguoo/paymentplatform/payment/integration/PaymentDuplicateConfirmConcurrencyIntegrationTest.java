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
 * 같은 주문에 대한 confirm 두 건을 실제 시점에 맞춰 동시에 태워, outbox UNIQUE 경합에서
 * 진 쪽이 {@link PaymentOutboxDuplicateException}으로 분류되고 경보 없이 롤백되는지 확인한다.
 *
 * <p>{@code executeConfirmTx}는 결제 상태 전이(READY→IN_PROGRESS)를 먼저 커밋 스냅샷에 반영한
 * 뒤 outbox INSERT IGNORE를 시도한다. 두 트랜잭션이 실제로 겹쳐야만(스케줄링이 완전히 갈려
 * 뒤쪽 트랜잭션이 앞쪽 커밋 이후에야 시작하면 잠금 없는 조회로도 우연히 같은 결과가 나온다)
 * 잠금 읽기 확인 조회가 실제로 필요한 경우를 재현한다 — {@code CountDownLatch}로 두 스레드를
 * 같은 시점에 풀고, 스케줄링 편차를 상쇄하기 위해 반복한다.
 *
 * <p>재고 차감(Redis)은 {@code synchronized} 원자 처리로 이미 정확히-한-번을 보장하는 경로라
 * 이 테스트의 관심사가 아니다 — 여기서 보는 것은 outbox 행 삽입의 MySQL 트랜잭션 경합이다.
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
    @DisplayName("같은 주문 동시 승인 2건 — 진 쪽은 중복 재진입으로 분류되고 경보 없이 롤백되며, "
            + "이긴 쪽만 정상 확정된다")
    void 같은_주문_동시_승인에서_진_쪽은_중복_재진입으로_분류되고_경보_없이_롤백된다() throws Exception {
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

        // when — 두 스레드를 같은 시점에 풀어 outbox 삽입 경합을 만든다.
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

        Exception loserException = firstFailure.get() != null ? firstFailure.get() : secondFailure.get();

        // then — 정확히 한 건만 실패(진 쪽)하고 나머지 한 건은 성공(이긴 쪽)한다.
        boolean exactlyOneFailed = (firstFailure.get() == null) != (secondFailure.get() == null);
        assertThat(exactlyOneFailed)
                .as("동시 승인 2건 중 정확히 한 건만 실패해야 한다(outbox UNIQUE 가 나머지를 막는다)")
                .isTrue();

        // then — 진 쪽은 중복 재진입 예외로 분류된다.
        assertThat(loserException).isInstanceOf(PaymentOutboxDuplicateException.class);

        // then — 진 쪽에는 재고 미회수 경보가 남지 않는다(카운터 증가 0).
        assertThat(unrecoveredCounterCount()).isEqualTo(unrecoveredBefore);

        // then — 이긴 쪽은 정상 확정되어 outbox 행이 정확히 1개, PENDING 으로 생성된다.
        PaymentOutboxEntity outboxEntity = jpaPaymentOutboxRepository.findByOrderId(orderId).orElseThrow();
        assertThat(outboxEntity.getStatus()).isEqualTo(PaymentOutboxStatus.PENDING);

        // then — 진 쪽의 상태 전이는 롤백된다. 최종 상태는 이긴 쪽이 커밋한 IN_PROGRESS 하나뿐이고,
        // 결제가 READY 도 아니고 다른 잔여 상태로도 반쯤 남지 않는다.
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
