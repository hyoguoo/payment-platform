package com.hyoguoo.paymentplatform.payment.infrastructure.dedupe;

import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventDedupeStore;
import com.hyoguoo.paymentplatform.payment.core.common.service.port.LocalDateTimeProvider;
import java.sql.Timestamp;
import java.time.Instant;
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
 * <p>received_at 시간 소스는 {@link LocalDateTimeProvider#nowInstant()} 로 주입받는다
 * ({@code Instant.now()} 직접 호출 금지).
 */
@Repository
public class JdbcPaymentEventDedupeStore implements PaymentEventDedupeStore {

    private static final String INSERT_IGNORE_SQL =
            "INSERT IGNORE INTO payment_event_dedupe "
                    + "(event_uuid, order_id, status, received_at, expires_at) "
                    + "VALUES (:eventUuid, :orderId, :status, :receivedAt, :expiresAt)";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final LocalDateTimeProvider localDateTimeProvider;

    public JdbcPaymentEventDedupeStore(
            NamedParameterJdbcTemplate jdbcTemplate,
            LocalDateTimeProvider localDateTimeProvider) {
        this.jdbcTemplate = jdbcTemplate;
        this.localDateTimeProvider = localDateTimeProvider;
    }

    /**
     * {@inheritDoc}
     *
     * <p>received_at 은 {@link LocalDateTimeProvider#nowInstant()} 로 주입된 시계 기준.
     * expires_at 은 호출자가 도메인 정책(Kafka retention + 복구 버퍼)에 따라 계산해 넘긴다.
     */
    @Override
    public int markIfAbsent(String eventUuid, long orderId, String status, Instant expiresAt) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("eventUuid", eventUuid)
                .addValue("orderId", orderId)
                .addValue("status", status)
                .addValue("receivedAt", Timestamp.from(localDateTimeProvider.nowInstant()))
                .addValue("expiresAt", Timestamp.from(expiresAt));
        return jdbcTemplate.update(INSERT_IGNORE_SQL, params);
    }
}
