package com.hyoguoo.paymentplatform.core.test;

import com.hyoguoo.paymentplatform.core.common.infrastructure.http.HttpOperator;
import com.hyoguoo.paymentplatform.mock.FakeTossHttpOperator;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
@Import(BaseKafkaIntegrationTest.BaseKafkaTestConfig.class)
public abstract class BaseKafkaIntegrationTest {

    static final MySQLContainer<?> MYSQL_CONTAINER;
    static final KafkaContainer KAFKA_CONTAINER;

    static {
        MYSQL_CONTAINER = new MySQLContainer<>("mysql:8.0")
                .withDatabaseName("payment-test")
                .withUsername("test")
                .withPassword("test")
                .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci")
                .withReuse(true);
        MYSQL_CONTAINER.start();

        KAFKA_CONTAINER = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.0"))
                .withKraft()
                .withReuse(true);
        KAFKA_CONTAINER.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL_CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL_CONTAINER::getUsername);
        registry.add("spring.datasource.password", MYSQL_CONTAINER::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.kafka.bootstrap-servers", KAFKA_CONTAINER::getBootstrapServers);
        registry.add("spring.payment.async-strategy", () -> "kafka");
        registry.add("scheduler.enabled", () -> "true");
    }

    @TestConfiguration
    static class BaseKafkaTestConfig {

        @Bean
        public HttpOperator httpOperator() {
            return new FakeTossHttpOperator();
        }
    }
}
