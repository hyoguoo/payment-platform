package com.hyoguoo.paymentplatform.payment.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.payment.core.test.BaseIntegrationTest;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentOrderEntity;
import com.hyoguoo.paymentplatform.payment.infrastructure.repository.JpaPaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.infrastructure.repository.JpaPaymentOrderRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PaymentScheduler 통합 테스트.
 *
 * <p>현재 시각 기준의 상대 시각을 사용하여 TZ 독립적으로 테스트한다.
 * DB 데이터를 현재 시각 기준 상대 시각으로 삽입하고, 스케줄러의 cutoff 는
 * application 계층 Clock 기준(기본: systemUTC)으로 자동 계산된다.
 */
class PaymentSchedulerTest extends BaseIntegrationTest {

    private static final String PAYMENT_ORDER_INSERT_SQL = """
            INSERT INTO payment_order
                (id, payment_event_id, order_id, product_id, quantity, status, amount, created_at, updated_at)
            VALUES
                (?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
            """;

    @Autowired
    private JpaPaymentEventRepository jpaPaymentEventRepository;
    @Autowired
    private JpaPaymentOrderRepository jpaPaymentOrderRepository;
    @Autowired
    private PaymentScheduler paymentScheduler;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jpaPaymentEventRepository.deleteAllInBatch();
        jpaPaymentOrderRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("스케줄러가 30분 이상 지난 READY 상태의 결제를 EXPIRED로 변경한다")
    void testExpireOldReadyPayments() {
        // given — 현재 시각(KST/UTC 무관) 기준 상대 시각으로 데이터 삽입.
        // TestClock 에 현재 시각을 고정하면 getReadyPaymentsOlder() 의 cutoff = now - 30min.
        Instant nowInstant = Instant.now();
        LocalDateTime nowLdt = LocalDateTime.ofInstant(nowInstant, ZoneOffset.UTC);
        LocalDateTime thirtyOneMinutesAgo = nowLdt.minus(31, ChronoUnit.MINUTES);
        LocalDateTime twentyNineMinutesAgo = nowLdt.minus(29, ChronoUnit.MINUTES);

        jdbcTemplate.update("""
                        INSERT INTO payment_event
                            (id, buyer_id, seller_id, order_name, order_id, payment_key, gateway_type, status, approved_at, executed_at, retry_count, created_at, updated_at)
                        VALUES
                            (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                100, 1, 1, "Old Order", "order100", null, "TOSS", PaymentEventStatus.READY.name(),
                null, null, 0, thirtyOneMinutesAgo, nowLdt);
        jdbcTemplate.update(PAYMENT_ORDER_INSERT_SQL,
                100, 100, "order100", 1, 1, PaymentOrderStatus.NOT_STARTED.name(), 50000);

        jdbcTemplate.update("""
                        INSERT INTO payment_event
                            (id, buyer_id, seller_id, order_name, order_id, payment_key, gateway_type, status, approved_at, executed_at, retry_count, created_at, updated_at)
                        VALUES
                            (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                200, 1, 1, "New Order", "order200", null, "TOSS", PaymentEventStatus.READY.name(),
                null, null, 0, twentyNineMinutesAgo, nowLdt);
        jdbcTemplate.update(PAYMENT_ORDER_INSERT_SQL,
                200, 200, "order200", 2, 1, PaymentOrderStatus.NOT_STARTED.name(), 30000);

        // when
        paymentScheduler.expireOldReadyPayments();

        // then
        PaymentEvent expiredPayment = jpaPaymentEventRepository.findById(100L)
                .map(payment -> {
                    List<PaymentOrder> paymentOrderList = jpaPaymentOrderRepository.findByPaymentEventId(
                                    payment.getId())
                            .stream()
                            .map(PaymentOrderEntity::toDomain).toList();
                    return payment.toDomain(paymentOrderList);
                }).orElseThrow();

        PaymentEvent notExpiredPayment = jpaPaymentEventRepository.findById(200L)
                .map(payment -> {
                    List<PaymentOrder> paymentOrderList = jpaPaymentOrderRepository.findByPaymentEventId(
                                    payment.getId())
                            .stream()
                            .map(PaymentOrderEntity::toDomain).toList();
                    return payment.toDomain(paymentOrderList);
                }).orElseThrow();

        // 31분 전 결제는 EXPIRED 상태
        assertThat(expiredPayment.getStatus()).isEqualTo(PaymentEventStatus.EXPIRED);
        assertThat(expiredPayment.getPaymentOrderList()).allMatch(
                order -> order.getStatus() == PaymentOrderStatus.EXPIRED
        );

        // 29분 전 결제는 여전히 READY 상태
        assertThat(notExpiredPayment.getStatus()).isEqualTo(PaymentEventStatus.READY);
        assertThat(notExpiredPayment.getPaymentOrderList()).allMatch(
                order -> order.getStatus() == PaymentOrderStatus.NOT_STARTED
        );
    }

    @Test
    @DisplayName("스케줄러가 READY가 아닌 상태의 결제는 만료시키지 않는다")
    void testExpireOldReadyPayments_NotReadyStatus() {
        // given — 현재 시각 기준 상대 시각 사용
        Instant nowInstant = Instant.now();
        LocalDateTime nowLdt = LocalDateTime.ofInstant(nowInstant, ZoneOffset.UTC);
        LocalDateTime thirtyOneMinutesAgo = nowLdt.minus(31, ChronoUnit.MINUTES);

        // IN_PROGRESS 상태 결제 (31분 전)
        jdbcTemplate.update("""
                        INSERT INTO payment_event
                            (id, buyer_id, seller_id, order_name, order_id, payment_key, gateway_type, status, approved_at, executed_at, retry_count, created_at, updated_at)
                        VALUES
                            (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                300, 1, 1, "In Progress Order", "order300", "key300", "TOSS", PaymentEventStatus.IN_PROGRESS.name(),
                null, thirtyOneMinutesAgo, 0, thirtyOneMinutesAgo, nowLdt);
        jdbcTemplate.update(PAYMENT_ORDER_INSERT_SQL,
                300, 300, "order300", 1, 1, PaymentOrderStatus.EXECUTING.name(), 50000);

        // DONE 상태 결제 (31분 전)
        jdbcTemplate.update("""
                        INSERT INTO payment_event
                            (id, buyer_id, seller_id, order_name, order_id, payment_key, gateway_type, status, approved_at, executed_at, retry_count, created_at, updated_at)
                        VALUES
                            (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                400, 1, 1, "Done Order", "order400", "key400", "TOSS", PaymentEventStatus.DONE.name(),
                thirtyOneMinutesAgo, null, 0, thirtyOneMinutesAgo, nowLdt);
        jdbcTemplate.update(PAYMENT_ORDER_INSERT_SQL,
                400, 400, "order400", 2, 1, PaymentOrderStatus.SUCCESS.name(), 30000);

        // when
        paymentScheduler.expireOldReadyPayments();

        // then
        PaymentEvent inProgressPayment = jpaPaymentEventRepository.findById(300L)
                .map(payment -> {
                    List<PaymentOrder> paymentOrderList = jpaPaymentOrderRepository.findByPaymentEventId(
                                    payment.getId())
                            .stream()
                            .map(PaymentOrderEntity::toDomain).toList();
                    return payment.toDomain(paymentOrderList);
                }).orElseThrow();

        PaymentEvent donePayment = jpaPaymentEventRepository.findById(400L)
                .map(payment -> {
                    List<PaymentOrder> paymentOrderList = jpaPaymentOrderRepository.findByPaymentEventId(
                                    payment.getId())
                            .stream()
                            .map(PaymentOrderEntity::toDomain).toList();
                    return payment.toDomain(paymentOrderList);
                }).orElseThrow();

        // 둘 다 상태가 변경되지 않음
        assertThat(inProgressPayment.getStatus()).isEqualTo(PaymentEventStatus.IN_PROGRESS);
        assertThat(inProgressPayment.getPaymentOrderList()).allMatch(
                order -> order.getStatus() == PaymentOrderStatus.EXECUTING
        );

        assertThat(donePayment.getStatus()).isEqualTo(PaymentEventStatus.DONE);
        assertThat(donePayment.getPaymentOrderList()).allMatch(
                order -> order.getStatus() == PaymentOrderStatus.SUCCESS
        );
    }
}
