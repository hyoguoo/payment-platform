package com.hyoguoo.paymentplatform.payment.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hyoguoo.paymentplatform.payment.application.port.out.StockCompensationAtomicResult;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockDecrementAtomicResult;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DisplayName("StockCacheRedisAdapter 테스트")
class StockCacheRedisAdapterTest {

    @Container
    static final GenericContainer<?> REDIS_CONTAINER = new GenericContainer<>("redis:7.2-alpine")
            .withCommand("redis-server", "--appendonly", "yes")
            .withExposedPorts(6379);

    private StockCacheRedisAdapter adapter;
    private StringRedisTemplate redisTemplate;
    private LettuceConnectionFactory connectionFactory;

    private static final Long PRODUCT_ID = 42L;

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

        adapter = new StockCacheRedisAdapter(redisTemplate);
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
    @DisplayName("재고가 충분할 때 차감 성공 후 true를 반환하고 재고가 줄어든다")
    void decrement_WhenSufficientStock_ShouldDecrementAndReturnTrue() {
        // given
        adapter.set(PRODUCT_ID, 10);

        // when
        boolean result = adapter.decrement(PRODUCT_ID, 3);

        // then
        assertThat(result).isTrue();
        assertThat(adapter.current(PRODUCT_ID)).isEqualTo(7);
    }

    @Test
    @DisplayName("차감 시 재고가 음수가 되면 Lua가 복구하고 false를 반환하며 재고는 유지된다")
    void decrement_WhenStockWouldGoNegative_ShouldRollbackAndReturnFalse() {
        // given
        adapter.set(PRODUCT_ID, 5);

        // when
        boolean result = adapter.decrement(PRODUCT_ID, 10);

        // then
        assertThat(result).isFalse();
        assertThat(adapter.current(PRODUCT_ID)).isEqualTo(5);
    }

    @Test
    @DisplayName("동시 차감 시 Lua 원자성 보장 — 재고(100)는 절대 음수가 되지 않고 정확히 100번 성공한다")
    void decrement_Concurrent_ShouldBeAtomicAndNeverGoNegative() throws Exception {
        // given
        int initialStock = 100;
        int totalRequests = 200;
        adapter.set(PRODUCT_ID, initialStock);

        ExecutorService executor = Executors.newFixedThreadPool(50);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger trueCount = new AtomicInteger(0);
        AtomicInteger falseCount = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < totalRequests; i++) {
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                boolean decremented = adapter.decrement(PRODUCT_ID, 1);
                if (decremented) {
                    trueCount.incrementAndGet();
                } else {
                    falseCount.incrementAndGet();
                }
            }));
        }

        // when
        startLatch.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        executor.shutdown();

        // then
        assertThat(trueCount.get()).isEqualTo(100);
        assertThat(falseCount.get()).isEqualTo(100);
        assertThat(adapter.current(PRODUCT_ID)).isEqualTo(0);
    }

    @Test
    @DisplayName("rollback 호출 시 지정 수량만큼 재고가 증가한다")
    void rollback_ShouldIncrementStock() {
        // given
        adapter.set(PRODUCT_ID, 10);

        // when
        adapter.rollback(PRODUCT_ID, 5);

        // then
        assertThat(adapter.current(PRODUCT_ID)).isEqualTo(15);
    }

    @Test
    @DisplayName("Redis 연결 실패 시 예외가 상위로 전파된다 — swallow 금지")
    void decrement_WhenRedisDown_ShouldPropagateException() {
        // given — 잘못된 호스트로 연결하는 별도 어댑터 인스턴스 생성
        RedisStandaloneConfiguration badConfig = new RedisStandaloneConfiguration("invalid-host-that-does-not-exist", 6379);
        LettuceConnectionFactory badFactory = new LettuceConnectionFactory(badConfig);
        badFactory.afterPropertiesSet();

        StringRedisTemplate badTemplate = new StringRedisTemplate(badFactory);
        badTemplate.afterPropertiesSet();

        StockCacheRedisAdapter brokenAdapter = new StockCacheRedisAdapter(badTemplate);

        // when / then
        assertThatThrownBy(() -> brokenAdapter.decrement(PRODUCT_ID, 1))
                .isInstanceOf(DataAccessException.class);

        badFactory.destroy();
    }

    @Test
    @DisplayName("decrementAtomic — 2개 상품 정상 차감 시 OK를 반환하고 재고가 줄어든다")
    void decrementAtomic_2개_상품_정상_차감() {
        // given
        Long productId1 = 101L;
        Long productId2 = 102L;
        adapter.set(productId1, 10);
        adapter.set(productId2, 5);
        List<PaymentOrder> orders = List.of(
                buildOrder(productId1, 3),
                buildOrder(productId2, 2)
        );

        // when
        StockDecrementAtomicResult result = adapter.decrementAtomic("order-scr4-001", orders);

        // then
        assertThat(result).isEqualTo(StockDecrementAtomicResult.OK);
        assertThat(adapter.current(productId1)).isEqualTo(7);
        assertThat(adapter.current(productId2)).isEqualTo(3);
    }

    @Test
    @DisplayName("decrementAtomic — 재고 부족 시 INSUFFICIENT를 반환하고 재고는 불변이다")
    void decrementAtomic_재고_부족_INSUFFICIENT() {
        // given
        Long productId = 103L;
        adapter.set(productId, 1);
        List<PaymentOrder> orders = List.of(buildOrder(productId, 5));

        // when
        StockDecrementAtomicResult result = adapter.decrementAtomic("order-scr4-002", orders);

        // then
        assertThat(result).isEqualTo(StockDecrementAtomicResult.INSUFFICIENT);
        assertThat(adapter.current(productId)).isEqualTo(1);
    }

    @Test
    @DisplayName("decrementAtomic — 동일 orderId 재호출 시 ALREADY_DONE을 반환한다")
    void decrementAtomic_중복_ALREADY_DONE() {
        // given
        Long productId = 104L;
        adapter.set(productId, 10);
        List<PaymentOrder> orders = List.of(buildOrder(productId, 3));
        adapter.decrementAtomic("order-scr4-003", orders);

        // when
        StockDecrementAtomicResult result = adapter.decrementAtomic("order-scr4-003", orders);

        // then
        assertThat(result).isEqualTo(StockDecrementAtomicResult.ALREADY_DONE);
    }

    @Test
    @DisplayName("compensateAtomic — 2개 상품 정상 복원 시 OK를 반환하고 재고가 증가한다")
    void compensateAtomic_2개_상품_정상_복원() {
        // given
        Long productId1 = 105L;
        Long productId2 = 106L;
        adapter.set(productId1, 5);
        adapter.set(productId2, 3);
        List<PaymentOrder> orders = List.of(
                buildOrder(productId1, 3),
                buildOrder(productId2, 2)
        );

        // when
        StockCompensationAtomicResult result = adapter.compensateAtomic("order-scr4-004", orders);

        // then
        assertThat(result).isEqualTo(StockCompensationAtomicResult.OK);
        assertThat(adapter.current(productId1)).isEqualTo(8);
        assertThat(adapter.current(productId2)).isEqualTo(5);
    }

    @Test
    @DisplayName("compensateAtomic — 동일 orderId 재호출 시 ALREADY_DONE을 반환하고 재고는 불변이다")
    void compensateAtomic_중복_ALREADY_DONE() {
        // given
        Long productId = 107L;
        adapter.set(productId, 5);
        List<PaymentOrder> orders = List.of(buildOrder(productId, 3));
        adapter.compensateAtomic("order-scr4-005", orders);

        // when
        StockCompensationAtomicResult result = adapter.compensateAtomic("order-scr4-005", orders);

        // then
        assertThat(result).isEqualTo(StockCompensationAtomicResult.ALREADY_DONE);
        assertThat(adapter.current(productId)).isEqualTo(8); // 첫 번째 보상 결과만 반영
    }

    // --- helpers ---

    private PaymentOrder buildOrder(Long productId, int quantity) {
        return PaymentOrder.allArgsBuilder()
                .id(productId)
                .paymentEventId(1L)
                .orderId("order-test")
                .productId(productId)
                .quantity(quantity)
                .totalAmount(BigDecimal.valueOf(1000))
                .status(PaymentOrderStatus.EXECUTING)
                .allArgsBuild();
    }
}
