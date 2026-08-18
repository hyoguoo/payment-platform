package com.hyoguoo.paymentplatform.payment.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Optional;
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

/**
 * 확정 진입 시 상품 반복 전체를 감싸는 주문 단위 선점(acquireOrderLock/releaseOrderLock) 계약 테스트.
 */
@Testcontainers
@DisplayName("StockCacheRedisAdapter — 주문 단위 선점 테스트")
class StockCacheRedisAdapterOrderLockTest {

    @Container
    static final GenericContainer<?> REDIS_CONTAINER = new GenericContainer<>("redis:7.2-alpine")
            .withCommand("redis-server", "--appendonly", "yes")
            .withExposedPorts(6379);

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
    @DisplayName("선점을 잡으면 토큰을 돌려주고, 같은 주문번호로 다시 잡으면 실패한다")
    void 선점_성공_후_같은_주문번호_재선점_실패() {
        // given
        StockCacheRedisAdapter adapter = newAdapter(30);
        String orderId = "order-lock-001";

        // when
        Optional<String> first = adapter.acquireOrderLock(orderId);
        Optional<String> second = adapter.acquireOrderLock(orderId);

        // then
        assertThat(first).isPresent();
        assertThat(second).isEmpty();
    }

    @Test
    @DisplayName("명시적으로 풀면 같은 주문번호로 다시 잡을 수 있다")
    void 명시적_해제_후_재선점_성공() {
        // given
        StockCacheRedisAdapter adapter = newAdapter(30);
        String orderId = "order-lock-002";
        Optional<String> lockToken = adapter.acquireOrderLock(orderId);
        assertThat(lockToken).isPresent();

        // when
        adapter.releaseOrderLock(orderId, lockToken.orElseThrow());
        Optional<String> reacquired = adapter.acquireOrderLock(orderId);

        // then
        assertThat(reacquired).isPresent();
    }

    @Test
    @DisplayName("수명이 지나면 선점이 저절로 풀려 다른 요청이 잡을 수 있다")
    void 수명_경과_후_자동_해제() {
        // given — 회수용 backup 을 짧은 수명으로 단정
        StockCacheRedisAdapter adapter = newAdapter(1);
        String orderId = "order-lock-003";
        adapter.acquireOrderLock(orderId);

        // when / then
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(adapter.acquireOrderLock(orderId)).isPresent());
    }

    @Test
    @DisplayName("다른 주문번호는 서로 선점을 막지 않는다")
    void 다른_주문번호는_서로_막지_않는다() {
        // given
        StockCacheRedisAdapter adapter = newAdapter(30);

        // when
        Optional<String> orderA = adapter.acquireOrderLock("order-lock-004-a");
        Optional<String> orderB = adapter.acquireOrderLock("order-lock-004-b");

        // then
        assertThat(orderA).isPresent();
        assertThat(orderB).isPresent();
    }

    @Test
    @DisplayName("토큰이 일치하지 않으면 해제되지 않는다 — 수명이 지나 재획득된 다른 요청의 선점을 지우지 않는다")
    void 토큰_불일치시_해제되지_않는다() {
        // given
        StockCacheRedisAdapter adapter = newAdapter(30);
        String orderId = "order-lock-005";
        adapter.acquireOrderLock(orderId);

        // when — 다른(가짜) 토큰으로 해제 시도
        adapter.releaseOrderLock(orderId, "다른-요청의-토큰이-아님");

        // then — 여전히 선점 중이라 재선점은 실패한다
        assertThat(adapter.acquireOrderLock(orderId)).isEmpty();
    }

    private StockCacheRedisAdapter newAdapter(long orderLockTtlSeconds) {
        return new StockCacheRedisAdapter(redisTemplate, orderLockTtlSeconds);
    }
}
