package com.hyoguoo.paymentplatform.payment.infrastructure.dedupe;

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
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

/**
 * JdbcPaymentEventDedupeStore.deleteExpired Testcontainers 통합 테스트.
 *
 * <p>만료 행만 삭제·미만료 행 잔존, batchSize 제한, 빈 테이블, 멱등성 4개 시나리오 검증.
 * Flyway V1~V2 자동 적용 환경.
 */
@SpringBootTest
@Tag("integration")
@DisplayName("JdbcPaymentEventDedupeStore deleteExpired 통합 테스트")
class JdbcPaymentEventDedupeStoreCleanupTest {

    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL_CONTAINER =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("payment-test")
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
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.sql.init.mode", () -> "never");
        registry.add("scheduler.enabled", () -> "false");
        registry.add("payment.cache.stock-redis.host", () -> "localhost");
        registry.add("payment.cache.stock-redis.port", () -> "6380");
    }

    @Autowired
    private JdbcPaymentEventDedupeStore dedupeStore;

    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTable() {
        jdbcTemplate.update("TRUNCATE TABLE payment_event_dedupe", new MapSqlParameterSource());
    }

    // ────────────────────────────────────────────────────────────
    // 헬퍼
    // ────────────────────────────────────────────────────────────

    private void insertRow(String eventUuid, Instant expiresAt) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("eventUuid", eventUuid)
                .addValue("orderId", 1001L)
                .addValue("status", "APPROVED")
                .addValue("receivedAt", Timestamp.from(Instant.now()))
                .addValue("expiresAt", Timestamp.from(expiresAt));
        jdbcTemplate.update(
                "INSERT INTO payment_event_dedupe "
                        + "(event_uuid, order_id, status, received_at, expires_at) "
                        + "VALUES (:eventUuid, :orderId, :status, :receivedAt, :expiresAt)",
                params
        );
    }

    private int countAll() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_event_dedupe",
                new MapSqlParameterSource(),
                Integer.class
        );
        return count == null ? 0 : count;
    }

    // ────────────────────────────────────────────────────────────
    // 테스트 4건
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
    @DisplayName("deleteExpired — batchSize 3 제한 시 만료 행 5건 중 3건만 삭제")
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
    @DisplayName("deleteExpired — 테이블 비어 있을 때 0 반환")
    void deleteExpired_행없음_0반환() {
        int deleted = dedupeStore.deleteExpired(Instant.now(), 10);

        assertThat(deleted).isEqualTo(0);
    }

    @Test
    @DisplayName("deleteExpired — 이미 삭제된 행 재실행 시 0 반환 (멱등성)")
    void deleteExpired_멱등성_이미삭제된행재실행_0반환() {
        Instant past = Instant.now().minusSeconds(86400);

        insertRow(UUID.randomUUID().toString(), past);
        insertRow(UUID.randomUUID().toString(), past);

        int firstRun = dedupeStore.deleteExpired(Instant.now(), 10);
        int secondRun = dedupeStore.deleteExpired(Instant.now(), 10);

        assertThat(firstRun).isEqualTo(2);
        assertThat(secondRun).isEqualTo(0);
    }
}
