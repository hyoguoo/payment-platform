package com.hyoguoo.paymentplatform.payment.infrastructure.repository;

import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentStatusQueryPort;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentStatusSnapshot;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * PaymentStatusQueryPort JDBC 구현체 — 폴링 조회 전용.
 *
 * <p>{@code paymentReplicaJdbcTemplate}(읽기 복제본 데이터소스)만 쓴다. 돈 경로 판정이 쓰는
 * JPA 영속성 컨텍스트를 태우지 않고 필요한 컬럼만 직접 읽는다. {@code @Transactional} 을
 * 붙이지 않는다 — 단건 SELECT 라 트랜잭션 경계가 필요 없다.
 *
 * <p>발행 상태 필터(발행 대기 또는 발행 중)는 SQL 조건이 아니라 도메인 열거형으로 변환한
 * 뒤 자바 코드에서 판정한다 — SQL 에 상태 문자열을 흩뿌리지 않는다.
 */
@Repository
public class PaymentStatusQueryJdbcAdapter implements PaymentStatusQueryPort {

    private static final String FIND_OUTBOX_STATUS_SQL =
            "SELECT status FROM payment_outbox WHERE order_id = ?";

    private static final String FIND_STATUS_SNAPSHOT_SQL =
            "SELECT order_id, status, approved_at FROM payment_event WHERE order_id = ?";

    private final JdbcTemplate jdbcTemplate;

    public PaymentStatusQueryJdbcAdapter(
            @Qualifier("paymentReplicaJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<PaymentOutboxStatus> findActiveOutboxStatus(String orderId) {
        return queryOutboxStatus(orderId)
                .filter(status -> status.isClaimable() || status.isInFlight());
    }

    @Override
    public Optional<PaymentStatusSnapshot> findStatusSnapshot(String orderId) {
        return jdbcTemplate.query(FIND_STATUS_SNAPSHOT_SQL, this::mapStatusSnapshot, orderId)
                .stream()
                .findFirst();
    }

    private Optional<PaymentOutboxStatus> queryOutboxStatus(String orderId) {
        return jdbcTemplate.query(
                        FIND_OUTBOX_STATUS_SQL,
                        (rs, rowNum) -> PaymentOutboxStatus.valueOf(rs.getString("status")),
                        orderId)
                .stream()
                .findFirst();
    }

    private PaymentStatusSnapshot mapStatusSnapshot(ResultSet rs, int rowNum) throws SQLException {
        Timestamp approvedAtTimestamp = rs.getTimestamp("approved_at");
        Instant approvedAt = approvedAtTimestamp != null ? approvedAtTimestamp.toInstant() : null;
        return new PaymentStatusSnapshot(
                rs.getString("order_id"),
                PaymentEventStatus.valueOf(rs.getString("status")),
                approvedAt);
    }
}
