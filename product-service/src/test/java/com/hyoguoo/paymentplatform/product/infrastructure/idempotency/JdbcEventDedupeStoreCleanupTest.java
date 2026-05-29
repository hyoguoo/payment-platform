package com.hyoguoo.paymentplatform.product.infrastructure.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * JdbcEventDedupeStore.deleteExpired Testcontainers 통합 테스트.
 *
 * <p>만료 행만 삭제·미만료 행 잔존, batchSize 제한, 미만료 existsValid 보존 3개 시나리오 검증.
 * product-service Flyway V1(schema only) 자동 적용 환경.
 */
@SpringBootTest
@Testcontainers
@Tag("integration")
@DisplayName("JdbcEventDedupeStore deleteExpired 통합 테스트")
class JdbcEventDedupeStoreCleanupTest {

    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL_CONTAINER =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("product-test")
                    .withUsername("test")
                    .withPassword("test")
                    .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci")
                    .withReuse(true);

    static {
        MYSQL_CONTAINER.start();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL_CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL_CONTAINER::getUsername);
        registry.add("spring.datasource.password", MYSQL_CONTAINER::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/schema");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.sql.init.mode", () -> "never");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("eureka.client.enabled", () -> "false");
        registry.add("eureka.client.register-with-eureka", () -> "false");
        registry.add("eureka.client.fetch-registry", () -> "false");
    }

    @Autowired
    private JdbcEventDedupeStore dedupeStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTable() {
        jdbcTemplate.update("TRUNCATE TABLE stock_commit_dedupe");
    }

    // ────────────────────────────────────────────────────────────
    // 헬퍼
    // ────────────────────────────────────────────────────────────

    private void insertRow(String eventUuid, Instant expiresAt) {
        jdbcTemplate.update(
                "INSERT INTO stock_commit_dedupe (event_uuid, expires_at) VALUES (?, ?)",
                eventUuid,
                Timestamp.from(expiresAt)
        );
    }

    private int countAll() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_commit_dedupe",
                Integer.class
        );
        return count == null ? 0 : count;
    }

    // ────────────────────────────────────────────────────────────
    // 테스트 3건
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("deleteExpired — 만료 행 3건 삭제, 미만료 행 2건 잔존")
    void deleteExpired_만료행만삭제_미만료행잔존() {
        Instant past = Instant.now().minusSeconds(86400);   // now - 1day: 만료
        Instant future = Instant.now().plusSeconds(604800); // now + 7day: 미만료

        insertRow(UUID.randomUUID().toString(), past);
        insertRow(UUID.randomUUID().toString(), past);
        insertRow(UUID.randomUUID().toString(), past);
        insertRow(UUID.randomUUID().toString(), future);
        insertRow(UUID.randomUUID().toString(), future);

        int deleted = dedupeStore.deleteExpired(Instant.now(), 10);

        assertThat(deleted).isEqualTo(3);
        assertThat(countAll()).isEqualTo(2);
    }

    @Test
    @DisplayName("deleteExpired — batchSize 3 제한 시 만료 행 5건 중 3건만 삭제, 잔존 2건")
    void deleteExpired_batchSize제한_초과분미삭제() {
        Instant past = Instant.now().minusSeconds(86400);

        insertRow(UUID.randomUUID().toString(), past);
        insertRow(UUID.randomUUID().toString(), past);
        insertRow(UUID.randomUUID().toString(), past);
        insertRow(UUID.randomUUID().toString(), past);
        insertRow(UUID.randomUUID().toString(), past);

        int deleted = dedupeStore.deleteExpired(Instant.now(), 3);

        assertThat(deleted).isEqualTo(3);
        assertThat(countAll()).isEqualTo(2);
    }

    @Test
    @DisplayName("deleteExpired — 미만료 dedupe 행은 existsValid=true 유지, 삭제 없음")
    void deleteExpired_existsValid미만료행_불영향() {
        Instant future = Instant.now().plusSeconds(604800); // now + 7day

        String uuid1 = UUID.randomUUID().toString();
        String uuid2 = UUID.randomUUID().toString();
        insertRow(uuid1, future);
        insertRow(uuid2, future);

        int deleted = dedupeStore.deleteExpired(Instant.now(), 10);

        assertThat(deleted).isEqualTo(0);
        assertThat(dedupeStore.existsValid(uuid1)).isTrue();
        assertThat(dedupeStore.existsValid(uuid2)).isTrue();
    }
}
