package com.hyoguoo.paymentplatform.payment.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.messaging.PaymentTopics;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentOutboxUseCase;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentGatewayType;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentEventEntity;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentOrderEntity;
import com.hyoguoo.paymentplatform.payment.infrastructure.repository.JpaPaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.infrastructure.repository.JpaPaymentOrderRepository;
import com.hyoguoo.paymentplatform.payment.infrastructure.repository.JpaPaymentOutboxRepository;
import com.hyoguoo.paymentplatform.payment.presentation.port.PaymentConfirmService;
import java.math.BigDecimal;
import java.time.Duration;
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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
 * 재고 보상 폐기 정책 하의 재고 정합(과매도 0) 회귀 가드 통합 테스트.
 *
 * <p>검증 범위: {@code OutboxAsyncConfirmService.confirm()} → {@code decrementAtomic}(Redis 선차감)
 * → {@code executeConfirmTx}(@Transactional) 경로. 확정 TX 실패 시 선차감 재고를 보상(increment)하지
 * 않고 그대로 유지하는 정책(STOCK-COMPENSATION-OTHER-PATHS Task 2)이 재확정·동시 confirm 상황에서도
 * 과매도(재고가 의도한 -N 보다 더 깎이는 것) 와 이중 차감을 만들지 않음을 단언한다.
 *
 * <p>회귀 가드 취지: 향후 누군가 {@code executeConfirmTxWithStockRetention} 에 재고 보상(increment)
 * 호출을 되살리면, 본 테스트의 "재고가 정확히 -N 한 번만" 단언이 깨져야 한다.
 *
 * <p>확정 TX 실패 유발 방법: {@code payment_outbox.order_id} 에 걸린 UNIQUE 제약을 이용한다.
 * 동일 orderId의 outbox PENDING 레코드를 미리 심어두면, confirm 진입 시 {@code executeConfirmTx}
 * 내부의 {@code createPendingRecord} INSERT 가 UNIQUE 위반으로 실패해 TX 전체가 롤백된다
 * (재고 DECR 는 TX 외부에서 이미 커밋됐으므로 그대로 유지).
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
@DisplayName("재고 정합(과매도 0) 회귀 가드 통합 테스트")
class StockRetentionIntegrationTest {

    private static final Long PRODUCT_ID = 200L;
    private static final int INITIAL_STOCK = 10;
    private static final int ORDER_QUANTITY = 3;
    private static final Long BUYER_ID = 1L;
    private static final BigDecimal UNIT_PRICE = BigDecimal.valueOf(5000);

    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL_CONTAINER =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("payment-sri-test")
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
        // StockCompensationRecoveryIntegrationTest 와 동일한 이유로 수동 start —
        // @Container 가 stop() 을 강제하면 withReuse(true) 에도 불구하고 컨테이너가 종료되어
        // 후속 BaseIntegrationTest 기반 테스트의 HikariPool 연결이 끊긴다.
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
        // StockCacheRedisAdapter 가 payment.cache.stock-redis.* 를 사용한다 (StockRedisConfig).
        registry.add("payment.cache.stock-redis.host", REDIS_CONTAINER::getHost);
        registry.add("payment.cache.stock-redis.port",
                () -> String.valueOf(REDIS_CONTAINER.getMappedPort(6379)));
        registry.add("spring.kafka.listener.auto-startup", () -> "true");
    }

    @Autowired
    private PaymentConfirmService paymentConfirmService;

    @Autowired
    private PaymentOutboxUseCase paymentOutboxUseCase;

    @Autowired
    private JpaPaymentEventRepository jpaPaymentEventRepository;

    @Autowired
    private JpaPaymentOrderRepository jpaPaymentOrderRepository;

    @Autowired
    private JpaPaymentOutboxRepository jpaPaymentOutboxRepository;

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

        redisTemplate.opsForValue().set("stock:" + PRODUCT_ID, String.valueOf(INITIAL_STOCK));

        jpaPaymentOrderRepository.deleteAllInBatch();
        jpaPaymentEventRepository.deleteAllInBatch();
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
    }

    @Test
    @DisplayName("재확정 이중차감 0: 확정 TX 실패(outbox UNIQUE 충돌) 후 같은 주문 재확정 → "
            + "decrementAtomic ALREADY_DONE, redis 재고는 -N 한 번만 유지")
    void 재확정_이중차감_0_재고_정확히_N개만_차감() throws Exception {
        // given — 확정 TX 가 반드시 실패하도록 동일 orderId의 outbox PENDING 을 미리 심어둔다.
        String orderId = "order-sri-1-" + UUID.randomUUID();
        String paymentKey = "pay-key-" + orderId;
        saveReadyPayment(orderId);
        paymentOutboxUseCase.createPendingRecord(orderId);

        PaymentConfirmCommand command = buildConfirmCommand(orderId, paymentKey);

        // when — 1차 confirm: decrementAtomic OK(선차감 성공) → executeConfirmTx 가
        // createPendingRecord UNIQUE 위반으로 실패 → 원본 예외 전파, 재고는 보상되지 않고 유지.
        assertThatThrownBy(() -> paymentConfirmService.confirm(command))
                .isInstanceOf(RuntimeException.class);

        assertThat(getStock()).isEqualTo(INITIAL_STOCK - ORDER_QUANTITY);

        // when — 재확정(동일 orderId): decrementAtomic 은 dedup token 때문에 ALREADY_DONE 으로
        // SUCCESS 분기에 들어가지만, outbox row 가 여전히 남아 있어 executeConfirmTx 도 다시 실패한다.
        assertThatThrownBy(() -> paymentConfirmService.confirm(command))
                .isInstanceOf(RuntimeException.class);

        // then — 재확정으로도 redis 재고가 추가로 깎이지 않는다(ALREADY_DONE 재차감 0).
        // 재고 -N 1회만 유지된다는 것이 과매도 0 불변식의 핵심 단언이다.
        assertThat(getStock()).isEqualTo(INITIAL_STOCK - ORDER_QUANTITY);
    }

    @Test
    @DisplayName("동시 confirm 2건: 토큰 SETNX 로 차감 1회만 보장되고, "
            + "충돌로 실패한 건은 재고 무접촉(increment 보상 호출 0)")
    void 동시_confirm_2건_차감_1회_보장_충돌건_재고_무접촉() throws Exception {
        // given — 동시에 confirm 되어도 outbox UNIQUE 제약으로 둘 중 하나만 executeConfirmTx 에 성공한다.
        String orderId = "order-sri-2-" + UUID.randomUUID();
        String paymentKey = "pay-key-" + orderId;
        saveReadyPayment(orderId);

        PaymentConfirmCommand command = buildConfirmCommand(orderId, paymentKey);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Exception> firstFailure = new AtomicReference<>();
        AtomicReference<Exception> secondFailure = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        // when
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

        // then — 정확히 한 건만 성공(outbox UNIQUE 가 나머지를 막는다), 다른 한 건은 실패.
        boolean exactlyOneFailed = (firstFailure.get() == null) != (secondFailure.get() == null);
        assertThat(exactlyOneFailed)
                .as("동시 confirm 2건 중 정확히 한 건만 성공해야 한다(outbox UNIQUE 가 나머지 차단)")
                .isTrue();

        // then — SETNX dedup token 덕에 redis 재고는 정확히 한 번(-N)만 차감된다.
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(getStock()).isEqualTo(INITIAL_STOCK - ORDER_QUANTITY));

    }

    @Test
    @DisplayName("교차 케이스: 선차감 후 확정 TX 실패한 요청과, 같은 주문을 ALREADY_DONE 으로 "
            + "확정 완료한 다른 요청이 교차해도 재고는 -N 1회만 유지(과매도 0)")
    void 교차_케이스_TX_실패_후_ALREADY_DONE_확정_완료_재고_1회만_차감() throws Exception {
        // given — req A 가 확정 TX 에서 실패하도록 outbox row 를 미리 심어둔다.
        String orderId = "order-sri-3-" + UUID.randomUUID();
        String paymentKey = "pay-key-" + orderId;
        saveReadyPayment(orderId);
        paymentOutboxUseCase.createPendingRecord(orderId);

        PaymentConfirmCommand command = buildConfirmCommand(orderId, paymentKey);

        // when — req A: decrementAtomic OK(최초 차감) → executeConfirmTx 가 outbox UNIQUE 위반으로 실패.
        assertThatThrownBy(() -> paymentConfirmService.confirm(command))
                .isInstanceOf(RuntimeException.class);
        assertThat(getStock()).isEqualTo(INITIAL_STOCK - ORDER_QUANTITY);

        // 충돌을 일으킨 outbox row 를 제거 — req B 는 이 row 없이 같은 주문을 재확정한다.
        deletePendingOutbox(orderId);

        // when — req B(같은 orderId 재확정): decrementAtomic 은 dedup token 으로 ALREADY_DONE →
        // SUCCESS 분기. 이번엔 outbox 충돌이 없으므로 executeConfirmTx 가 정상 커밋된다.
        paymentConfirmService.confirm(command);

        // then — req A 의 실패 차감 + req B 의 ALREADY_DONE 재진입을 거쳤어도 재고는 -N 1회만 유지(과매도 0).
        assertThat(getStock()).isEqualTo(INITIAL_STOCK - ORDER_QUANTITY);

        // then — req B 는 IN_PROGRESS 로 정상 확정 완료된다.
        PaymentEventEntity entity = jpaPaymentEventRepository.findByOrderId(orderId).orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(PaymentEventStatus.IN_PROGRESS);
    }

    // ── 내부 헬퍼 ────────────────────────────────────────────────────────────

    private int getStock() {
        String value = redisTemplate.opsForValue().get("stock:" + PRODUCT_ID);
        assertThat(value).isNotNull();
        return Integer.parseInt(value);
    }

    private void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("대기 중 인터럽트", e);
        }
    }

    private void deletePendingOutbox(String orderId) {
        jpaPaymentOutboxRepository.findByOrderId(orderId)
                .ifPresent(jpaPaymentOutboxRepository::delete);
    }

    /**
     * READY 상태의 PaymentEvent + PaymentOrder(EXECUTING) 를 DB 에 저장한다.
     * PaymentOrder 를 EXECUTING 으로 저장하는 이유: {@code PaymentOrder.execute()} 는
     * NOT_STARTED/EXECUTING 에서만 허용되므로, 재confirm(자기전이) 시나리오를 막지 않기 위해
     * StockCompensationRecoveryIntegrationTest 픽스처와 동일하게 EXECUTING 으로 둔다. paymentKey 는
     * confirm 시점에 처음 전달되므로 픽스처에서는 비워 둔다(validateConfirmRequest 는 기존
     * paymentKey 가 null 이면 통과시킨다).
     *
     * @param orderId 주문 ID
     */
    private void saveReadyPayment(String orderId) {
        PaymentEventEntity event = PaymentEventEntity.builder()
                .buyerId(BUYER_ID)
                .sellerId(2L)
                .orderName("테스트 상품 포함 1건")
                .orderId(orderId)
                .gatewayType(PaymentGatewayType.TOSS)
                .status(PaymentEventStatus.READY)
                .retryCount(0)
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
