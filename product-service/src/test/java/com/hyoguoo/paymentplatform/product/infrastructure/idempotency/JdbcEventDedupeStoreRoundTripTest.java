package com.hyoguoo.paymentplatform.product.infrastructure.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.TimeZone;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
 * JdbcEventDedupeStore 비-UTC JVM TZ round-trip 통합 테스트.
 *
 * <p>JVM TZ 를 Asia/Seoul(KST, UTC+9)로 강제한 상태에서 recordIfAbsent()를 호출하고,
 * DB 에서 조회한 expires_at 의 절대시점이 입력값과 밀리초 동치임을 단정한다.
 * connectionTimeZone=UTC 가 누락되면 이 테스트가 실패해야 한다 — 회귀 가드 역할.
 *
 * <p>D6 — recordIfAbsent DELETE 경계 검증:
 * expires_at &lt; now(strict) 만 삭제되고, expires_at == now 경계 행은 잔존.
 * D7 — raw-JDBC 경로(JdbcTemplate) UTC Calendar 명시 바인딩.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@Tag("integration")
@DisplayName("JdbcEventDedupeStore 비-UTC JVM TZ round-trip 통합 테스트")
class JdbcEventDedupeStoreRoundTripTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-01-01T12:00:00Z");

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
        // JVM TZ 를 Asia/Seoul 로 교체해 비-UTC 환경을 재현한다.
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
    // round-trip 테스트 1건
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("비-UTC JVM TZ에서 expires_at round-trip 절대시점 동치 (UTC Calendar 명시 바인딩)")
    void recordIfAbsent_nonUtcJvm_expiresAtRoundTripSameInstant() {
        // given — 비-UTC JVM TZ(Asia/Seoul)에서 고정 UTC Instant 기준 expiresAt 사용
        Instant fixedInstant = FIXED_INSTANT;
        String eventUuid = UUID.randomUUID().toString();
        Instant now = fixedInstant;
        Instant expiresAt = fixedInstant.plusSeconds(86400 * 8);  // 8일 후

        // when — recordIfAbsent 호출 (UTC Calendar 명시 바인딩 없으면 KST로 저장 → 9시간 오차)
        boolean recorded = dedupeStore.recordIfAbsent(eventUuid, now, expiresAt);
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

    // ────────────────────────────────────────────────────────────
    // D6 — recordIfAbsent DELETE 경계 검증 (DM-2)
    // ────────────────────────────────────────────────────────────

    /**
     * D6 — recordIfAbsent DELETE 경계 단정.
     *
     * <p>시나리오:
     * - uuid_expired: expires_at = now - 1h (만료 — DELETE 대상, strict &lt;)
     * - uuid_active : expires_at = now + 1h (미만료 — 잔존)
     * - uuid_boundary: expires_at = now (경계 동치 — expires_at &lt; now 조건 불충족 → 잔존)
     *
     * <p>recordIfAbsent(uuid_new, now, future) 호출 시:
     * - uuid_expired 행 DELETE 후 재삽입 → true (만료 재기록)
     * - uuid_active 행은 DELETE 안 됨 → INSERT IGNORE 0 row → false (유효 중복)
     * - uuid_boundary 행은 DELETE 안 됨(경계 잔존) → uuid_boundary 재기록 시 false
     *
     * <p>만료 행 삭제 후 count: uuid_active(1) + uuid_boundary(1) + uuid_new(1) = 3.
     */
    @Test
    @DisplayName("D6 — 비-UTC JVM TZ에서 만료 행 DELETE 경계: 만료 행만 삭제, 경계·미만료 행 잔존")
    void recordIfAbsent_nonUtcJvm_expiredRow삭제경계_만료행만삭제() {
        // given — 3종 행 삽입
        Instant now = Instant.parse("2026-06-06T12:00:00Z");
        Instant expiredExpiry = now.minusSeconds(3600);   // now - 1h: 만료
        Instant activeExpiry = now.plusSeconds(3600);     // now + 1h: 미만료
        Instant boundaryExpiry = now;                     // now == now: 경계 동치

        String uuidExpired = UUID.randomUUID().toString();
        String uuidActive = UUID.randomUUID().toString();
        String uuidBoundary = UUID.randomUUID().toString();
        String uuidNew = UUID.randomUUID().toString();

        insertRow(uuidExpired, expiredExpiry);
        insertRow(uuidActive, activeExpiry);
        insertRow(uuidBoundary, boundaryExpiry);

        // when 1 — 만료 uuid(uuidExpired)로 recordIfAbsent 재시도 → 만료 행 삭제 후 재삽입 = true
        Instant futureExpiry = now.plusSeconds(86400 * 8);
        boolean rerecorded = dedupeStore.recordIfAbsent(uuidExpired, now, futureExpiry);
        assertThat(rerecorded)
                .as("만료 행 DELETE 후 재삽입 — 최초 기록으로 처리(true)")
                .isTrue();

        // when 2 — 미만료 uuid(uuidActive)로 recordIfAbsent 재시도 → 유효 중복(false)
        boolean duplicateActive = dedupeStore.recordIfAbsent(uuidActive, now, futureExpiry);
        assertThat(duplicateActive)
                .as("미만료 행 잔존 — 중복(false)")
                .isFalse();

        // when 3 — 경계 uuid(uuidBoundary) 재시도: expires_at == now → strict < 불충족 → 잔존 → false
        boolean duplicateBoundary = dedupeStore.recordIfAbsent(uuidBoundary, now, futureExpiry);
        assertThat(duplicateBoundary)
                .as("경계 행(expires_at==now) 잔존 — 만료로 보지 않고 중복(false) 반환")
                .isFalse();

        // when 4 — 신규 uuid로 처음 recordIfAbsent → true
        boolean firstSeenNew = dedupeStore.recordIfAbsent(uuidNew, now, futureExpiry);
        assertThat(firstSeenNew)
                .as("신규 uuid — 최초 기록(true)")
                .isTrue();

        // then — 행 수 단정
        // uuidExpired(재삽입 1) + uuidActive(1) + uuidBoundary(1) + uuidNew(1) = 4
        Integer totalCount = namedJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_commit_dedupe",
                new MapSqlParameterSource(),
                Integer.class
        );
        assertThat(totalCount)
                .as("총 행 수: 만료 행 삭제 후 재삽입 + 미만료 + 경계 + 신규 = 4")
                .isEqualTo(4);
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
