package com.hyoguoo.paymentplatform.payment.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.payment.core.test.BaseIntegrationTest;
import com.hyoguoo.paymentplatform.payment.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentOrderEntity;
import com.hyoguoo.paymentplatform.payment.infrastructure.repository.JpaPaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.infrastructure.repository.JpaPaymentOrderRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

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
    @Autowired
    private LocalDateTimeProvider localDateTimeProvider;

    @BeforeEach
    void setUp() {
        jpaPaymentEventRepository.deleteAllInBatch();
        jpaPaymentOrderRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("스케줄러가 30분 이상 지난 READY 상태의 결제를 EXPIRED로 변경한다")
    void testExpireOldReadyPayments() {
        // given
        LocalDateTime now = LocalDateTime.of(2021, 1, 1, 0, 31, 0);
        LocalDateTime thirtyOneMinutesAgo = now.minusMinutes(31);
        LocalDateTime twentyNineMinutesAgo = now.minusMinutes(29);

        ReflectionTestUtils.invokeMethod(localDateTimeProvider, "setFixedDateTime", now);

        jdbcTemplate.update("""
                        INSERT INTO payment_event
                            (id, buyer_id, seller_id, order_name, order_id, payment_key, status, approved_at, executed_at, retry_count, created_at, updated_at)
                        VALUES
                            (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                100, 1, 1, "Old Order", "order100", null, PaymentEventStatus.READY.name(),
                null, null, 0, thirtyOneMinutesAgo, LocalDateTime.now());
        jdbcTemplate.update(PAYMENT_ORDER_INSERT_SQL,
                100, 100, "order100", 1, 1, PaymentOrderStatus.NOT_STARTED.name(), 50000);

        jdbcTemplate.update("""
                        INSERT INTO payment_event
                            (id, buyer_id, seller_id, order_name, order_id, payment_key, status, approved_at, executed_at, retry_count, created_at, updated_at)
                        VALUES
                            (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                200, 1, 1, "New Order", "order200", null, PaymentEventStatus.READY.name(),
                null, null, 0, twentyNineMinutesAgo, LocalDateTime.now());
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
        // given
        LocalDateTime now = LocalDateTime.of(2021, 1, 1, 0, 31, 0);
        LocalDateTime thirtyOneMinutesAgo = now.minusMinutes(31);

        ReflectionTestUtils.invokeMethod(localDateTimeProvider, "setFixedDateTime", now);

        // IN_PROGRESS 상태 결제 (31분 전)
        jdbcTemplate.update("""
                        INSERT INTO payment_event
                            (id, buyer_id, seller_id, order_name, order_id, payment_key, status, approved_at, executed_at, retry_count, created_at, updated_at)
                        VALUES
                            (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                300, 1, 1, "In Progress Order", "order300", "key300", PaymentEventStatus.IN_PROGRESS.name(),
                null, thirtyOneMinutesAgo, 0, thirtyOneMinutesAgo, LocalDateTime.now());
        jdbcTemplate.update(PAYMENT_ORDER_INSERT_SQL,
                300, 300, "order300", 1, 1, PaymentOrderStatus.EXECUTING.name(), 50000);

        // DONE 상태 결제 (31분 전)
        jdbcTemplate.update("""
                        INSERT INTO payment_event
                            (id, buyer_id, seller_id, order_name, order_id, payment_key, status, approved_at, executed_at, retry_count, created_at, updated_at)
                        VALUES
                            (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                400, 1, 1, "Done Order", "order400", "key400", PaymentEventStatus.DONE.name(),
                thirtyOneMinutesAgo, null, 0, thirtyOneMinutesAgo, LocalDateTime.now());
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
