package com.hyoguoo.paymentplatform.product.infrastructure.idempotency;

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
 * JdbcEventDedupeStore 비-UTC JVM TZ round-trip 통합 테스트 (AC8 — product).
 *
 * <p>JVM TZ 를 Asia/Seoul(KST, UTC+9)로 강제한 상태에서 recordIfAbsent()를 호출하고,
 * DB 에서 조회한 expires_at 의 절대시점이 입력값과 밀리초 동치임을 단정한다.
 * connectionTimeZone=UTC 가 누락되면 이 테스트가 실패해야 한다 — 회귀 가드 역할.
 *
 * <p>AC8 — 비-UTC JVM TZ raw-JDBC UTC 규약 검증.
 * D7 — raw-JDBC 경로(JdbcTemplate) UTC Calendar 명시 바인딩.
 */
@SpringBootTest(
        properties = "spring.main.allow-bean-definition-overriding=true",
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@Tag("integration")
@Import(JdbcEventDedupeStoreRoundTripTest.FixedClockConfig.class)
@DisplayName("JdbcEventDedupeStore 비-UTC JVM TZ round-trip 통합 테스트 (AC8)")
class JdbcEventDedupeStoreRoundTripTest {

    /**
     * T13 RED — Clock.fixed 를 @Primary 로 오버라이드해 clock 주입 결정성 검증.
     * 구현이 Clock 주입을 사용할 때만 동작이 결정적으로 제어된다.
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
                    .withDatabaseName("product-test")
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

    private TimeZone originalTimeZone;

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
    private NamedParameterJdbcTemplate namedJdbcTemplate;

    @BeforeEach
    void setUp() {
        // AC8 — JVM TZ 를 Asia/Seoul 로 교체해 비-UTC 환경을 재현한다.
        originalTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        namedJdbcTemplate.update("TRUNCATE TABLE stock_commit_dedupe", new MapSqlParameterSource());
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
    @DisplayName("AC8 — 비-UTC JVM TZ에서 expires_at round-trip 절대시점 동치 (UTC Calendar 명시 바인딩)")
    void recordIfAbsent_nonUtcJvm_expiresAtRoundTripSameInstant() {
        // given — 비-UTC JVM TZ(Asia/Seoul)에서 Clock.fixed UTC 기준 expiresAt 사용
        Instant fixedInstant = FixedClockConfig.FIXED_INSTANT;
        String eventUuid = UUID.randomUUID().toString();
        Instant expiresAt = fixedInstant.plusSeconds(86400 * 8);  // 8일 후

        // when — recordIfAbsent 호출 (UTC Calendar 명시 바인딩 없으면 KST로 저장 → 9시간 오차)
        boolean recorded = dedupeStore.recordIfAbsent(eventUuid, expiresAt);
        assertThat(recorded).isTrue();

        // then — DB 에서 읽은 expires_at 이 입력 Instant 와 밀리초 동치
        // connectionTimeZone=UTC + UTC Calendar 없으면 비-UTC JVM에서 9시간 오차 발생
        Instant storedExpiresAt = namedJdbcTemplate.queryForObject(
                "SELECT expires_at FROM stock_commit_dedupe WHERE event_uuid = :uuid",
                new MapSqlParameterSource("uuid", eventUuid),
                (rs, rowNum) -> rs.getTimestamp("expires_at").toInstant()
        );
        assertThat(storedExpiresAt)
                .as("expires_at round-trip: 비-UTC JVM에서 UTC Calendar 명시 바인딩 필수 (9시간 오차 미허용)")
                .isAfterOrEqualTo(expiresAt.minusMillis(10))
                .isBeforeOrEqualTo(expiresAt.plusMillis(10));
    }

    @Test
    @DisplayName("AC8 — 비-UTC JVM TZ에서 existsValid NOW() split-brain 부재 (connectionTimeZone=UTC 경계 일치)")
    void existsValid_nowBasedOnConnectionUTC_sameBoundaryAsAppInstant() {
        // given — 만료된 엔트리(과거)와 미만료 엔트리(미래) 각 1건 직접 삽입
        Instant now = Instant.now();
        Instant pastExpiry = now.minusSeconds(3600);     // 1시간 전: 만료됨
        Instant futureExpiry = now.plusSeconds(3600);    // 1시간 후: 미만료

        String expiredUuid = UUID.randomUUID().toString();
        String activeUuid = UUID.randomUUID().toString();

        insertRow(expiredUuid, pastExpiry);
        insertRow(activeUuid, futureExpiry);

        // when & then — existsValid 는 NOW() 기반 비교 (connectionTimeZone=UTC → 앱 Instant 동일 기준)
        assertThat(dedupeStore.existsValid(expiredUuid))
                .as("만료된 UUID — existsValid=false (NOW() > pastExpiry)")
                .isFalse();
        assertThat(dedupeStore.existsValid(activeUuid))
                .as("미만료 UUID — existsValid=true (NOW() < futureExpiry)")
                .isTrue();
    }

    // ────────────────────────────────────────────────────────────
    // 헬퍼
    // ────────────────────────────────────────────────────────────

    /**
     * UTC Calendar 명시 바인딩으로 JDBC Timestamp 삽입 — 헬퍼.
     */
    private void insertRow(String eventUuid, Instant expiresAt) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("eventUuid", eventUuid)
                .addValue("expiresAt", java.sql.Timestamp.from(expiresAt));
        namedJdbcTemplate.update(
                "INSERT INTO stock_commit_dedupe (event_uuid, expires_at) VALUES (:eventUuid, :expiresAt)",
                params
        );
    }
}
