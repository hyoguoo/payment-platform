package com.hyoguoo.paymentplatform.payment.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.IntegrationTest;
import com.hyoguoo.paymentplatform.core.common.infrastructure.http.HttpOperator;
import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.core.response.BasicResponse;
import com.hyoguoo.paymentplatform.mixin.BasicResponseMixin;
import com.hyoguoo.paymentplatform.mixin.CheckoutResponseMixin;
import com.hyoguoo.paymentplatform.mixin.PaymentConfirmResponseMixin;
import com.hyoguoo.paymentplatform.mock.FakeTossHttpOperator;
import com.hyoguoo.paymentplatform.mock.TestLocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentOrderEntity;
import com.hyoguoo.paymentplatform.payment.infrastructure.repository.JpaPaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.infrastructure.repository.JpaPaymentOrderRepository;
import com.hyoguoo.paymentplatform.payment.presentation.dto.response.CheckoutResponse;
import com.hyoguoo.paymentplatform.payment.presentation.dto.response.PaymentConfirmResponse;
import com.hyoguoo.paymentplatform.paymentgateway.exception.common.TossPaymentErrorCode;
import com.hyoguoo.paymentplatform.product.domain.Product;
import com.hyoguoo.paymentplatform.product.infrastructure.repository.JpaProductRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class PaymentSchedulerTest extends IntegrationTest {

    private static final String PAYMENT_EVENT_INSERT_SQL = """
            INSERT INTO payment_event
                (id, buyer_id, seller_id, order_name, order_id, payment_key, status, approved_at, executed_at, retry_count, created_at, updated_at)
            VALUES
                (?, ?, ?, ?, ?, ?, ?, ?, ?, ?,  NOW(), NOW())
            """;
    private static final String PAYMENT_ORDER_INSERT_SQL = """
            INSERT INTO payment_order
                (id, payment_event_id, order_id, product_id, quantity, status, amount, created_at, updated_at)
            VALUES
                (?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
            """;
    private static final String UPDATE_PRODUCT_STOCK_SQL = """
            UPDATE product
            SET stock = ?
            WHERE id = ?
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();
    @Autowired
    private JpaPaymentEventRepository jpaPaymentEventRepository;
    @Autowired
    private JpaPaymentOrderRepository jpaPaymentOrderRepository;
    @Autowired
    private JpaProductRepository jpaProductRepository;
    @Autowired
    private PaymentScheduler paymentScheduler;
    @Autowired
    private HttpOperator httpOperator;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private LocalDateTimeProvider localDateTimeProvider;
    @Value("${spring.myapp.toss-payments.http.read-timeout-millis}")
    private int readTimeoutMillisLimit;

    static Stream<Arguments> providePaymentEventData() {
        return Stream.of(
                Arguments.of(6, PaymentEventStatus.IN_PROGRESS),  // 5분 전 IN_PROGRESS
                Arguments.of(6, PaymentEventStatus.UNKNOWN),      // UNKNOWN 상태
                Arguments.of(4, PaymentEventStatus.UNKNOWN)       // UNKNOWN 상태
        );
    }

    @BeforeEach
    void setUp() {
        objectMapper.addMixIn(CheckoutResponse.class, CheckoutResponseMixin.class);
        objectMapper.addMixIn(PaymentConfirmResponse.class, PaymentConfirmResponseMixin.class);
        objectMapper.addMixIn(BasicResponse.class, BasicResponseMixin.class);
        ReflectionTestUtils.invokeMethod(httpOperator, "setDelayRange", 0, 0);
        ReflectionTestUtils.invokeMethod(httpOperator, "clearErrorInPostRequest");
        jpaPaymentEventRepository.deleteAllInBatch();
        jpaPaymentOrderRepository.deleteAllInBatch();
    }

    @ParameterizedTest
    @MethodSource("providePaymentEventData")
    @DisplayName("UNKNOWN 혹은 5분 이전의 IN_PROGRESS 상태의 결제 이벤트에 대해서만 재시도하고, DONE / SUCCESS 상태로 변경한다.")
    void testRecoverRetryablePayment(int offsetMinute, PaymentEventStatus status) {
        // given
        LocalDateTime now = LocalDateTime.of(2021, 1, 1, 0, 0, 0);
        ReflectionTestUtils.invokeMethod(localDateTimeProvider, "setFixedDateTime", now);
        LocalDateTime executedAt = now.minusMinutes(offsetMinute);
        int initRetryCount = 0;

        jdbcTemplate.update(PAYMENT_EVENT_INSERT_SQL,
                1, 1, 1, "orderName", "orderId", "paymentKey", status.name(), null, executedAt, initRetryCount);
        jdbcTemplate.update(PAYMENT_ORDER_INSERT_SQL, 1, 1, "orderId", 1, 1, PaymentOrderStatus.UNKNOWN.name(), 50000);
        jdbcTemplate.update(PAYMENT_ORDER_INSERT_SQL, 2, 1, "orderId", 2, 1, PaymentOrderStatus.UNKNOWN.name(), 30000);

        // when
        paymentScheduler.recoverRetryablePayment();

        // then
        PaymentEvent paymentEvent = jpaPaymentEventRepository.findById(1L)
                .map(payment -> {
                    List<PaymentOrder> paymentOrderList = jpaPaymentOrderRepository.findByPaymentEventId(
                                    payment.getId())
                            .stream()
                            .map(PaymentOrderEntity::toDomain).toList();
                    return payment.toDomain(paymentOrderList);
                }).orElseThrow();
        List<PaymentOrder> paymentOrderList = paymentEvent.getPaymentOrderList();

        assertThat(paymentEvent.getStatus()).isEqualTo(PaymentEventStatus.DONE);
        assertThat(paymentEvent.getRetryCount()).isEqualTo(initRetryCount + 1);
        assertThat(paymentOrderList).isNotEmpty()
                .allMatch(paymentOrder -> paymentOrder.getStatus() == PaymentOrderStatus.SUCCESS);
    }

    @Test
    @DisplayName("재시도 가능한 에러가 발생하여 다시 UNKNOWN 처리된다.")
    void testRecoverRetryablePayment_RetryableFailure() {
        // given
        int initRetryCount = 0;

        jdbcTemplate.update(PAYMENT_EVENT_INSERT_SQL,
                1, 1, 1, "orderName", "orderId", "paymentKey", PaymentEventStatus.UNKNOWN.name(), null,
                LocalDateTime.now(), initRetryCount);
        jdbcTemplate.update(PAYMENT_ORDER_INSERT_SQL, 1, 1, "orderId", 1, 1, PaymentOrderStatus.UNKNOWN.name(), 50000);
        jdbcTemplate.update(PAYMENT_ORDER_INSERT_SQL, 2, 1, "orderId", 2, 1, PaymentOrderStatus.UNKNOWN.name(), 30000);

        ReflectionTestUtils.invokeMethod(httpOperator, "addErrorInPostRequest",
                TossPaymentErrorCode.PROVIDER_ERROR.name(),
                TossPaymentErrorCode.PROVIDER_ERROR.getDescription()
        );

        // when
        paymentScheduler.recoverRetryablePayment();

        // then
        PaymentEvent paymentEvent = jpaPaymentEventRepository.findById(1L)
                .map(payment -> {
                    List<PaymentOrder> paymentOrderList = jpaPaymentOrderRepository.findByPaymentEventId(
                                    payment.getId())
                            .stream()
                            .map(PaymentOrderEntity::toDomain).toList();
                    return payment.toDomain(paymentOrderList);
                }).orElseThrow();
        List<PaymentOrder> paymentOrderList = paymentEvent.getPaymentOrderList();

        assertThat(paymentEvent.getStatus()).isEqualTo(PaymentEventStatus.UNKNOWN);
        assertThat(paymentEvent.getRetryCount()).isEqualTo(initRetryCount + 1);
        assertThat(paymentOrderList).isNotEmpty()
                .allMatch(paymentOrder -> paymentOrder.getStatus() == PaymentOrderStatus.UNKNOWN);
    }


    @Test
    @DisplayName("재시도 중 타임아웃이 발생하여 다시 UNKNOWN 처리된다.")
    void testRecoverRetryablePayment_Timeout() {
        // given
        int initRetryCount = 0;

        jdbcTemplate.update(PAYMENT_EVENT_INSERT_SQL,
                1, 1, 1, "orderName", "orderId", "paymentKey", PaymentEventStatus.UNKNOWN.name(), null,
                LocalDateTime.now(), initRetryCount);
        jdbcTemplate.update(PAYMENT_ORDER_INSERT_SQL, 1, 1, "orderId", 1, 1, PaymentOrderStatus.UNKNOWN.name(), 50000);
        jdbcTemplate.update(PAYMENT_ORDER_INSERT_SQL, 2, 1, "orderId", 2, 1, PaymentOrderStatus.UNKNOWN.name(), 30000);

        ReflectionTestUtils.invokeMethod(httpOperator, "setDelayRange",
                readTimeoutMillisLimit + 1000, readTimeoutMillisLimit + 2000
        );

        // when
        paymentScheduler.recoverRetryablePayment();

        // then
        PaymentEvent paymentEvent = jpaPaymentEventRepository.findById(1L)
                .map(payment -> {
                    List<PaymentOrder> paymentOrderList = jpaPaymentOrderRepository.findByPaymentEventId(
                                    payment.getId())
                            .stream()
                            .map(PaymentOrderEntity::toDomain).toList();
                    return payment.toDomain(paymentOrderList);
                }).orElseThrow();
        List<PaymentOrder> paymentOrderList = paymentEvent.getPaymentOrderList();

        assertThat(paymentEvent.getStatus()).isEqualTo(PaymentEventStatus.UNKNOWN);
        assertThat(paymentEvent.getRetryCount()).isEqualTo(initRetryCount + 1);
        assertThat(paymentOrderList).isNotEmpty()
                .allMatch(paymentOrder -> paymentOrder.getStatus() == PaymentOrderStatus.UNKNOWN);
    }

    @Test
    @DisplayName("재시도 불가능한 에러가 발생하여 FAIL 처리 후 재고가 증가된다.")
    void testRecoverRetryablePayment_NonRetryableFailure() {
        // given
        int initRetryCount = 0;
        int initStock = 10;
        int orderedQuantity1 = 1;
        int orderedQuantity2 = 2;

        jdbcTemplate.update(PAYMENT_EVENT_INSERT_SQL,
                1, 1, 1, "orderName", "orderId", "paymentKey", PaymentEventStatus.UNKNOWN.name(), null,
                LocalDateTime.now(), initRetryCount);
        jdbcTemplate.update(PAYMENT_ORDER_INSERT_SQL,
                1, 1, "orderId", 1, orderedQuantity1, PaymentOrderStatus.UNKNOWN.name(), 50000);
        jdbcTemplate.update(PAYMENT_ORDER_INSERT_SQL,
                2, 1, "orderId", 2, orderedQuantity2, PaymentOrderStatus.UNKNOWN.name(), 30000);
        jdbcTemplate.update(UPDATE_PRODUCT_STOCK_SQL, initStock, 1);
        jdbcTemplate.update(UPDATE_PRODUCT_STOCK_SQL, initStock, 2);

        ReflectionTestUtils.invokeMethod(httpOperator, "addErrorInPostRequest",
                TossPaymentErrorCode.INVALID_STOPPED_CARD.name(),
                TossPaymentErrorCode.INVALID_STOPPED_CARD.getDescription()
        );

        // when
        paymentScheduler.recoverRetryablePayment();

        // then
        PaymentEvent paymentEvent = jpaPaymentEventRepository.findById(1L)
                .map(payment -> {
                    List<PaymentOrder> paymentOrderList = jpaPaymentOrderRepository.findByPaymentEventId(
                                    payment.getId())
                            .stream()
                            .map(PaymentOrderEntity::toDomain).toList();
                    return payment.toDomain(paymentOrderList);
                }).orElseThrow();
        List<PaymentOrder> paymentOrderList = paymentEvent.getPaymentOrderList();

        Product product1 = jpaProductRepository.findById(1L).orElseThrow().toDomain();
        Product product2 = jpaProductRepository.findById(2L).orElseThrow().toDomain();

        assertThat(paymentEvent.getStatus()).isEqualTo(PaymentEventStatus.FAILED);
        assertThat(paymentOrderList).isNotEmpty()
                .allMatch(paymentOrder -> paymentOrder.getStatus() == PaymentOrderStatus.FAIL);
        assertThat(product1.getStock()).isEqualTo(initStock + orderedQuantity1);
        assertThat(product2.getStock()).isEqualTo(initStock + orderedQuantity2);
    }

    @Test
    @DisplayName("특정 시간 이내의 IN_PROGRESS 상태의 결제는 재시도하지 않는다.")
    void testDoNotRetryWithin5Minutes() {
        // given
        int retryableMinutesForInProgress = PaymentEvent.RETRYABLE_MINUTES_FOR_IN_PROGRESS;
        LocalDateTime now = LocalDateTime.of(2021, 1, 1, 0, 0, 0);
        ReflectionTestUtils.invokeMethod(localDateTimeProvider, "setFixedDateTime", now);
        LocalDateTime executedAt = now.minusMinutes(retryableMinutesForInProgress - 1);

        int retryCount = 0;

        jdbcTemplate.update(PAYMENT_EVENT_INSERT_SQL,
                1, 1, 1, "orderName", "orderId", "paymentKey", PaymentEventStatus.IN_PROGRESS.name(), null,
                executedAt, retryCount);
        jdbcTemplate.update(PAYMENT_ORDER_INSERT_SQL,
                1, 1, "orderId", 1, 1, PaymentOrderStatus.EXECUTING.name(), 50000);
        jdbcTemplate.update(PAYMENT_ORDER_INSERT_SQL,
                2, 1, "orderId", 2, 2, PaymentOrderStatus.EXECUTING.name(), 30000);

        // when
        paymentScheduler.recoverRetryablePayment();

        // then
        PaymentEvent paymentEvent = jpaPaymentEventRepository.findById(1L)
                .map(payment -> {
                    List<PaymentOrder> paymentOrderList = jpaPaymentOrderRepository.findByPaymentEventId(
                                    payment.getId())
                            .stream()
                            .map(PaymentOrderEntity::toDomain).toList();
                    return payment.toDomain(paymentOrderList);
                }).orElseThrow();
        List<PaymentOrder> paymentOrderList = paymentEvent.getPaymentOrderList();

        assertThat(paymentEvent.getStatus()).isEqualTo(PaymentEventStatus.IN_PROGRESS);
        assertThat(paymentOrderList).isNotEmpty()
                .allMatch(paymentOrder -> paymentOrder.getStatus() == PaymentOrderStatus.EXECUTING);
        assertThat(paymentEvent.getRetryCount()).isEqualTo(retryCount);
    }

    @Test
    @DisplayName("최대 재시도 횟수 초과 시 결제는 FAIL 처리되고 재고가 복구된다.")
    void testMaxRetryCountExceeded() {
        // given
        int maxRetryCount = PaymentEvent.RETRYABLE_LIMIT;
        int initStock = 10;
        int orderedQuantity1 = 1;
        int orderedQuantity2 = 2;

        jdbcTemplate.update(PAYMENT_EVENT_INSERT_SQL,
                1, 1, 1, "orderName", "orderId", "paymentKey", PaymentEventStatus.UNKNOWN.name(), null,
                LocalDateTime.now(), maxRetryCount);
        jdbcTemplate.update(PAYMENT_ORDER_INSERT_SQL,
                1, 1, "orderId", 1, orderedQuantity1, PaymentOrderStatus.UNKNOWN.name(), 50000);
        jdbcTemplate.update(PAYMENT_ORDER_INSERT_SQL,
                2, 1, "orderId", 2, orderedQuantity2, PaymentOrderStatus.UNKNOWN.name(), 30000);
        jdbcTemplate.update(UPDATE_PRODUCT_STOCK_SQL, initStock, 1);
        jdbcTemplate.update(UPDATE_PRODUCT_STOCK_SQL, initStock, 2);

        // when
        paymentScheduler.recoverRetryablePayment();

        // then
        PaymentEvent paymentEvent = jpaPaymentEventRepository.findById(1L)
                .map(payment -> {
                    List<PaymentOrder> paymentOrderList = jpaPaymentOrderRepository.findByPaymentEventId(
                                    payment.getId())
                            .stream()
                            .map(PaymentOrderEntity::toDomain).toList();
                    return payment.toDomain(paymentOrderList);
                }).orElseThrow();
        List<PaymentOrder> paymentOrderList = paymentEvent.getPaymentOrderList();
        Product product1 = jpaProductRepository.findById(1L).orElseThrow().toDomain();
        Product product2 = jpaProductRepository.findById(2L).orElseThrow().toDomain();


        assertThat(paymentEvent.getStatus()).isEqualTo(PaymentEventStatus.FAILED);
        assertThat(paymentOrderList).isNotEmpty()
                .allMatch(paymentOrder -> paymentOrder.getStatus() == PaymentOrderStatus.FAIL);
        assertThat(product1.getStock()).isEqualTo(initStock + orderedQuantity1);
        assertThat(product2.getStock()).isEqualTo(initStock + orderedQuantity2);
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        public HttpOperator httpOperator() {
            return new FakeTossHttpOperator();
        }

        @Bean
        public LocalDateTimeProvider localDateTimeProvider() {
            return new TestLocalDateTimeProvider();
        }
    }
}
