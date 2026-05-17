package com.hyoguoo.paymentplatform.payment.infrastructure.dedupe;

import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventDedupeStore;
import java.sql.Timestamp;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * PaymentEventDedupeStore JDBC 구현체.
 *
 * <p>PET-4 신설 {@code payment_event_dedupe} 테이블에 {@code INSERT IGNORE} 를 실행한다.
 * affected row 수 (0 또는 1) 를 그대로 반환 — 호출자가 신규/중복 여부를 판단한다.
 *
 * <p>INSERT IGNORE: PK(event_uuid) 중복 시 예외 없이 0 row 반환 (MySQL 시맨틱).
 * DR-5 race window — 동시 INSERT IGNORE 양쪽 모두 예외 없음, 합 == 1 보장.
 */
@Repository
@RequiredArgsConstructor
public class JdbcPaymentEventDedupeStore implements PaymentEventDedupeStore {

    private static final String INSERT_IGNORE_SQL =
            "INSERT IGNORE INTO payment_event_dedupe "
                    + "(event_uuid, order_id, status, received_at, expires_at) "
                    + "VALUES (:eventUuid, :orderId, :status, :receivedAt, :expiresAt)";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    /**
     * {@inheritDoc}
     *
     * <p>received_at 은 서버 시계 ({@link Instant#now()}) 기준.
     * expires_at 은 호출자가 도메인 정책(Kafka retention + 복구 버퍼)에 따라 계산해 넘긴다.
     */
    @Override
    public int markIfAbsent(String eventUuid, long orderId, String status, Instant expiresAt) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("eventUuid", eventUuid)
                .addValue("orderId", orderId)
                .addValue("status", status)
                .addValue("receivedAt", Timestamp.from(Instant.now()))
                .addValue("expiresAt", Timestamp.from(expiresAt));
        return jdbcTemplate.update(INSERT_IGNORE_SQL, params);
    }
}
