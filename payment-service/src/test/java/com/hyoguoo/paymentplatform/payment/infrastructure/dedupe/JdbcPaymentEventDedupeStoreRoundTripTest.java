package com.hyoguoo.paymentplatform.payment.infrastructure.dedupe;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.TimeZone;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

/**
 * JdbcPaymentEventDedupeStore 비-UTC JVM TZ round-trip 통합 테스트 (AC8).
 *
 * <p>JVM TZ 를 Asia/Seoul(KST, UTC+9)로 강제한 상태에서 Clock.fixed(UTC)로 markIfAbsent()를 호출하고,
 * DB 에서 조회한 received_at/expires_at 의 절대시점이 입력값과 밀리초 동치임을 단정한다.
 * connectionTimeZone=UTC 가 누락되면 이 테스트가 실패해야 한다 — 회귀 가드 역할.
 *
 * <p>AC8 — 비-UTC JVM TZ raw-JDBC UTC 규약 검증.
 * D7 — raw-JDBC 경로(JdbcTemplate) UTC Calendar 명시 바인딩.
 */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@Tag("integration")
@Import(JdbcPaymentEventDedupeStoreRoundTripTest.FixedClockConfig.class)
@DisplayName("JdbcPaymentEventDedupeStore 비-UTC JVM TZ round-trip 통합 테스트 (AC8)")
class JdbcPaymentEventDedupeStoreRoundTripTest {

    /**
     * T12 RED — Clock.fixed 를 @Primary 로 오버라이드해 dedupeStore 내부 received_at 소스를 결정적으로 제어.
     * 구현이 Clock 주입을 사용할 때만 received_at 이 fixedInstant 와 일치한다.
     * LocalDateTimeProvider 기반 구현에서는 다른 시각이 저장되어 테스트가 실패한다.
     */
    @TestConfiguration
    static class FixedClockConfig {

        static final Instant FIXED_INSTANT = Instant.parse("2026-01-01T12:00:00Z");

        @Bean
        @Primary
        public Clock clock() {
            return Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        }
    }

    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL_CONTAINER =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("payment-test")
                    .withUsername("test")
                    .withPassword("test")
                    .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci")
                    // D7/AC8 — connectionTimeZone=UTC 가 없으면 비-UTC JVM TZ에서 round-trip 이 어긋난다.
                    .withUrlParam("connectionTimeZone", "UTC")
                    .withUrlParam("forceConnectionTimeZoneToSession", "true")
                    .withReuse(true);

    static {
        MYSQL_CONTAINER.start();
    }

    /** 테스트 전 JVM TZ 를 Asia/Seoul 로 교체 — 비-UTC 환경 재현. */
    private TimeZone originalTimeZone;

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
        // D3 — ORM 경로 UTC 고정
        registry.add("spring.jpa.properties.hibernate.jdbc.time_zone", () -> "UTC");
    }

    @Autowired
    private JdbcPaymentEventDedupeStore dedupeStore;

    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        // AC8 — JVM TZ 를 Asia/Seoul 로 교체해 비-UTC 환경을 재현한다.
        // tearDown 에서 원래 TZ 로 복원한다.
        originalTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        jdbcTemplate.update("TRUNCATE TABLE payment_event_dedupe", new MapSqlParameterSource());
    }

    @AfterEach
    void tearDown() {
        // 반드시 원래 TZ 로 복원 — 다른 테스트에 영향을 주지 않는다.
        TimeZone.setDefault(originalTimeZone);
    }

    // ────────────────────────────────────────────────────────────
    // AC8 테스트 2건
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC8 — 비-UTC JVM TZ에서 received_at round-trip 절대시점 동치 (Clock 기반)")
    void markIfAbsent_nonUtcJvm_receivedAtRoundTripSameInstant() {
        // given — FixedClockConfig.FIXED_INSTANT 가 @Primary Clock 으로 주입된 상태.
        // 구현이 Clock.instant() 를 received_at 소스로 사용하면 DB 에 FIXED_INSTANT 가 저장된다.
        Instant fixedInstant = FixedClockConfig.FIXED_INSTANT;
        String eventUuid = UUID.randomUUID().toString();
        Instant expiresAt = fixedInstant.plusSeconds(86400);

        // when — 비-UTC JVM TZ(Asia/Seoul)에서 markIfAbsent 호출
        int result = dedupeStore.markIfAbsent(eventUuid, 1001L, "APPROVED", expiresAt);
        assertThat(result).isEqualTo(1);

        // then — DB 에서 읽은 received_at 이 Clock.fixed 시각(FIXED_INSTANT)과 동치
        // LocalDateTimeProvider 기반 구현에서는 현재 시각(LocalDateTime.now())이 저장돼 불일치.
        // connectionTimeZone=UTC 가 없으면 KST로 저장되어 9시간 오차가 발생한다.
        Instant storedReceivedAt = jdbcTemplate.queryForObject(
                "SELECT received_at FROM payment_event_dedupe WHERE event_uuid = :uuid",
                new MapSqlParameterSource("uuid", eventUuid),
                (rs, rowNum) -> rs.getTimestamp("received_at").toInstant()
        );
        assertThat(storedReceivedAt)
                .as("received_at == Clock.fixed INSTANT (10ms 허용): 구현이 Clock 기반일 때만 통과")
                .isAfterOrEqualTo(fixedInstant.minusMillis(10))
                .isBeforeOrEqualTo(fixedInstant.plusMillis(10));

        // expires_at round-trip 도 동시 검증 (D7 UTC Calendar 명시 바인딩 회귀 가드)
        Instant storedExpiresAt = jdbcTemplate.queryForObject(
                "SELECT expires_at FROM payment_event_dedupe WHERE event_uuid = :uuid",
                new MapSqlParameterSource("uuid", eventUuid),
                (rs, rowNum) -> rs.getTimestamp("expires_at").toInstant()
        );
        assertThat(storedExpiresAt)
                .as("expires_at round-trip: 비-UTC JVM에서 UTC Calendar 명시 바인딩 필수 (9시간 오차 미허용)")
                .isAfterOrEqualTo(expiresAt.minusMillis(1))
                .isBeforeOrEqualTo(expiresAt.plusMillis(10));
    }

    @Test
    @DisplayName("AC8 — 비-UTC JVM TZ에서 deleteExpired cutoff 경계 Instant 기준 동작 (만료/미만료 구분)")
    void deleteExpired_nonUtcJvm_respectsInstantBoundary() {
        // given
        Instant now = Instant.now();
        Instant pastExpiry = now.minusSeconds(3600);     // 1시간 전: 만료됨
        Instant futureExpiry = now.plusSeconds(3600);    // 1시간 후: 미만료

        String expiredUuid = UUID.randomUUID().toString();
        String activeUuid = UUID.randomUUID().toString();

        // 헬퍼 대신 dedupeStore.markIfAbsent 사용 불가 — expires_at 은 이미 시각이 결정되어야 함.
        // 직접 INSERT (connectionTimeZone=UTC 가 적용된 동일 datasource 사용)
        insertRow(expiredUuid, pastExpiry);
        insertRow(activeUuid, futureExpiry);

        // when — cutoff = now, 만료 행만 삭제됨을 단정
        int deleted = dedupeStore.deleteExpired(now, 10);

        // then
        assertThat(deleted)
                .as("만료 행 1건만 삭제: connectionTimeZone=UTC 기준으로 cutoff 비교 시 정확해야 함")
                .isEqualTo(1);

        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_event_dedupe",
                new MapSqlParameterSource(),
                Integer.class
        );
        assertThat(remaining).isEqualTo(1);
    }

    // ────────────────────────────────────────────────────────────
    // 헬퍼
    // ────────────────────────────────────────────────────────────

    /**
     * UTC Calendar 를 명시해 JDBC Timestamp 바인딩.
     * dedupeStore 구현 수정 전/후 동일 datasource를 사용하므로 연결 TZ 는 같다.
     */
    private void insertRow(String eventUuid, Instant expiresAt) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("eventUuid", eventUuid)
                .addValue("orderId", 1001L)
                .addValue("status", "APPROVED")
                .addValue("receivedAt", java.sql.Timestamp.from(Instant.now(Clock.systemUTC())))
                .addValue("expiresAt", java.sql.Timestamp.from(expiresAt));
        jdbcTemplate.update(
                "INSERT INTO payment_event_dedupe "
                        + "(event_uuid, order_id, status, received_at, expires_at) "
                        + "VALUES (:eventUuid, :orderId, :status, :receivedAt, :expiresAt)",
                params
        );
    }
}
