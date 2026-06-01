package com.hyoguoo.paymentplatform.payment.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.core.test.BaseIntegrationTest;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.UserInfo;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentGatewayType;
import com.hyoguoo.paymentplatform.payment.infrastructure.repository.JpaPaymentEventRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class PaymentEventRepositoryImplTest extends BaseIntegrationTest {

    @Autowired
    private PaymentEventRepository paymentEventRepository;

    @Autowired
    private JpaPaymentEventRepository jpaPaymentEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        jpaPaymentEventRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("findReadyPaymentsOlderThan(Instant) — cutoff 이전 READY 결제만 반환한다.")
    void findReadyPaymentsOlderThan_withInstantCutoff_returnsOnlyOlderPayments() {
        // given — READY 결제 2건을 raw SQL 로 직접 삽입하여 created_at 을 제어한다.
        // created_at 은 LocalDateTime (BaseEntity), JPQL 비교도 LocalDateTime 기준.
        // 테스트에서 두 건의 created_at 시각 차이를 명확히 하여 cutoff 경계를 검증한다.

        // older: 현재 UTC - 61분 (cutoff 이전 → 조회 대상)
        LocalDateTime olderCreatedAt = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(61);
        // newer: 현재 UTC + 5분 (cutoff 이후 → 조회 제외)
        LocalDateTime newerCreatedAt = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5);

        jdbcTemplate.update("""
                        INSERT INTO payment_event
                            (buyer_id, seller_id, order_name, order_id, gateway_type, status, retry_count, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                1L, 2L, "older-order", "older-order-id-1", "TOSS", "READY", 0,
                olderCreatedAt, olderCreatedAt);

        Long olderEventId = jdbcTemplate.queryForObject(
                "SELECT id FROM payment_event WHERE order_id = ?",
                Long.class, "older-order-id-1");

        jdbcTemplate.update("""
                        INSERT INTO payment_event
                            (buyer_id, seller_id, order_name, order_id, gateway_type, status, retry_count, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                1L, 2L, "newer-order", "newer-order-id-1", "TOSS", "READY", 0,
                newerCreatedAt, newerCreatedAt);

        Long newerEventId = jdbcTemplate.queryForObject(
                "SELECT id FROM payment_event WHERE order_id = ?",
                Long.class, "newer-order-id-1");

        // cutoff = 현재 UTC - 30분 (older 포함, newer 제외)
        Instant cutoff = Instant.now().minusSeconds(30 * 60);

        // when
        List<PaymentEvent> result = paymentEventRepository.findReadyPaymentsOlderThan(cutoff);

        // then
        List<Long> resultIds = result.stream().map(PaymentEvent::getId).toList();
        assertThat(resultIds).contains(olderEventId);
        assertThat(resultIds).doesNotContain(newerEventId);
    }
}
