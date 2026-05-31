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
@DisplayName("stock_decrement_atomic.lua 단위 테스트")
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
    @DisplayName("단일_상품_정상_차감_성공 — KEYS 3개(token, stock1, stock2), 재고 충분 → OK 반환 + 재고 감소")
    void 단일_상품_정상_차감_성공() {
        // given
        String orderId = "order-001";
        String tokenKey = "decrement:done:" + orderId;
        String stockKey1 = "stock:10";
        String stockKey2 = "stock:20";

        redisTemplate.opsForValue().set(stockKey1, "100");
        redisTemplate.opsForValue().set(stockKey2, "50");

        List<String> keys = Arrays.asList(tokenKey, stockKey1, stockKey2);
        // ARGV[1..N] = 차감 수량, ARGV[N+1] = TTL
        String[] args = {"10", "5", String.valueOf(P8D_TTL)};

        // when
        String result = redisTemplate.execute(DECREMENT_ATOMIC_SCRIPT, keys, (Object[]) args);

        // then
        assertThat(result).isEqualTo("OK");
        assertThat(redisTemplate.opsForValue().get(stockKey1)).isEqualTo("90");
        assertThat(redisTemplate.opsForValue().get(stockKey2)).isEqualTo("45");
    }

    @Test
    @DisplayName("재고_부족_시_INSUFFICIENT_반환_및_차감_없음 — stock 부족 → INSUFFICIENT + 기존 재고 보존 + dedup 삭제")
    void 재고_부족_시_INSUFFICIENT_반환_및_차감_없음() {
        // given
        String orderId = "order-002";
        String tokenKey = "decrement:done:" + orderId;
        String stockKey = "stock:30";

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
    @DisplayName("두번째_호출_ALREADY_DONE — 동일 orderId 재호출 → ALREADY_DONE")
    void 두번째_호출_ALREADY_DONE() {
        // given
        String orderId = "order-003";
        String tokenKey = "decrement:done:" + orderId;
        String stockKey = "stock:40";

        redisTemplate.opsForValue().set(stockKey, "100");

        List<String> keys = Arrays.asList(tokenKey, stockKey);
        String[] args = {"5", String.valueOf(P8D_TTL)};

        // 첫 번째 호출 — 성공
        String firstResult = redisTemplate.execute(DECREMENT_ATOMIC_SCRIPT, keys, (Object[]) args);
        assertThat(firstResult).isEqualTo("OK");

        // when — 동일 orderId 재호출
        String secondResult = redisTemplate.execute(DECREMENT_ATOMIC_SCRIPT, keys, (Object[]) args);

        // then
        assertThat(secondResult).isEqualTo("ALREADY_DONE");
        // 재고는 첫 번째 호출 결과 유지 (두 번 차감 안 됨)
        assertThat(redisTemplate.opsForValue().get(stockKey)).isEqualTo("95");
    }

    @Test
    @DisplayName("부분_부족_시_전체_미차감 — 상품A 재고 충분 + 상품B 부족 → INSUFFICIENT + A 재고 그대로")
    void 부분_부족_시_전체_미차감() {
        // given
        String orderId = "order-004";
        String tokenKey = "decrement:done:" + orderId;
        String stockKeyA = "stock:50";
        String stockKeyB = "stock:60";

        redisTemplate.opsForValue().set(stockKeyA, "100"); // 충분
        redisTemplate.opsForValue().set(stockKeyB, "2");   // 부족

        List<String> keys = Arrays.asList(tokenKey, stockKeyA, stockKeyB);
        // A 10개, B 5개 요청 (B 부족)
        String[] args = {"10", "5", String.valueOf(P8D_TTL)};

        // when
        String result = redisTemplate.execute(DECREMENT_ATOMIC_SCRIPT, keys, (Object[]) args);

        // then
        assertThat(result).isEqualTo("INSUFFICIENT");
        // A 재고 그대로 — 부분 차감 없음
        assertThat(redisTemplate.opsForValue().get(stockKeyA)).isEqualTo("100");
        assertThat(redisTemplate.opsForValue().get(stockKeyB)).isEqualTo("2");
    }

    @Test
    @DisplayName("dedup_token_TTL_설정_확인 — SETNX 후 TTL 조회 → P8D 범위 내")
    void dedup_token_TTL_설정_확인() {
        // given
        String orderId = "order-005";
        String tokenKey = "decrement:done:" + orderId;
        String stockKey = "stock:70";

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
