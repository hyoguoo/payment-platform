package com.hyoguoo.paymentplatform.payment.infrastructure.dedupe;

import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventDedupeStore;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Calendar;
import java.util.TimeZone;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * PaymentEventDedupeStore JDBC 구현체.
 *
 * <p>{@code payment_event_dedupe} 테이블에 {@code INSERT IGNORE} 를 실행한다.
 * affected row 수 (0 또는 1) 를 그대로 반환 — 호출자가 신규/중복 여부를 판단한다.
 *
 * <p>INSERT IGNORE: PK(event_uuid) 중복 시 예외 없이 0 row 반환 (MySQL 시맨틱).
 * 동시 INSERT IGNORE 가 경합해도 양쪽 모두 예외 없이 한 쪽만 1 row 를 얻는다 (합 == 1 보장).
 *
 * <p>D7 — received_at/expires_at 바인딩에 UTC {@link Calendar} 를 명시해 JVM TZ 와 무관하게
 * UTC 기준으로 저장/조회한다. {@code connectionTimeZone=UTC} datasource URL 파라미터와 함께
 * raw-JDBC 경로의 TZ 누수를 이중으로 차단한다.
 *
 * <p>received_at 시간 소스는 {@link Clock#instant()} 로 주입받는다
 * ({@code Instant.now()} 직접 호출 금지).
 */
@Repository
public class JdbcPaymentEventDedupeStore implements PaymentEventDedupeStore {

    private static final String INSERT_IGNORE_SQL =
            "INSERT IGNORE INTO payment_event_dedupe "
                    + "(event_uuid, order_id, status, received_at, expires_at) "
                    + "VALUES (?, ?, ?, ?, ?)";

    private static final String DELETE_EXPIRED_SQL =
            "DELETE FROM payment_event_dedupe "
                    + "WHERE expires_at < :now "
                    + "LIMIT :batchSize";

    /** UTC Calendar 인스턴스 — JDBC Timestamp 바인딩에 명시해 JVM TZ 의존을 제거한다 (D7). */
    private static final Calendar UTC_CALENDAR =
            Calendar.getInstance(TimeZone.getTimeZone("UTC"));

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;
    private final Clock clock;

    public JdbcPaymentEventDedupeStore(
            NamedParameterJdbcTemplate namedJdbcTemplate,
            Clock clock) {
        this.namedJdbcTemplate = namedJdbcTemplate;
        this.jdbcTemplate = namedJdbcTemplate.getJdbcTemplate();
        this.clock = clock;
    }

    /**
     * {@inheritDoc}
     *
     * <p>received_at 은 {@link Clock#instant()} 로 주입된 시계 기준 UTC 절대시점.
     * expires_at 은 호출자가 도메인 정책(Kafka retention + 복구 버퍼)에 따라 계산해 넘긴다.
     *
     * <p>D7 — {@link PreparedStatement#setTimestamp(int, Timestamp, Calendar)} 에 UTC
     * Calendar 를 명시해 JVM TZ 가 비-UTC 라도 저장 시각이 UTC 기준임을 보장한다.
     */
    @Override
    public int markIfAbsent(String eventUuid, long orderId, String status, Instant expiresAt) {
        Instant receivedAt = clock.instant();
        return jdbcTemplate.update(INSERT_IGNORE_SQL, ps -> bindInsertParams(
                ps, eventUuid, orderId, status, receivedAt, expiresAt));
    }

    /**
     * {@inheritDoc}
     *
     * <p>expires_at &lt; :now 조건 idempotent batch DELETE.
     * LIMIT :batchSize 로 한 번에 삭제할 최대 행 수를 제한한다.
     * 이미 삭제된 행은 0 row affected — 동시 실행 무해.
     *
     * <p>D7 — now 바인딩도 UTC Calendar 명시 (namedJdbcTemplate 경로는 Timestamp.from 이므로
     * connectionTimeZone=UTC datasource 파라미터로 1차 보정됨; Calendar 이중 적용 안 함).
     */
    @Override
    public int deleteExpired(Instant now, int batchSize) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("now", Timestamp.from(now))
                .addValue("batchSize", batchSize);
        return namedJdbcTemplate.update(DELETE_EXPIRED_SQL, params);
    }

    // ────────────────────────────────────────────────────────────
    // 내부 헬퍼
    // ────────────────────────────────────────────────────────────

    /**
     * D7 UTC Calendar 명시 PreparedStatement 바인딩.
     *
     * <p>setTimestamp(idx, ts, utcCalendar) 호출로 JDBC 드라이버가 utcCalendar TZ 기준으로
     * Timestamp 를 변환한다. JVM 의 기본 TZ(예: Asia/Seoul)와 무관하게 UTC 절대시점으로 저장.
     */
    private void bindInsertParams(
            PreparedStatement ps,
            String eventUuid,
            long orderId,
            String status,
            Instant receivedAt,
            Instant expiresAt) throws SQLException {
        ps.setString(1, eventUuid);
        ps.setLong(2, orderId);
        ps.setString(3, status);
        // D7 — UTC Calendar 명시 바인딩: JVM TZ 에 관계없이 UTC 절대시점 저장
        ps.setTimestamp(4, Timestamp.from(receivedAt), UTC_CALENDAR);
        ps.setTimestamp(5, Timestamp.from(expiresAt), UTC_CALENDAR);
    }
}
