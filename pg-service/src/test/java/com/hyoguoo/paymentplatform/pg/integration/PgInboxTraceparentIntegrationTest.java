package com.hyoguoo.paymentplatform.pg.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.pg.application.port.out.EventDedupeStore;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgInboxRepository;
import com.hyoguoo.paymentplatform.pg.infrastructure.repository.JpaPgInboxRepository;
import com.hyoguoo.paymentplatform.pg.mock.FakeEventDedupeStore;
import java.sql.ResultSet;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 추적 연속성 Testcontainers 통합 테스트 — E-5.
 *
 * <p>검증 범위:
 * <ul>
 *   <li>Flyway V4 마이그레이션 적용 확인: pg_inbox 테이블에 stored_traceparent 컬럼 존재</li>
 *   <li>traceparent 저장 경로: insertPending 호출 시 stored_traceparent 컬럼에 기록</li>
 *   <li>NULL 폴백: traceparent=null 인자로 INSERT 성공, stored_traceparent IS NULL</li>
 * </ul>
 *
 * <p>인프라:
 * <ul>
 *   <li>Testcontainers MySQL — Flyway V1~V4 자동 적용</li>
 *   <li>Redis 미사용 — FakeEventDedupeStore 로 대체</li>
 *   <li>Kafka 미사용 — consumer auto-startup 비활성</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
})
@Tag("integration")
@Testcontainers
@DisplayName("추적 연속성 통합 테스트 — E-5")
class PgInboxTraceparentIntegrationTest {

    // ─── Testcontainers MySQL ─────────────────────────────────────────────────

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL_CONTAINER =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("pg-test")
                    .withUsername("test")
                    .withPassword("test")
                    .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL_CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL_CONTAINER::getUsername);
        registry.add("spring.datasource.password", MYSQL_CONTAINER::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("pg.gateway.type", () -> "fake");
        // 스케줄러 비활성
        registry.add("pg.scheduler.inbox-polling-worker.fixed-delay-ms", () -> "3600000");
        registry.add("pg.scheduler.polling-worker.fixed-delay-ms", () -> "3600000");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9099");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    // ─── TestConfiguration — FakeEventDedupeStore ────────────────────────────

    @TestConfiguration
    static class TraceparentIntegrationTestConfig {

        @Bean
        @Primary
        public EventDedupeStore fakeEventDedupeStore() {
            return new FakeEventDedupeStore();
        }
    }

    // ─── 의존성 ───────────────────────────────────────────────────────────────

    @Autowired
    private PgInboxRepository pgInboxRepository;

    @Autowired
    private JpaPgInboxRepository jpaPgInboxRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final long AMOUNT = 10_000L;

    // ─── setUp ────────────────────────────────────────────────────────────────

    @BeforeEach
    void setUp() {
        jpaPgInboxRepository.deleteAll();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Flyway V4 컬럼 존재
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Flyway V4 마이그레이션 적용 후 pg_inbox.stored_traceparent 컬럼이 존재한다")
    void flywayMigration_V4컬럼존재() {
        // when — INFORMATION_SCHEMA 조회
        String sql = "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'pg_inbox' "
                + "AND COLUMN_NAME = 'stored_traceparent'";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class);

        // then — 컬럼 존재
        assertThat(count)
                .as("Flyway V4 마이그레이션 후 pg_inbox.stored_traceparent 컬럼이 존재해야 함")
                .isEqualTo(1);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // traceparent 저장 경로
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("insertPending 호출 시 stored_traceparent 컬럼에 traceparent가 저장된다")
    void insertPending_traceparent저장됨() {
        // given
        String orderId = "order-e5-tp-" + UUID.randomUUID();
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

        // when
        pgInboxRepository.insertPending(
                orderId, AMOUNT, UUID.randomUUID().toString(), "TOSS", "pay-key-e5-tp", traceparent);

        // then — SELECT stored_traceparent FROM pg_inbox WHERE order_id=? 결과 == 저장한 traceparent
        Optional<String> stored = pgInboxRepository.findStoredTraceparent(
                fetchIdByOrderId(orderId));

        assertThat(stored)
                .as("insertPending 후 findStoredTraceparent 가 저장한 traceparent 를 반환해야 함")
                .isPresent()
                .hasValue(traceparent);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NULL 폴백
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("traceparent=null로 insertPending 시 INSERT 성공하고 stored_traceparent IS NULL")
    void insertPending_traceparentNull_INSERT성공_폴백() {
        // given
        String orderId = "order-e5-null-" + UUID.randomUUID();

        // when
        Long inboxId = pgInboxRepository.insertPending(
                orderId, AMOUNT, UUID.randomUUID().toString(), "TOSS", "pay-key-e5-null", null);

        // then — INSERT 성공 (id 반환)
        assertThat(inboxId)
                .as("traceparent=null 인자로 insertPending 호출 시 id 가 반환되어야 함")
                .isNotNull()
                .isPositive();

        // then — stored_traceparent IS NULL
        String nullCheckSql = "SELECT stored_traceparent FROM pg_inbox WHERE order_id = ?";
        String storedValue = jdbcTemplate.query(
                nullCheckSql,
                (ResultSet rs) -> rs.next() ? rs.getString("stored_traceparent") : "NOT_FOUND",
                orderId
        );

        assertThat(storedValue)
                .as("traceparent=null 인자 INSERT 후 stored_traceparent 컬럼이 NULL 이어야 함")
                .isNull();
    }

    // ─── 헬퍼 ────────────────────────────────────────────────────────────────

    private Long fetchIdByOrderId(String orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM pg_inbox WHERE order_id = ?",
                Long.class,
                orderId
        );
    }
}
