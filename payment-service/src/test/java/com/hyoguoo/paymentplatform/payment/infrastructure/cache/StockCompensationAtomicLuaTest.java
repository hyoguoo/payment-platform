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
@DisplayName("stock_compensation_atomic.lua 단위 테스트")
class StockCompensationAtomicLuaTest {

    @Container
    static final GenericContainer<?> REDIS_CONTAINER = new GenericContainer<>("redis:7.2-alpine")
            .withCommand("redis-server", "--appendonly", "yes")
            .withExposedPorts(6379);

    private static final DefaultRedisScript<String> COMPENSATION_ATOMIC_SCRIPT;

    static {
        COMPENSATION_ATOMIC_SCRIPT = new DefaultRedisScript<>();
        COMPENSATION_ATOMIC_SCRIPT.setLocation(new ClassPathResource("lua/stock_compensation_atomic.lua"));
        COMPENSATION_ATOMIC_SCRIPT.setResultType(String.class);
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
    @DisplayName("단일_상품_정상_보상_성공 — 재고 0 + INCRBY qty → OK + 재고 증가")
    void 단일_상품_정상_보상_성공() {
        // given
        String orderId = "order-comp-001";
        String tokenKey = "compensation:done:" + orderId;
        String stockKey = "stock:10";

        redisTemplate.opsForValue().set(stockKey, "0");

        List<String> keys = Arrays.asList(tokenKey, stockKey);
        // ARGV[1..N] = 복원 수량, ARGV[N+1] = TTL
        String[] args = {"5", String.valueOf(P8D_TTL)};

        // when
        String result = redisTemplate.execute(COMPENSATION_ATOMIC_SCRIPT, keys, (Object[]) args);

        // then
        assertThat(result).isEqualTo("OK");
        assertThat(redisTemplate.opsForValue().get(stockKey)).isEqualTo("5");
    }

    @Test
    @DisplayName("복수_상품_atomic_보상 — N개 상품 한 번에 복원 → 모두 증가")
    void 복수_상품_atomic_보상() {
        // given
        String orderId = "order-comp-002";
        String tokenKey = "compensation:done:" + orderId;
        String stockKey1 = "stock:20";
        String stockKey2 = "stock:30";
        String stockKey3 = "stock:40";

        redisTemplate.opsForValue().set(stockKey1, "10");
        redisTemplate.opsForValue().set(stockKey2, "0");
        redisTemplate.opsForValue().set(stockKey3, "5");

        List<String> keys = Arrays.asList(tokenKey, stockKey1, stockKey2, stockKey3);
        // A 3개, B 7개, C 2개 복원
        String[] args = {"3", "7", "2", String.valueOf(P8D_TTL)};

        // when
        String result = redisTemplate.execute(COMPENSATION_ATOMIC_SCRIPT, keys, (Object[]) args);

        // then
        assertThat(result).isEqualTo("OK");
        assertThat(redisTemplate.opsForValue().get(stockKey1)).isEqualTo("13");
        assertThat(redisTemplate.opsForValue().get(stockKey2)).isEqualTo("7");
        assertThat(redisTemplate.opsForValue().get(stockKey3)).isEqualTo("7");
    }

    @Test
    @DisplayName("두번째_호출_ALREADY_DONE — 동일 orderId 재호출 → ALREADY_DONE + 재고 변화 없음")
    void 두번째_호출_ALREADY_DONE() {
        // given
        String orderId = "order-comp-003";
        String tokenKey = "compensation:done:" + orderId;
        String stockKey = "stock:50";

        redisTemplate.opsForValue().set(stockKey, "0");

        List<String> keys = Arrays.asList(tokenKey, stockKey);
        String[] args = {"10", String.valueOf(P8D_TTL)};

        // 첫 번째 호출 — 성공
        String firstResult = redisTemplate.execute(COMPENSATION_ATOMIC_SCRIPT, keys, (Object[]) args);
        assertThat(firstResult).isEqualTo("OK");
        assertThat(redisTemplate.opsForValue().get(stockKey)).isEqualTo("10");

        // when — 동일 orderId 재호출
        String secondResult = redisTemplate.execute(COMPENSATION_ATOMIC_SCRIPT, keys, (Object[]) args);

        // then
        assertThat(secondResult).isEqualTo("ALREADY_DONE");
        // 재고는 첫 번째 호출 결과 유지 (이중 복원 없음)
        assertThat(redisTemplate.opsForValue().get(stockKey)).isEqualTo("10");
    }

    @Test
    @DisplayName("다른_orderId_는_독립적 — orderId1 ALREADY_DONE 이어도 orderId2 정상 보상")
    void 다른_orderId_는_독립적() {
        // given
        String orderId1 = "order-comp-004";
        String orderId2 = "order-comp-005";
        String tokenKey1 = "compensation:done:" + orderId1;
        String tokenKey2 = "compensation:done:" + orderId2;
        String stockKey = "stock:60";

        redisTemplate.opsForValue().set(stockKey, "0");

        List<String> keys1 = Arrays.asList(tokenKey1, stockKey);
        String[] args1 = {"5", String.valueOf(P8D_TTL)};

        // orderId1 두 번 호출 → 두 번째는 ALREADY_DONE
        String firstResult = redisTemplate.execute(COMPENSATION_ATOMIC_SCRIPT, keys1, (Object[]) args1);
        assertThat(firstResult).isEqualTo("OK");
        String dupResult = redisTemplate.execute(COMPENSATION_ATOMIC_SCRIPT, keys1, (Object[]) args1);
        assertThat(dupResult).isEqualTo("ALREADY_DONE");

        List<String> keys2 = Arrays.asList(tokenKey2, stockKey);
        String[] args2 = {"3", String.valueOf(P8D_TTL)};

        // when — orderId2 는 독립적으로 정상 보상 가능
        String result2 = redisTemplate.execute(COMPENSATION_ATOMIC_SCRIPT, keys2, (Object[]) args2);

        // then
        assertThat(result2).isEqualTo("OK");
        // orderId1 에서 +5, orderId2 에서 +3 → 총 8
        assertThat(redisTemplate.opsForValue().get(stockKey)).isEqualTo("8");
    }

    @Test
    @DisplayName("dedup_token_TTL_설정_확인 — SETNX 후 TTL 조회 → P8D 범위 내")
    void dedup_token_TTL_설정_확인() {
        // given
        String orderId = "order-comp-006";
        String tokenKey = "compensation:done:" + orderId;
        String stockKey = "stock:70";

        redisTemplate.opsForValue().set(stockKey, "0");

        List<String> keys = Arrays.asList(tokenKey, stockKey);
        String[] args = {"1", String.valueOf(P8D_TTL)};

        // when
        String result = redisTemplate.execute(COMPENSATION_ATOMIC_SCRIPT, keys, (Object[]) args);

        // then
        assertThat(result).isEqualTo("OK");
        Long ttl = redisTemplate.getExpire(tokenKey);
        assertThat(ttl).isNotNull();
        // TTL 은 P8D(691200) 이하이며 0보다 커야 함
        assertThat(ttl).isGreaterThan(0L);
        assertThat(ttl).isLessThanOrEqualTo(P8D_TTL);
    }
}
