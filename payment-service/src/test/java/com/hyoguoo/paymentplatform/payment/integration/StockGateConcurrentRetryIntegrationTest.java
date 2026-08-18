package com.hyoguoo.paymentplatform.payment.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.payment.application.port.out.StockDecrementAtomicResult;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockHoldRecordSnapshot;
import com.hyoguoo.paymentplatform.payment.application.util.StockHoldReverter;
import com.hyoguoo.paymentplatform.payment.core.config.ClockConfig;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.StockHoldRecordStatus;
import com.hyoguoo.paymentplatform.payment.infrastructure.cache.StockCacheRedisAdapter;
import com.hyoguoo.paymentplatform.payment.infrastructure.repository.JpaStockHoldRecordRepository;
import com.hyoguoo.paymentplatform.payment.infrastructure.repository.StockHoldRecordRepositoryImpl;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 거절 후 재시도가 실제로 재고까지 되돌아오는지, 그리고 되돌리기와 기록 닫기 사이의 창에 새
 * 차감이 끼어들어도 뒤늦은 닫기가 그 차감을 덮지 않는지 검증한다.
 *
 * <p>주문번호·상품번호 유일 제약은 Flyway 마이그레이션에만 있고 JPA 엔티티 애너테이션에는 없다
 * — 다른 재고 게이트 통합 테스트가 쓰는 {@code BaseIntegrationTest}는 테스트 프로파일에서
 * {@code ddl-auto: create-drop} + Flyway 비활성으로 스키마를 만들어 이 제약이 실제로 서지 않는다.
 * 이 클래스는 같은 주문·상품 조합에 {@code openHold}를 두 사이클 이상 걸쳐 부르므로(재시도 시나리오)
 * 제약이 없으면 행이 갈라져 재오픈이 아니라 중복 삽입이 된다 — {@code StockHoldRecordRepositoryImplTest}
 * (Task 5)와 같은 방식으로 Flyway 를 켜고 {@code ddl-auto: validate} 로 실제 스키마 위에서 검증한다.
 *
 * <p>동시 중복 확정 차단은 {@code PaymentDuplicateConfirmConcurrencyIntegrationTest}가, 완료된
 * 결제 재확정 시 캐시 호출이 없는 것은 {@code PaymentTransactionCoordinatorTest}가 이미 값
 * 단정까지 포함해 검증한다 — 이 클래스는 그 두 시나리오와 겹치지 않는 나머지 둘만 다룬다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({StockHoldRecordRepositoryImpl.class, ClockConfig.class})
@Testcontainers
@DisplayName("재고 게이트 거절-재시도 재고 정합 및 닫기 경합 통합 테스트")
class StockGateConcurrentRetryIntegrationTest {

    private static final int INITIAL_STOCK = 1_000;
    private static final int QUANTITY = 5;
    private static final Long PRODUCT_ID = 601L;
    private static final String ORDER_ID_REJECT_RETRY = "order-reject-retry-601";
    private static final String ORDER_ID_CLOSE_RACE = "order-close-race-602";

    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL_CONTAINER =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("stock-gate-retry-test")
                    .withUsername("test")
                    .withPassword("test")
                    .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci")
                    .withReuse(true);

    @Container
    static final GenericContainer<?> REDIS_CONTAINER = new GenericContainer<>("redis:7.2-alpine")
            .withCommand("redis-server", "--appendonly", "yes")
            .withExposedPorts(6379);

    static {
        MYSQL_CONTAINER.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL_CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL_CONTAINER::getUsername);
        registry.add("spring.datasource.password", MYSQL_CONTAINER::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    private StockHoldRecordRepositoryImpl stockHoldRecordRepository;

    @Autowired
    private JpaStockHoldRecordRepository jpaStockHoldRecordRepository;

    private StockCacheRedisAdapter stockCachePort;
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
        stockCachePort = new StockCacheRedisAdapter(redisTemplate, 30);
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
    }

    @Test
    @DisplayName("거절 후 재시도가 그 상품을 다시 차감하고, 그 차감이 되돌아가면 재고가 실제로 원래 값으로 복원된다")
    void 거절_후_재시도_차감이_실제로_재고까지_되돌아온다() {
        // given — 1주기: 직접 차감 후 거절 전용 되돌리기로 두 표시를 함께 지운다
        PaymentOrder order = buildOrder(ORDER_ID_REJECT_RETRY, PRODUCT_ID, QUANTITY);
        redisTemplate.opsForValue().set(stockKey(PRODUCT_ID), String.valueOf(INITIAL_STOCK));

        stockHoldRecordRepository.openHold(ORDER_ID_REJECT_RETRY, order);
        StockDecrementAtomicResult firstDecrement = stockCachePort.decrementAtomic(ORDER_ID_REJECT_RETRY, order);
        assertThat(firstDecrement).isEqualTo(StockDecrementAtomicResult.OK);

        stockCachePort.rejectCompensate(ORDER_ID_REJECT_RETRY, order);
        assertThat(currentStock(PRODUCT_ID))
                .as("사전조건 — 거절 전용 되돌리기 직후 재고가 원래 값으로 복원돼야 한다")
                .isEqualTo(INITIAL_STOCK);

        // when — 2주기: 두 표시가 지워졌으므로 같은 조합이 다시 직접 차감된다(이미 처리됨이 아니다)
        String secondCycleToken = stockHoldRecordRepository.openHold(ORDER_ID_REJECT_RETRY, order);
        StockDecrementAtomicResult secondDecrement = stockCachePort.decrementAtomic(ORDER_ID_REJECT_RETRY, order);
        assertThat(secondDecrement)
                .as("거절이 두 표시를 함께 지웠으므로 재시도는 이미 처리됨이 아니라 실제로 다시 차감돼야 한다")
                .isEqualTo(StockDecrementAtomicResult.OK);
        assertThat(currentStock(PRODUCT_ID)).isEqualTo(INITIAL_STOCK - QUANTITY);

        // 2주기가 벤더 실패로 종결돼 조건부 되돌리기를 탄다
        stockCachePort.compensateIfDecremented(ORDER_ID_REJECT_RETRY, order);
        stockHoldRecordRepository.closeAsReverted(ORDER_ID_REJECT_RETRY, order, secondCycleToken);

        // then — 기록만 REVERTED 로 닫혀서는 부족하다. 되돌리기 표시가 앞 주기에서 남아 있었다면
        // 여기서 ALREADY_DONE 으로 흡수돼 재고가 차감된 채 봉인됐을 것이다
        assertThat(currentStock(PRODUCT_ID))
                .as("두 번째 차감도 실제로 재고까지 원래 값으로 복원돼야 한다")
                .isEqualTo(INITIAL_STOCK);
        StockHoldRecordSnapshot snapshot =
                stockHoldRecordRepository.findSnapshot(ORDER_ID_REJECT_RETRY, order).orElseThrow();
        assertThat(snapshot.status()).isEqualTo(StockHoldRecordStatus.REVERTED);
    }

    /**
     * {@code @DataJpaTest} 기본 트랜잭션(테스트당 단일 커넥션·롤백)을 그대로 쓰면 메인 스레드가
     * 쥔 미커밋 행 락을 백그라운드 스레드의 {@code closeAsReverted} 가 기다리다 서로 막혀 버린다
     * — {@code StockHoldRecordRepositoryImplTest} 의 동시 삽입 테스트와 같은 이유로
     * {@code NOT_SUPPORTED} 로 각 스레드가 독립 TX·커넥션을 쓰게 한다. 그만큼 자동 롤백이
     * 없어 끝에서 수동 정리한다.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("닫기 경합 — 캐시 되돌리기 후 기록 닫기 전에 새 차감이 들어오면 뒤늦은 닫기가 그 차감을 덮지 않는다")
    void 닫기_경합_뒤늦은_닫기가_새_차감을_덮지_않는다()
            throws InterruptedException, ExecutionException, TimeoutException {
        // given — 거절로 종결된 1주기의 기록은 닫히지 않은 채 남는다(거절 전용은 캐시만 되돌리고
        // 기록은 건드리지 않는다) — 이 잔여 기록을 뒤늦게 되돌리려는 주체가 있다고 가정한다
        PaymentOrder order = buildOrder(ORDER_ID_CLOSE_RACE, PRODUCT_ID, QUANTITY);
        redisTemplate.opsForValue().set(stockKey(PRODUCT_ID), String.valueOf(INITIAL_STOCK));

        String staleCycleToken = stockHoldRecordRepository.openHold(ORDER_ID_CLOSE_RACE, order);
        StockDecrementAtomicResult firstDecrement = stockCachePort.decrementAtomic(ORDER_ID_CLOSE_RACE, order);
        assertThat(firstDecrement).isEqualTo(StockDecrementAtomicResult.OK);
        stockCachePort.rejectCompensate(ORDER_ID_CLOSE_RACE, order);

        CountDownLatch pauseReachedBeforeClose = new CountDownLatch(1);
        CountDownLatch resumeCloseSignal = new CountDownLatch(1);
        StockHoldReverter delayedReverter = new StockHoldReverter(new SimpleMeterRegistry()) {
            @Override
            protected void beforeClose(String hookOrderId, PaymentOrder hookOrder) {
                pauseReachedBeforeClose.countDown();
                awaitQuietly(resumeCloseSignal);
            }
        };

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            raceLateCloseAgainstNewDecrement(
                    order, staleCycleToken, delayedReverter, executor,
                    pauseReachedBeforeClose, resumeCloseSignal);
        } finally {
            executor.shutdownNow();
        }

        // cleanup — NOT_SUPPORTED 이므로 테스트 프레임워크의 자동 롤백이 없어 수동 정리
        jpaStockHoldRecordRepository.deleteAll();
    }

    /**
     * 지연 주입 지점에서 멈춘 되돌리기 스레드를 붙잡아 둔 채 같은 조합에 새 차감을 끼워 넣고,
     * 재개된 뒤늦은 닫기가 그 새 차감을 덮지 않는지까지 한 흐름으로 단정한다.
     */
    private void raceLateCloseAgainstNewDecrement(
            PaymentOrder order,
            String staleCycleToken,
            StockHoldReverter delayedReverter,
            ExecutorService executor,
            CountDownLatch pauseReachedBeforeClose,
            CountDownLatch resumeCloseSignal)
            throws InterruptedException, ExecutionException, TimeoutException {
        Future<?> lateRevertFuture = executor.submit(() -> delayedReverter.revertProductHold(
                ORDER_ID_CLOSE_RACE, order, staleCycleToken, stockCachePort, stockHoldRecordRepository));

        awaitOrFail(pauseReachedBeforeClose);

        // when — 되돌리기와 닫기 사이의 창에 같은 조합의 새 차감이 들어온다(재시도)
        String newCycleToken = stockHoldRecordRepository.openHold(ORDER_ID_CLOSE_RACE, order);
        StockDecrementAtomicResult newDecrement = stockCachePort.decrementAtomic(ORDER_ID_CLOSE_RACE, order);
        assertThat(newDecrement)
                .as("거절이 두 표시를 지웠으므로 새 차감도 실제로 성공해야 한다")
                .isEqualTo(StockDecrementAtomicResult.OK);
        assertThat(newCycleToken).isNotEqualTo(staleCycleToken);

        resumeCloseSignal.countDown();
        lateRevertFuture.get(10, TimeUnit.SECONDS);

        // then — 뒤늦은 닫기는 옛 사이클 식별 값을 들고 있어 반영되지 않는다. 반영됐다면 새 차감이
        // 진행 중인데도 기록이 REVERTED 로 닫혀 회수 대상에서 영영 빠지는 유령 누수가 됐을 것이다
        StockHoldRecordSnapshot snapshot =
                stockHoldRecordRepository.findSnapshot(ORDER_ID_CLOSE_RACE, order).orElseThrow();
        assertThat(snapshot.status())
                .as("새 차감이 연 사이클은 뒤늦은 닫기에 덮이지 않고 잡음 상태 그대로 남아야 한다")
                .isEqualTo(StockHoldRecordStatus.NOISE);
        assertThat(snapshot.cycleToken()).isEqualTo(newCycleToken);
        assertThat(currentStock(PRODUCT_ID))
                .as("새 차감은 뒤늦은 닫기와 무관하게 그대로 유지돼야 한다")
                .isEqualTo(INITIAL_STOCK - QUANTITY);
    }

    // ── 내부 헬퍼 ────────────────────────────────────────────────────────────

    private int currentStock(long productId) {
        String value = redisTemplate.opsForValue().get(stockKey(productId));
        if (value == null) {
            throw new IllegalStateException("재고 키가 존재하지 않는다: " + stockKey(productId));
        }
        return Integer.parseInt(value);
    }

    private String stockKey(long productId) {
        return "stock:{" + productId + "}";
    }

    private PaymentOrder buildOrder(String orderId, long productId, int quantity) {
        return PaymentOrder.allArgsBuilder()
                .orderId(orderId)
                .productId(productId)
                .quantity(quantity)
                .totalAmount(BigDecimal.valueOf(1_000L * quantity))
                .status(PaymentOrderStatus.EXECUTING)
                .allArgsBuild();
    }

    private void awaitOrFail(CountDownLatch latch) throws InterruptedException {
        boolean reached = latch.await(10, TimeUnit.SECONDS);
        if (!reached) {
            throw new IllegalStateException("지연 주입 지점에 10초 안에 도달하지 못했다");
        }
    }

    private void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("대기 중 인터럽트", e);
        }
    }
}
