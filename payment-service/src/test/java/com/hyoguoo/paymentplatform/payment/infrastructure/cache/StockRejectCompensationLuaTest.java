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
@DisplayName("stock_reject_compensation.lua 단위 테스트 — 거절 전용 되돌리기")
class StockRejectCompensationLuaTest {

    @Container
    static final GenericContainer<?> REDIS_CONTAINER = new GenericContainer<>("redis:7.2-alpine")
            .withCommand("redis-server", "--appendonly", "yes")
            .withExposedPorts(6379);

    private static final DefaultRedisScript<String> REJECT_COMPENSATION_SCRIPT;

    static {
        REJECT_COMPENSATION_SCRIPT = new DefaultRedisScript<>();
        REJECT_COMPENSATION_SCRIPT.setLocation(new ClassPathResource("lua/stock_reject_compensation.lua"));
        REJECT_COMPENSATION_SCRIPT.setResultType(String.class);
    }

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
    @DisplayName("재고를_복원하면서_선차감_표시와_되돌리기_표시를_모두_지운다")
    void 재고_복원_및_두_표시_모두_삭제() {
        // given — 선차감이 이미 일어난 상태(선차감 표시 존재), 되돌리기 표시는 없음
        String orderId = "order-reject-001";
        String decrementDoneKey = "decrement:done:{10}:" + orderId;
        String compensationDoneKey = "compensation:done:{10}:" + orderId;
        String stockKey = "stock:{10}";

        redisTemplate.opsForValue().set(stockKey, "7"); // 10개 중 3개 차감된 상태
        redisTemplate.opsForValue().set(decrementDoneKey, "1");

        List<String> keys = Arrays.asList(decrementDoneKey, compensationDoneKey, stockKey);
        String[] args = {"3"};

        // when
        String result = redisTemplate.execute(REJECT_COMPENSATION_SCRIPT, keys, (Object[]) args);

        // then
        assertThat(result).isEqualTo("OK");
        assertThat(redisTemplate.opsForValue().get(stockKey)).isEqualTo("10");
        assertThat(redisTemplate.hasKey(decrementDoneKey)).isFalse();
        assertThat(redisTemplate.hasKey(compensationDoneKey)).isFalse();
    }

    @Test
    @DisplayName("두_표시가_지워지면_같은_조합의_차감이_다시_성공한다")
    void 되돌린_뒤_재차감_성공() {
        // given
        String orderId = "order-reject-002";
        String decrementDoneKey = "decrement:done:{20}:" + orderId;
        String compensationDoneKey = "compensation:done:{20}:" + orderId;
        String stockKey = "stock:{20}";

        redisTemplate.opsForValue().set(stockKey, "5");
        redisTemplate.opsForValue().set(decrementDoneKey, "1");

        redisTemplate.execute(
                REJECT_COMPENSATION_SCRIPT,
                Arrays.asList(decrementDoneKey, compensationDoneKey, stockKey),
                (Object[]) new String[]{"5"});

        // when — 되돌린 뒤 같은 상품·주문 조합으로 다시 차감을 시도한다
        DefaultRedisScript<String> decrementScript = new DefaultRedisScript<>();
        decrementScript.setLocation(new ClassPathResource("lua/stock_decrement_atomic.lua"));
        decrementScript.setResultType(String.class);

        String result = redisTemplate.execute(
                decrementScript,
                Arrays.asList(decrementDoneKey, stockKey),
                (Object[]) new String[]{"5", "691200"});

        // then
        assertThat(result).isEqualTo("OK");
        assertThat(redisTemplate.opsForValue().get(stockKey)).isEqualTo("5");
    }

    @Test
    @DisplayName("기존에_되돌리기_표시가_남아있어도_함께_지워진다")
    void 기존_되돌리기_표시도_삭제된다() {
        // given — 이전 사이클에서 되돌리기 표시가 남아 있는 이례 상태를 재현
        String orderId = "order-reject-003";
        String decrementDoneKey = "decrement:done:{30}:" + orderId;
        String compensationDoneKey = "compensation:done:{30}:" + orderId;
        String stockKey = "stock:{30}";

        redisTemplate.opsForValue().set(stockKey, "8");
        redisTemplate.opsForValue().set(decrementDoneKey, "1");
        redisTemplate.opsForValue().set(compensationDoneKey, "1");

        List<String> keys = Arrays.asList(decrementDoneKey, compensationDoneKey, stockKey);
        String[] args = {"2"};

        // when
        String result = redisTemplate.execute(REJECT_COMPENSATION_SCRIPT, keys, (Object[]) args);

        // then
        assertThat(result).isEqualTo("OK");
        assertThat(redisTemplate.opsForValue().get(stockKey)).isEqualTo("10");
        assertThat(redisTemplate.hasKey(decrementDoneKey)).isFalse();
        assertThat(redisTemplate.hasKey(compensationDoneKey)).isFalse();
    }
}
