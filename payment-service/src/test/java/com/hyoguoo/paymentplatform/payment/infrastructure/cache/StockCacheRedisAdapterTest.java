package com.hyoguoo.paymentplatform.payment.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.payment.application.port.out.StockDecrementAtomicResult;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockRecoveryCompensationResult;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import java.math.BigDecimal;
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

        adapter = new StockCacheRedisAdapter(redisTemplate, 30);
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
    @DisplayName("decrementAtomic(단일 상품) — 정상 차감 시 OK를 반환하고 재고를 줄이며 상품 기준 해시태그 형태의 선차감 표시를 남긴다")
    void decrementAtomic_단일_상품_메서드_정상_차감() {
        // given
        Long productId = 140L;
        setStock(productId, 10);
        PaymentOrder order = buildOrder(productId, 3);

        // when
        StockDecrementAtomicResult result = adapter.decrementAtomic("order-scr4-single-method-001", order);

        // then
        assertThat(result).isEqualTo(StockDecrementAtomicResult.OK);
        assertThat(getStock(productId)).isEqualTo(7);
        assertThat(existsDecrementDoneToken(productId, "order-scr4-single-method-001")).isTrue();
        assertThat(redisTemplate.hasKey("decrement:done:{140}:order-scr4-single-method-001")).isTrue();
        assertThat(redisTemplate.hasKey("stock:{140}")).isTrue();
    }

    @Test
    @DisplayName("decrementAtomic(단일 상품) — 재고 부족 시 INSUFFICIENT를 반환하고 선차감 표시를 남기지 않는다")
    void decrementAtomic_단일_상품_메서드_재고_부족() {
        // given
        Long productId = 141L;
        setStock(productId, 1);
        PaymentOrder order = buildOrder(productId, 5);

        // when
        StockDecrementAtomicResult result = adapter.decrementAtomic("order-scr4-single-method-002", order);

        // then
        assertThat(result).isEqualTo(StockDecrementAtomicResult.INSUFFICIENT);
        assertThat(getStock(productId)).isEqualTo(1);
        assertThat(existsDecrementDoneToken(productId, "order-scr4-single-method-002")).isFalse();
    }

    @Test
    @DisplayName("decrementAtomic(단일 상품) — 동일 주문·상품 재호출 시 ALREADY_DONE을 반환하고 재고가 더 줄지 않는다")
    void decrementAtomic_단일_상품_메서드_중복_ALREADY_DONE() {
        // given
        Long productId = 142L;
        setStock(productId, 10);
        PaymentOrder order = buildOrder(productId, 3);
        adapter.decrementAtomic("order-scr4-single-method-003", order);

        // when
        StockDecrementAtomicResult result = adapter.decrementAtomic("order-scr4-single-method-003", order);

        // then
        assertThat(result).isEqualTo(StockDecrementAtomicResult.ALREADY_DONE);
        assertThat(getStock(productId)).isEqualTo(7);
    }

    @Test
    @DisplayName("compensateIfDecremented(단일 상품) — decrement:done 존재 시 OK를 반환하고 재고를 복원한다")
    void compensateIfDecremented_단일_상품_메서드_정상_복원() {
        // given
        Long productId = 143L;
        setStock(productId, 5);
        PaymentOrder order = buildOrder(productId, 3);
        setDecrementDoneToken(productId, "order-scr-single-method-recovery-001");

        // when
        StockRecoveryCompensationResult result =
                adapter.compensateIfDecremented("order-scr-single-method-recovery-001", order);

        // then
        assertThat(result).isEqualTo(StockRecoveryCompensationResult.OK);
        assertThat(getStock(productId)).isEqualTo(8);
    }

    @Test
    @DisplayName("compensateIfDecremented(단일 상품) — decrement:done 부재 시 NO_DECREMENT를 반환하고 재고가 불변이다")
    void compensateIfDecremented_단일_상품_메서드_흔적_없음() {
        // given
        Long productId = 144L;
        setStock(productId, 5);
        PaymentOrder order = buildOrder(productId, 3);

        // when
        StockRecoveryCompensationResult result =
                adapter.compensateIfDecremented("order-scr-single-method-recovery-002", order);

        // then
        assertThat(result).isEqualTo(StockRecoveryCompensationResult.NO_DECREMENT);
        assertThat(getStock(productId)).isEqualTo(5);
    }

    @Test
    @DisplayName("compensateIfDecremented(단일 상품) — 이미 보상됐으면 ALREADY_DONE을 반환하고 재고는 불변이다")
    void compensateIfDecremented_단일_상품_메서드_이미_보상됨_ALREADY_DONE() {
        // given
        Long productId = 146L;
        setStock(productId, 5);
        PaymentOrder order = buildOrder(productId, 3);
        String orderId = "order-scr-single-method-recovery-003";
        setDecrementDoneToken(productId, orderId);
        adapter.compensateIfDecremented(orderId, order);

        // when
        StockRecoveryCompensationResult result = adapter.compensateIfDecremented(orderId, order);

        // then
        assertThat(result).isEqualTo(StockRecoveryCompensationResult.ALREADY_DONE);
        assertThat(getStock(productId)).isEqualTo(8); // 첫 번째 보상 결과만 반영
    }

    @Test
    @DisplayName("compensateIfDecremented(단일 상품) — decrement:done 부재 + compensation:done 존재 시에도 NO_DECREMENT를 반환하고 재고는 불변이다")
    void compensateIfDecremented_단일_상품_메서드_decrement_부재_compensation_존재_NO_DECREMENT() {
        // given
        Long productId = 147L;
        setStock(productId, 5);
        PaymentOrder order = buildOrder(productId, 3);
        String orderId = "order-scr-single-method-recovery-004";
        setCompensationDoneToken(productId, orderId); // decrement:done 없이 compensation:done 만 남은 이례 상태

        // when
        StockRecoveryCompensationResult result = adapter.compensateIfDecremented(orderId, order);

        // then
        assertThat(result).isEqualTo(StockRecoveryCompensationResult.NO_DECREMENT);
        assertThat(getStock(productId)).isEqualTo(5);
    }

    @Test
    @DisplayName("rejectCompensate(단일 상품) — 재고를 복원하면서 선차감 표시와 되돌리기 표시를 함께 지운다")
    void rejectCompensate_단일_상품_메서드_재고_복원과_표시_삭제() {
        // given
        Long productId = 145L;
        setStock(productId, 5);
        PaymentOrder order = buildOrder(productId, 3);
        String orderId = "order-scr-single-method-reject-001";
        setDecrementDoneToken(productId, orderId);
        setCompensationDoneToken(productId, orderId);

        // when
        adapter.rejectCompensate(orderId, order);

        // then
        assertThat(getStock(productId)).isEqualTo(8);
        assertThat(existsDecrementDoneToken(productId, orderId)).isFalse();
        assertThat(existsCompensationDoneToken(productId, orderId)).isFalse();
    }

    // --- helpers ---

    private void setStock(Long productId, int quantity) {
        redisTemplate.opsForValue().set(stockKey(productId), String.valueOf(quantity));
    }

    private int getStock(Long productId) {
        String value = redisTemplate.opsForValue().get(stockKey(productId));
        return value == null ? 0 : Integer.parseInt(value);
    }

    private void setDecrementDoneToken(Long productId, String orderId) {
        redisTemplate.opsForValue().set(decrementDoneKey(productId, orderId), "1");
    }

    private void setCompensationDoneToken(Long productId, String orderId) {
        redisTemplate.opsForValue().set(compensationDoneKey(productId, orderId), "1");
    }

    private boolean existsCompensationDoneToken(Long productId, String orderId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(compensationDoneKey(productId, orderId)));
    }

    private boolean existsDecrementDoneToken(Long productId, String orderId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(decrementDoneKey(productId, orderId)));
    }

    private String stockKey(Long productId) {
        return "stock:{" + productId + "}";
    }

    private String decrementDoneKey(Long productId, String orderId) {
        return "decrement:done:{" + productId + "}:" + orderId;
    }

    private String compensationDoneKey(Long productId, String orderId) {
        return "compensation:done:{" + productId + "}:" + orderId;
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
