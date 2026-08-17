package com.hyoguoo.paymentplatform.payment.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DisplayName("stock_decrement_atomic.lua 단위 테스트 — 상품 단위")
class StockDecrementAtomicLuaTest {

    @Container
    static final GenericContainer<?> REDIS_CONTAINER = new GenericContainer<>("redis:7.2-alpine")
            .withCommand("redis-server", "--appendonly", "yes")
            .withExposedPorts(6379);

    private static final DefaultRedisScript<String> DECREMENT_ATOMIC_SCRIPT;

    static {
        DECREMENT_ATOMIC_SCRIPT = new DefaultRedisScript<>();
        DECREMENT_ATOMIC_SCRIPT.setLocation(new ClassPathResource("lua/stock_decrement_atomic.lua"));
        DECREMENT_ATOMIC_SCRIPT.setResultType(String.class);
    }

    private static final long P8D_TTL = 691200L;

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
    @DisplayName("단일_상품_정상_차감_성공 — KEYS 2개(token, stock), 재고 충분 → OK 반환 + 재고 감소")
    void 단일_상품_정상_차감_성공() {
        // given
        String orderId = "order-001";
        String tokenKey = "decrement:done:{10}:" + orderId;
        String stockKey = "stock:{10}";

        redisTemplate.opsForValue().set(stockKey, "100");

        List<String> keys = Arrays.asList(tokenKey, stockKey);
        String[] args = {"10", String.valueOf(P8D_TTL)};

        // when
        String result = redisTemplate.execute(DECREMENT_ATOMIC_SCRIPT, keys, (Object[]) args);

        // then
        assertThat(result).isEqualTo("OK");
        assertThat(redisTemplate.opsForValue().get(stockKey)).isEqualTo("90");
    }

    @Test
    @DisplayName("재고_부족_시_INSUFFICIENT_반환_및_차감_없음 — stock 부족 → INSUFFICIENT + 기존 재고 보존 + dedup 삭제")
    void 재고_부족_시_INSUFFICIENT_반환_및_차감_없음() {
        // given
        String orderId = "order-002";
        String tokenKey = "decrement:done:{30}:" + orderId;
        String stockKey = "stock:{30}";

        redisTemplate.opsForValue().set(stockKey, "3");

        List<String> keys = Arrays.asList(tokenKey, stockKey);
        String[] args = {"10", String.valueOf(P8D_TTL)};

        // when
        String result = redisTemplate.execute(DECREMENT_ATOMIC_SCRIPT, keys, (Object[]) args);

        // then
        assertThat(result).isEqualTo("INSUFFICIENT");
        assertThat(redisTemplate.opsForValue().get(stockKey)).isEqualTo("3");
        // dedup token 이 삭제되어 재시도 가능해야 함
        assertThat(redisTemplate.hasKey(tokenKey)).isFalse();
    }

    @Test
    @DisplayName("두번째_호출_ALREADY_DONE — 동일 상품·주문 조합 재호출 → ALREADY_DONE")
    void 두번째_호출_ALREADY_DONE() {
        // given
        String orderId = "order-003";
        String tokenKey = "decrement:done:{40}:" + orderId;
        String stockKey = "stock:{40}";

        redisTemplate.opsForValue().set(stockKey, "100");

        List<String> keys = Arrays.asList(tokenKey, stockKey);
        String[] args = {"5", String.valueOf(P8D_TTL)};

        // 첫 번째 호출 — 성공
        String firstResult = redisTemplate.execute(DECREMENT_ATOMIC_SCRIPT, keys, (Object[]) args);
        assertThat(firstResult).isEqualTo("OK");

        // when — 동일 상품·주문 조합 재호출
        String secondResult = redisTemplate.execute(DECREMENT_ATOMIC_SCRIPT, keys, (Object[]) args);

        // then
        assertThat(secondResult).isEqualTo("ALREADY_DONE");
        // 재고는 첫 번째 호출 결과 유지 (두 번 차감 안 됨)
        assertThat(redisTemplate.opsForValue().get(stockKey)).isEqualTo("95");
    }

    @Test
    @DisplayName("다른_상품은_서로_막지_않는다 — 상품A 부족해도 상품B 는 정상 차감")
    void 다른_상품은_서로_막지_않는다() {
        // given
        String orderId = "order-004";
        String tokenKeyA = "decrement:done:{50}:" + orderId;
        String stockKeyA = "stock:{50}";
        String tokenKeyB = "decrement:done:{60}:" + orderId;
        String stockKeyB = "stock:{60}";

        redisTemplate.opsForValue().set(stockKeyA, "2"); // 부족
        redisTemplate.opsForValue().set(stockKeyB, "100"); // 충분

        // when
        String resultA = redisTemplate.execute(
                DECREMENT_ATOMIC_SCRIPT, Arrays.asList(tokenKeyA, stockKeyA),
                (Object[]) new String[]{"10", String.valueOf(P8D_TTL)});
        String resultB = redisTemplate.execute(
                DECREMENT_ATOMIC_SCRIPT, Arrays.asList(tokenKeyB, stockKeyB),
                (Object[]) new String[]{"10", String.valueOf(P8D_TTL)});

        // then
        assertThat(resultA).isEqualTo("INSUFFICIENT");
        assertThat(resultB).isEqualTo("OK");
        assertThat(redisTemplate.opsForValue().get(stockKeyA)).isEqualTo("2");
        assertThat(redisTemplate.opsForValue().get(stockKeyB)).isEqualTo("90");
    }

    @Test
    @DisplayName("dedup_token_TTL_설정_확인 — SETNX 후 TTL 조회 → P8D 범위 내")
    void dedup_token_TTL_설정_확인() {
        // given
        String orderId = "order-005";
        String tokenKey = "decrement:done:{70}:" + orderId;
        String stockKey = "stock:{70}";

        redisTemplate.opsForValue().set(stockKey, "100");

        List<String> keys = Arrays.asList(tokenKey, stockKey);
        String[] args = {"1", String.valueOf(P8D_TTL)};

        // when
        String result = redisTemplate.execute(DECREMENT_ATOMIC_SCRIPT, keys, (Object[]) args);

        // then
        assertThat(result).isEqualTo("OK");
        Long ttl = redisTemplate.getExpire(tokenKey);
        assertThat(ttl).isNotNull();
        // TTL 은 P8D(691200) 이하 이며 0보다 커야 함
        assertThat(ttl).isGreaterThan(0L);
        assertThat(ttl).isLessThanOrEqualTo(P8D_TTL);
    }
}
