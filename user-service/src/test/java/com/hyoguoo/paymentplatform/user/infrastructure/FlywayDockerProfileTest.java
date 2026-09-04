package com.hyoguoo.paymentplatform.user.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * docker profile 에서 Flyway seed(V2) 가 적용됨을 검증하는 통합 테스트.
 *
 * <p>application-docker.yml 은 {@code spring.flyway.locations: classpath:db/schema,classpath:db/seed}
 * 로 V2__seed_user.sql 을 반드시 포함한다. 새 볼륨으로 스택을 띄우면 user 테이블이 비어 있어
 * 결제 부하 도구의 checkout 이 전량 실패하기 때문이다. 시드는 {@code INSERT IGNORE} 라 멱등이고
 * 실환경 배포에서는 값이 이미 있으면 no-op 이다.
 *
 * <p>docker-java 기본 API 버전(1.32)이 Docker 29.4.2 최소 지원 버전(1.40)보다 낮아
 * src/test/resources/docker-java.properties 에서 api.version=1.44 로 고정한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@Tag("integration")
@ActiveProfiles("docker")
class FlywayDockerProfileTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_unicode_ci"
            );

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.flyway.url", mysql::getJdbcUrl);
        registry.add("spring.flyway.user", mysql::getUsername);
        registry.add("spring.flyway.password", mysql::getPassword);
        // Kafka, Eureka 는 이 테스트에서 불필요
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("eureka.client.enabled", () -> "false");
        registry.add("eureka.client.register-with-eureka", () -> "false");
        registry.add("eureka.client.fetch-registry", () -> "false");
    }

    @Autowired
    private DataSource dataSource;

    /**
     * docker profile 에서 V2 seed 가 적용됨을 검증한다.
     *
     * <ol>
     *   <li>flyway_schema_history 에 V1 + V2 record 모두 존재</li>
     *   <li>user 테이블 row count = V2__seed_user.sql 삽입 행 수(1)</li>
     * </ol>
     */
    @Test
    @DisplayName("docker profile — V2 seed 적용: flyway_schema_history V1+V2 + user row count 1")
    void dockerProfile_appliesSeedMigration() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            // 케이스 1: flyway_schema_history 에 V1 + V2 record 모두 존재
            int historyCount = queryCount(conn, "SELECT COUNT(*) FROM flyway_schema_history");
            assertThat(historyCount)
                    .as("flyway_schema_history row count — V1 + V2 모두 적용되어야 한다")
                    .isEqualTo(2);

            // 케이스 2: user 테이블 row count = V2__seed_user.sql 삽입 행 수
            int userCount = queryCount(conn, "SELECT COUNT(*) FROM `user`");
            assertThat(userCount)
                    .as("user 테이블 row count — docker profile 에서 seed 가 적용되어야 한다")
                    .isEqualTo(1);
        }
    }

    private int queryCount(Connection conn, String sql) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
