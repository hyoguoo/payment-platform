package com.hyoguoo.paymentplatform.payment.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.payment.application.port.out.StockCompensationAtomicResult;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockDecrementAtomicResult;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockRecoveryCompensationResult;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
    @DisplayName("decrementAtomic — 2개 상품 정상 차감 시 OK를 반환하고 재고가 줄어든다")
    void decrementAtomic_2개_상품_정상_차감() {
        // given
        Long productId1 = 101L;
        Long productId2 = 102L;
        setStock(productId1, 10);
        setStock(productId2, 5);
        List<PaymentOrder> orders = List.of(
                buildOrder(productId1, 3),
                buildOrder(productId2, 2)
        );

        // when
        StockDecrementAtomicResult result = adapter.decrementAtomic("order-scr4-001", orders);

        // then
        assertThat(result).isEqualTo(StockDecrementAtomicResult.OK);
        assertThat(getStock(productId1)).isEqualTo(7);
        assertThat(getStock(productId2)).isEqualTo(3);
    }

    @Test
    @DisplayName("decrementAtomic — 재고 부족 시 INSUFFICIENT를 반환하고 재고는 불변이다")
    void decrementAtomic_재고_부족_INSUFFICIENT() {
        // given
        Long productId = 103L;
        setStock(productId, 1);
        List<PaymentOrder> orders = List.of(buildOrder(productId, 5));

        // when
        StockDecrementAtomicResult result = adapter.decrementAtomic("order-scr4-002", orders);

        // then
        assertThat(result).isEqualTo(StockDecrementAtomicResult.INSUFFICIENT);
        assertThat(getStock(productId)).isEqualTo(1);
    }

    @Test
    @DisplayName("decrementAtomic — 동일 orderId 재호출 시 ALREADY_DONE을 반환한다")
    void decrementAtomic_중복_ALREADY_DONE() {
        // given
        Long productId = 104L;
        setStock(productId, 10);
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
        setStock(productId1, 5);
        setStock(productId2, 3);
        List<PaymentOrder> orders = List.of(
                buildOrder(productId1, 3),
                buildOrder(productId2, 2)
        );

        // when
        StockCompensationAtomicResult result = adapter.compensateAtomic("order-scr4-004", orders);

        // then
        assertThat(result).isEqualTo(StockCompensationAtomicResult.OK);
        assertThat(getStock(productId1)).isEqualTo(8);
        assertThat(getStock(productId2)).isEqualTo(5);
    }

    @Test
    @DisplayName("compensateAtomic — 동일 orderId 재호출 시 ALREADY_DONE을 반환하고 재고는 불변이다")
    void compensateAtomic_중복_ALREADY_DONE() {
        // given
        Long productId = 107L;
        setStock(productId, 5);
        List<PaymentOrder> orders = List.of(buildOrder(productId, 3));
        adapter.compensateAtomic("order-scr4-005", orders);

        // when
        StockCompensationAtomicResult result = adapter.compensateAtomic("order-scr4-005", orders);

        // then
        assertThat(result).isEqualTo(StockCompensationAtomicResult.ALREADY_DONE);
        assertThat(getStock(productId)).isEqualTo(8); // 첫 번째 보상 결과만 반영
    }

    @Test
    @DisplayName("compensateIfDecremented — decrement:done 부재 시 NO_DECREMENT를 반환하고 재고·compensation:done 모두 불변이다")
    void compensateIfDecremented_decrement_토큰_부재_NO_DECREMENT() {
        // given
        Long productId = 108L;
        setStock(productId, 5);
        List<PaymentOrder> orders = List.of(buildOrder(productId, 3));

        // when
        StockRecoveryCompensationResult result = adapter.compensateIfDecremented("order-scr-recovery-001", orders);

        // then
        assertThat(result).isEqualTo(StockRecoveryCompensationResult.NO_DECREMENT);
        assertThat(getStock(productId)).isEqualTo(5);
        assertThat(existsCompensationDoneToken("order-scr-recovery-001")).isFalse();
    }

    @Test
    @DisplayName("compensateIfDecremented — decrement:done 존재 + compensation:done 부재 시 OK를 반환하고 재고를 복원한다")
    void compensateIfDecremented_decrement_존재_미보상_OK() {
        // given
        Long productId = 109L;
        setStock(productId, 5);
        List<PaymentOrder> orders = List.of(buildOrder(productId, 3));
        setDecrementDoneToken("order-scr-recovery-002");

        // when
        StockRecoveryCompensationResult result = adapter.compensateIfDecremented("order-scr-recovery-002", orders);

        // then
        assertThat(result).isEqualTo(StockRecoveryCompensationResult.OK);
        assertThat(getStock(productId)).isEqualTo(8);
        assertThat(existsCompensationDoneToken("order-scr-recovery-002")).isTrue();
    }

    @Test
    @DisplayName("compensateIfDecremented — decrement:done, compensation:done 모두 존재 시 ALREADY_DONE을 반환하고 재고는 불변이다")
    void compensateIfDecremented_이미_보상됨_ALREADY_DONE() {
        // given
        Long productId = 110L;
        setStock(productId, 5);
        List<PaymentOrder> orders = List.of(buildOrder(productId, 3));
        setDecrementDoneToken("order-scr-recovery-003");
        adapter.compensateIfDecremented("order-scr-recovery-003", orders);

        // when
        StockRecoveryCompensationResult result = adapter.compensateIfDecremented("order-scr-recovery-003", orders);

        // then
        assertThat(result).isEqualTo(StockRecoveryCompensationResult.ALREADY_DONE);
        assertThat(getStock(productId)).isEqualTo(8); // 첫 번째 보상 결과만 반영
    }

    @Test
    @DisplayName("compensateIfDecremented — decrement:done 부재 + compensation:done 존재 시에도 NO_DECREMENT를 반환하고 재고는 불변이다")
    void compensateIfDecremented_decrement_부재_compensation_존재_NO_DECREMENT() {
        // given
        Long productId = 111L;
        setStock(productId, 5);
        List<PaymentOrder> orders = List.of(buildOrder(productId, 3));
        setCompensationDoneToken("order-scr-recovery-004"); // decrement:done 없이 compensation:done 만 남은 이례 상태

        // when
        StockRecoveryCompensationResult result = adapter.compensateIfDecremented("order-scr-recovery-004", orders);

        // then
        assertThat(result).isEqualTo(StockRecoveryCompensationResult.NO_DECREMENT);
        assertThat(getStock(productId)).isEqualTo(5);
    }

    // --- helpers ---

    private void setStock(Long productId, int quantity) {
        redisTemplate.opsForValue().set("stock:" + productId, String.valueOf(quantity));
    }

    private int getStock(Long productId) {
        String value = redisTemplate.opsForValue().get("stock:" + productId);
        return value == null ? 0 : Integer.parseInt(value);
    }

    private void setDecrementDoneToken(String orderId) {
        redisTemplate.opsForValue().set("decrement:done:" + orderId, "1");
    }

    private void setCompensationDoneToken(String orderId) {
        redisTemplate.opsForValue().set("compensation:done:" + orderId, "1");
    }

    private boolean existsCompensationDoneToken(String orderId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey("compensation:done:" + orderId));
    }

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
