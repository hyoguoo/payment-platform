package com.hyoguoo.paymentplatform.payment.infrastructure.dedupe;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * JdbcPaymentEventDedupeStore Testcontainers MySQL 단위 테스트.
 *
 * <p>멱등 마킹 정확성 + INSERT IGNORE race window 회귀 가드.
 * Flyway V1 → V2 자동 적용 환경에서 payment_event_dedupe 테이블 행동 검증.
 */
@SpringBootTest
@Tag("integration")
@DisplayName("JdbcPaymentEventDedupeStore INSERT IGNORE 단위 테스트")
class JdbcPaymentEventDedupeStoreTest {

    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL_CONTAINER =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("payment-test")
                    .withUsername("test")
                    .withPassword("test")
                    .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci")
                    .withReuse(true);

    static {
        // @Testcontainers/@Container 를 사용하지 않고 수동 start.
        // @Container 로 관리하면 JUnit5 extension 이 테스트 클래스 완료 후 stop() 을 명시 호출하여
        // withReuse(true) 설정에도 불구하고 컨테이너가 종료된다.
        MYSQL_CONTAINER.start();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL_CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL_CONTAINER::getUsername);
        registry.add("spring.datasource.password", MYSQL_CONTAINER::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        // Flyway 활성화 + JPA ddl-auto=none — Flyway V1→V2 순서 적용으로 스키마 생성
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.sql.init.mode", () -> "never");
        registry.add("scheduler.enabled", () -> "false");
        // Redis / Kafka — 테스트 범위 밖이므로 dummy 설정
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
    // 테스트 헬퍼
    // ────────────────────────────────────────────────────────────

    private String randomUuid() {
        return UUID.randomUUID().toString();
    }

    private Instant expiresAt() {
        return Instant.now().plusSeconds(3600);
    }

    // ────────────────────────────────────────────────────────────
    // 테스트 4건
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("markIfAbsent — 신규 event_uuid 는 1 row 반환 + 테이블에 row 박힘")
    void shouldReturnOneAndInsertRowForNewEventUuid() {
        String eventUuid = randomUuid();
        long orderId = 1001L;
        String status = "APPROVED";

        int result = dedupeStore.markIfAbsent(eventUuid, orderId, status, expiresAt());

        assertThat(result).isEqualTo(1);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_event_dedupe WHERE event_uuid = :uuid",
                new MapSqlParameterSource("uuid", eventUuid),
                Integer.class
        );
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("markIfAbsent — 동일 event_uuid 두 번째 호출은 0 row 반환 (INSERT IGNORE)")
    void shouldReturnZeroOnDuplicateEventUuid() {
        String eventUuid = randomUuid();
        long orderId = 1002L;
        String status = "APPROVED";

        int first = dedupeStore.markIfAbsent(eventUuid, orderId, status, expiresAt());
        int second = dedupeStore.markIfAbsent(eventUuid, orderId, status, expiresAt());

        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(0);
    }

    @Test
    @DisplayName("markIfAbsent — INSERT 시 메타데이터 (order_id, status, received_at, expires_at) 정확히 박힘")
    void shouldPersistMetadataCorrectly() {
        String eventUuid = randomUuid();
        long orderId = 1003L;
        String status = "FAILED";
        Instant expires = expiresAt();

        Instant before = Instant.now().minusSeconds(1);
        dedupeStore.markIfAbsent(eventUuid, orderId, status, expires);
        Instant after = Instant.now().plusSeconds(1);

        MapSqlParameterSource params = new MapSqlParameterSource("uuid", eventUuid);
        Long storedOrderId = jdbcTemplate.queryForObject(
                "SELECT order_id FROM payment_event_dedupe WHERE event_uuid = :uuid",
                params, Long.class);
        String storedStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM payment_event_dedupe WHERE event_uuid = :uuid",
                params, String.class);
        Instant storedReceivedAt = jdbcTemplate.queryForObject(
                "SELECT received_at FROM payment_event_dedupe WHERE event_uuid = :uuid",
                params,
                (rs, rowNum) -> rs.getTimestamp("received_at").toInstant()
        );
        Instant storedExpiresAt = jdbcTemplate.queryForObject(
                "SELECT expires_at FROM payment_event_dedupe WHERE event_uuid = :uuid",
                params,
                (rs, rowNum) -> rs.getTimestamp("expires_at").toInstant()
        );

        assertThat(storedOrderId).isEqualTo(orderId);
        assertThat(storedStatus).isEqualTo(status);
        assertThat(storedReceivedAt).isAfterOrEqualTo(before).isBeforeOrEqualTo(after);
        // expires_at: MySQL TIMESTAMP 는 초 단위 — 1초 허용 오차
        assertThat(storedExpiresAt).isAfterOrEqualTo(expires.minusSeconds(1))
                                   .isBeforeOrEqualTo(expires.plusSeconds(1));
    }

    @Test
    @DisplayName("markIfAbsent — 동시 INSERT IGNORE 같은 key 두 스레드 race 시 예외 없이 한 쪽만 1, 다른 쪽 0")
    void shouldNotThrowOnConcurrentInsertSameKey() throws Exception {
        String eventUuid = randomUuid();
        long orderId = 1004L;
        String status = "APPROVED";
        Instant expires = expiresAt();

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        List<Future<Integer>> futures = new ArrayList<>();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    startLatch.await();
                    int result = dedupeStore.markIfAbsent(eventUuid, orderId, status, expires);
                    doneLatch.countDown();
                    return result;
                }));
            }

            startLatch.countDown();
            doneLatch.await();

            int sum = 0;
            for (Future<Integer> future : futures) {
                sum += future.get();
            }

            // race 회귀 가드: 두 스레드 결과 합 == 1 (한 쪽만 신규, 다른 쪽은 중복)
            assertThat(sum).isEqualTo(1);
        } finally {
            executor.shutdown();
        }
    }
}
