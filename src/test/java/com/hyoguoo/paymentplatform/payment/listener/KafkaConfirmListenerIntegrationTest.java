package com.hyoguoo.paymentplatform.payment.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.hyoguoo.paymentplatform.core.common.infrastructure.http.HttpOperator;
import com.hyoguoo.paymentplatform.core.test.BaseKafkaIntegrationTest;
import com.hyoguoo.paymentplatform.mock.FakeTossHttpOperator;
import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentLoadUseCase;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import com.hyoguoo.paymentplatform.payment.presentation.port.PaymentConfirmService;
import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.jdbc.Sql;

@DisplayName("KafkaConfirmListener 통합 테스트")
@Sql(scripts = "/data-test.sql")
class KafkaConfirmListenerIntegrationTest extends BaseKafkaIntegrationTest {

    private static final String TEST_ORDER_ID = FakeTossHttpOperator.TEST_ORDER_ID;
    private static final String TEST_PAYMENT_KEY = FakeTossHttpOperator.TEST_PAYMENT_KEY;
    private static final double TEST_TOTAL_AMOUNT_1 = FakeTossHttpOperator.TEST_TOTAL_AMOUNT_1;
    private static final double TEST_TOTAL_AMOUNT_2 = FakeTossHttpOperator.TEST_TOTAL_AMOUNT_2;

    private static final String PAYMENT_EVENT_INSERT_SQL = """
            INSERT INTO payment_event
                (id, buyer_id, seller_id, order_name, order_id, payment_key, status, approved_at, executed_at, retry_count, created_at, updated_at)
            VALUES
                (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
            """;
    private static final String PAYMENT_ORDER_INSERT_SQL = """
            INSERT INTO payment_order
                (id, payment_event_id, order_id, product_id, quantity, status, amount, created_at, updated_at)
            VALUES
                (?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
            """;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private PaymentLoadUseCase paymentLoadUseCase;

    @Autowired
    private PaymentConfirmService paymentConfirmService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM payment_order");
        jdbcTemplate.update("DELETE FROM payment_event");
    }

    @Test
    @DisplayName("Kafka 메시지 발행 후 컨슈머가 PaymentEvent를 DONE으로 전환한다")
    void kafkaConfirmListener_WhenMessagePublished_PaymentEventBecomeDone() throws Exception {
        // given: DB에 READY 상태의 PaymentEvent + PaymentOrder 준비
        jdbcTemplate.update(PAYMENT_EVENT_INSERT_SQL,
                1L, 1L, 2L, "Ogu T 포함 2건", TEST_ORDER_ID, null,
                PaymentEventStatus.READY.name(), null, null, 0);
        jdbcTemplate.update(PAYMENT_ORDER_INSERT_SQL,
                1L, 1L, TEST_ORDER_ID, 1L, 1, PaymentOrderStatus.NOT_STARTED.name(),
                TEST_TOTAL_AMOUNT_1);
        jdbcTemplate.update(PAYMENT_ORDER_INSERT_SQL,
                2L, 1L, TEST_ORDER_ID, 2L, 2, PaymentOrderStatus.NOT_STARTED.name(),
                TEST_TOTAL_AMOUNT_2);

        PaymentConfirmCommand command = PaymentConfirmCommand.builder()
                .userId(1L)
                .orderId(TEST_ORDER_ID)
                .paymentKey(TEST_PAYMENT_KEY)
                .amount(BigDecimal.valueOf(TEST_TOTAL_AMOUNT_1 + TEST_TOTAL_AMOUNT_2))
                .build();

        // when: KafkaConfirmAdapter.confirm() 호출 → executePayment() + 재고감소 + Kafka 발행
        paymentConfirmService.confirm(command);

        // then: Awaitility polling — 컨슈머가 Kafka 메시지를 소비하고 PaymentEvent를 DONE으로 전환
        await()
                .atMost(30, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .until(() -> paymentLoadUseCase.getPaymentEventByOrderId(TEST_ORDER_ID)
                        .getStatus() == PaymentEventStatus.DONE);

        assertThat(paymentLoadUseCase.getPaymentEventByOrderId(TEST_ORDER_ID).getStatus())
                .isEqualTo(PaymentEventStatus.DONE);
    }

    @Test
    @DisplayName("중복 메시지 발행 시 이미 DONE 상태가 유지된다 (Toss 멱등키 처리)")
    void kafkaConfirmListener_WhenDuplicateMessage_IdempotentProcessing() throws Exception {
        // given: DONE 상태의 PaymentEvent 준비 (이미 Toss 결제 완료된 상태)
        jdbcTemplate.update(PAYMENT_EVENT_INSERT_SQL,
                1L, 1L, 2L, "Ogu T 포함 2건", TEST_ORDER_ID, TEST_PAYMENT_KEY,
                PaymentEventStatus.DONE.name(), "2024-09-29 05:03:19", "2024-09-29 05:00:58", 0);
        jdbcTemplate.update(PAYMENT_ORDER_INSERT_SQL,
                1L, 1L, TEST_ORDER_ID, 1L, 1, PaymentOrderStatus.SUCCESS.name(),
                TEST_TOTAL_AMOUNT_1);
        jdbcTemplate.update(PAYMENT_ORDER_INSERT_SQL,
                2L, 1L, TEST_ORDER_ID, 2L, 2, PaymentOrderStatus.SUCCESS.name(),
                TEST_TOTAL_AMOUNT_2);

        // when: 동일 orderId로 Kafka 메시지 중복 발행
        kafkaTemplate.send("payment-confirm", TEST_ORDER_ID, TEST_ORDER_ID);

        // then: 5초 대기 후 PaymentEvent 상태가 여전히 DONE
        // 중복 메시지는 컨슈머가 처리를 시도하지만 DONE 상태는 변경되지 않는다
        Thread.sleep(5000);

        assertThat(paymentLoadUseCase.getPaymentEventByOrderId(TEST_ORDER_ID).getStatus())
                .isEqualTo(PaymentEventStatus.DONE);
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        public HttpOperator httpOperator() {
            return new FakeTossHttpOperator();
        }
    }
}
