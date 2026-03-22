package com.hyoguoo.paymentplatform.core.test;

import com.hyoguoo.paymentplatform.core.common.infrastructure.http.HttpOperator;
import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.mock.FakeTossHttpOperator;
import com.hyoguoo.paymentplatform.mock.TestLocalDateTimeProvider;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
@Import(BaseIntegrationTest.BaseTestConfig.class)
public abstract class BaseIntegrationTest {

    static final MySQLContainer<?> MYSQL_CONTAINER;

    static {
        MYSQL_CONTAINER = new MySQLContainer<>("mysql:8.0")
                .withDatabaseName("payment-test")
                .withUsername("test")
                .withPassword("test")
                .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci")
                .withReuse(true);
        MYSQL_CONTAINER.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL_CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL_CONTAINER::getUsername);
        registry.add("spring.datasource.password", MYSQL_CONTAINER::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");

        registry.add("scheduler.enabled", () -> "true");
    }

    @TestConfiguration
    static class BaseTestConfig {

        @Bean
        public HttpOperator httpOperator() {
            return new FakeTossHttpOperator();
        }

        @Bean
        public LocalDateTimeProvider localDateTimeProvider() {
            return new TestLocalDateTimeProvider();
        }
    }
}
