package com.hyoguoo.paymentplatform.payment.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hyoguoo.paymentplatform.payment.application.messaging.PaymentTopics;
import com.hyoguoo.paymentplatform.payment.application.port.out.ProductPort;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

/**
 * 재고 캐시 수동 resync admin endpoint 통합테스트.
 *
 * <p>검증 범위 — endpoint 라우팅 + use-case 와이어링 + 실제 redis SET 어댑터를 한 흐름으로:
 * <ul>
 *   <li>redis 캐시가 product RDB(SoT)와 발산한 상태에서 {@code POST /admin/stock/resync/{productId}} 호출 시
 *       product RDB stock 으로 캐시를 덮어쓰고 그 수량을 200 응답으로 돌려준다.</li>
 *   <li>실제 redis(Testcontainer)의 {@code stock:{productId}} 값이 RDB 값으로 바뀐 것을 직접 단정한다.</li>
 * </ul>
 *
 * <p>product RDB 조회는 외부 product-service HTTP 라 {@link ProductPort} 를 mock 으로 대체하고,
 * redis 쓰기는 실제 {@code StockCacheRedisAdapter} 경로를 탄다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("integration")
@EmbeddedKafka(
        partitions = 1,
        topics = {PaymentTopics.EVENTS_CONFIRMED},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@DisplayName("재고 캐시 수동 resync admin endpoint 통합테스트")
class StockResyncAdminIntegrationTest {

    private static final Long PRODUCT_ID = 300L;
    private static final int STALE_CACHE_STOCK = 4;
    private static final int RDB_STOCK = 25;

    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL_CONTAINER =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("payment-resync-test")
                    .withUsername("test")
                    .withPassword("test")
                    .withCommand(
                            "--character-set-server=utf8mb4",
                            "--collation-server=utf8mb4_unicode_ci"
                    )
                    .withReuse(true);

    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS_CONTAINER =
            new GenericContainer<>("redis:7.2-alpine")
                    .withExposedPorts(6379)
                    .withReuse(true);

    static {
        // @Testcontainers/@Container 미사용 — 후속 통합테스트와 컨테이너 재사용(withReuse) 유지.
        MYSQL_CONTAINER.start();
        REDIS_CONTAINER.start();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL_CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL_CONTAINER::getUsername);
        registry.add("spring.datasource.password", MYSQL_CONTAINER::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.defer-datasource-initialization", () -> "false");
        registry.add("spring.data.redis.host", REDIS_CONTAINER::getHost);
        registry.add("spring.data.redis.port",
                () -> String.valueOf(REDIS_CONTAINER.getMappedPort(6379)));
        registry.add("payment.cache.stock-redis.host", REDIS_CONTAINER::getHost);
        registry.add("payment.cache.stock-redis.port",
                () -> String.valueOf(REDIS_CONTAINER.getMappedPort(6379)));
        registry.add("scheduler.enabled", () -> "false");
    }

    @MockitoBean
    private ProductPort productPort;

    @Autowired
    private MockMvc mockMvc;

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
    @DisplayName("POST /admin/stock/resync/{productId} — 발산한 redis 캐시를 product RDB stock 으로 덮어쓰고 그 값을 응답한다")
    void resync_overwritesStaleCacheWithRdbStock() throws Exception {
        // given — redis 캐시가 RDB(SoT)와 발산한 상태 (캐시 4 vs RDB 25)
        redisTemplate.opsForValue().set("stock:" + PRODUCT_ID, String.valueOf(STALE_CACHE_STOCK));
        when(productPort.getProductInfoById(PRODUCT_ID))
                .thenReturn(ProductInfo.builder().id(PRODUCT_ID).stock(RDB_STOCK).build());

        // when & then — endpoint 라우팅 + 응답 본문 (공통 응답 래퍼가 data 로 감싼다)
        mockMvc.perform(post("/admin/stock/resync/{productId}", PRODUCT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productId").value(PRODUCT_ID.intValue()))
                .andExpect(jsonPath("$.data.quantity").value(RDB_STOCK));

        // then — 실제 redis 캐시가 product RDB 값으로 덮어써짐
        String cached = redisTemplate.opsForValue().get("stock:" + PRODUCT_ID);
        assertThat(cached).isEqualTo(String.valueOf(RDB_STOCK));
    }
}
