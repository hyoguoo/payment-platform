package com.hyoguoo.paymentplatform.payment.core.test;

import com.hyoguoo.paymentplatform.mock.TestLocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.port.out.ProductPort;
import com.hyoguoo.paymentplatform.payment.application.port.out.UserPort;
import com.hyoguoo.paymentplatform.payment.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.UserInfo;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Sql(scripts = "/data-test.sql")
@Tag("integration")
@Import(BaseIntegrationTest.BaseTestConfig.class)
public abstract class BaseIntegrationTest {

    static final MySQLContainer<?> MYSQL_CONTAINER;

    /**
     * 기본 Redis(redis-dedupe) 컨테이너 — IdempotencyStoreRedisAdapter 가 사용하는 인스턴스.
     * {@code spring.data.redis.host/port} 로 등록된다.
     * checkout 통합 테스트(PaymentControllerTest, PaymentCheckoutConcurrencyIntegrationTest)가
     * idempotency-key 저장에 이 컨테이너를 사용한다.
     */
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS_CONTAINER;

    static {
        MYSQL_CONTAINER = new MySQLContainer<>("mysql:8.0")
                .withDatabaseName("payment-test")
                .withUsername("test")
                .withPassword("test")
                .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci")
                // D3/D7 — raw-JDBC(JdbcTemplate) 와 JPA 가 같은 connection 세션 TZ(UTC) 를 공유하도록
                // 강제한다. 이 파라미터가 getJdbcUrl() 에 자동 부착되어 LocalDateTime/Instant ↔ DATETIME
                // 라운드트립이 시스템 TZ 와 무관하게 UTC 기준으로 일관된다.
                .withUrlParam("connectionTimeZone", "UTC")
                .withUrlParam("forceConnectionTimeZoneToSession", "true")
                .withReuse(true);
        REDIS_CONTAINER = new GenericContainer<>("redis:7.2-alpine")
                .withExposedPorts(6379)
                .withReuse(true);
        // @Testcontainers/@Container 를 사용하지 않고 수동 start.
        // @Container 로 관리하면 JUnit5 extension 이 테스트 클래스 완료 후 stop() 을 명시 호출하여
        // withReuse(true) 설정에도 불구하고 컨테이너가 종료된다.
        MYSQL_CONTAINER.start();
        REDIS_CONTAINER.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // D3/D7 — raw-JDBC 및 JPA 경로 모두 UTC 기준으로 통일.
        // connectionTimeZone=UTC 는 컨테이너 빌더의 withUrlParam 으로 getJdbcUrl 에 부착된다.
        // hibernate.jdbc.time_zone=UTC 를 명시 등록해 ORM 바인딩도 UTC 기준으로 고정한다.
        registry.add("spring.datasource.url", MYSQL_CONTAINER::getJdbcUrl);
        registry.add("spring.jpa.properties.hibernate.jdbc.time_zone", () -> "UTC");
        registry.add("spring.datasource.username", MYSQL_CONTAINER::getUsername);
        registry.add("spring.datasource.password", MYSQL_CONTAINER::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        // IdempotencyStoreRedisAdapter 가 사용하는 default Redis(RedisConfig).
        registry.add("spring.data.redis.host", REDIS_CONTAINER::getHost);
        registry.add("spring.data.redis.port",
                () -> String.valueOf(REDIS_CONTAINER.getMappedPort(6379)));
        // StockRedisConfig 가 요구하는 stock-redis 는 checkout 경로에서 미사용이나,
        // Spring 컨텍스트 구동 시 LettuceConnectionFactory 가 wiring 된다.
        // 연결 실패를 막기 위해 동일 컨테이너 포트를 가리키도록 한다.
        registry.add("payment.cache.stock-redis.host", REDIS_CONTAINER::getHost);
        registry.add("payment.cache.stock-redis.port",
                () -> String.valueOf(REDIS_CONTAINER.getMappedPort(6379)));
        registry.add("scheduler.enabled", () -> "true");
    }

    @TestConfiguration
    static class BaseTestConfig {

        @Bean
        @Primary
        public LocalDateTimeProvider localDateTimeProvider() {
            return new TestLocalDateTimeProvider();
        }

        /**
         * ProductPort Fake bean — product-service HTTP 미기동 환경에서 checkout 통합 테스트를 지원한다.
         * {@code @Primary} 로 ProductHttpAdapter 보다 우선 주입되어 HTTP 호출 없이 product 정보를 반환한다.
         * 테스트 데이터: productId=1L(이름="테스트 상품1", 가격=10000, 재고=100, sellerId=2L),
         *               productId=2L(이름="테스트 상품2", 가격=5000, 재고=50, sellerId=2L).
         */
        @Bean
        @Primary
        public ProductPort productPort() {
            Map<Long, ProductInfo> products = Map.of(
                    1L, ProductInfo.builder()
                            .id(1L)
                            .name("테스트 상품1")
                            .price(BigDecimal.valueOf(10000))
                            .stock(100)
                            .sellerId(2L)
                            .build(),
                    2L, ProductInfo.builder()
                            .id(2L)
                            .name("테스트 상품2")
                            .price(BigDecimal.valueOf(5000))
                            .stock(50)
                            .sellerId(2L)
                            .build()
            );
            return productId -> Optional.ofNullable(products.get(productId))
                    .orElseThrow(() -> new IllegalArgumentException("테스트 상품 없음: productId=" + productId));
        }

        /**
         * UserPort Fake bean — user-service HTTP 미기동 환경에서 checkout 통합 테스트를 지원한다.
         * {@code @Primary} 로 UserHttpAdapter 보다 우선 주입되어 HTTP 호출 없이 사용자 정보를 반환한다.
         * 테스트 데이터: userId=1L 을 고정 반환.
         */
        @Bean
        @Primary
        public UserPort userPort() {
            return userId -> UserInfo.builder().id(userId).build();
        }
    }
}
