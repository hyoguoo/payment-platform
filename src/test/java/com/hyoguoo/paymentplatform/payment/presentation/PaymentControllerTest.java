package com.hyoguoo.paymentplatform.payment.presentation;


import static com.hyoguoo.paymentplatform.mock.FakeTossHttpOperator.TEST_ORDER_ID;
import static com.hyoguoo.paymentplatform.mock.FakeTossHttpOperator.TEST_PAYMENT_KEY;
import static com.hyoguoo.paymentplatform.mock.FakeTossHttpOperator.TEST_TOTAL_AMOUNT_1;
import static com.hyoguoo.paymentplatform.mock.FakeTossHttpOperator.TEST_TOTAL_AMOUNT_2;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.IntegrationTest;
import com.hyoguoo.paymentplatform.core.common.infrastructure.http.HttpOperator;
import com.hyoguoo.paymentplatform.core.response.BasicResponse;
import com.hyoguoo.paymentplatform.mixin.BasicResponseMixin;
import com.hyoguoo.paymentplatform.mixin.CheckoutResponseMixin;
import com.hyoguoo.paymentplatform.mixin.PaymentConfirmResponseMixin;
import com.hyoguoo.paymentplatform.mock.FakeTossHttpOperator;
import com.hyoguoo.paymentplatform.payment.application.dto.vo.OrderedProduct;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentOrderEntity;
import com.hyoguoo.paymentplatform.payment.infrastructure.repository.JpaPaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.infrastructure.repository.JpaPaymentOrderRepository;
import com.hyoguoo.paymentplatform.payment.presentation.dto.request.CheckoutRequest;
import com.hyoguoo.paymentplatform.payment.presentation.dto.request.PaymentConfirmRequest;
import com.hyoguoo.paymentplatform.payment.presentation.dto.response.CheckoutResponse;
import com.hyoguoo.paymentplatform.payment.presentation.dto.response.PaymentConfirmResponse;
import com.hyoguoo.paymentplatform.paymentgateway.exception.common.TossPaymentErrorCode;
import com.hyoguoo.paymentplatform.product.domain.Product;
import com.hyoguoo.paymentplatform.product.infrastructure.repository.JpaProductRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

class PaymentControllerTest extends IntegrationTest {

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
    private static final String UPDATE_PRODUCT_STOCK_SQL = """
            UPDATE product
            SET stock = ?
            WHERE id = ?
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JpaPaymentEventRepository jpaPaymentEventRepository;
    @Autowired
    private JpaPaymentOrderRepository jpaPaymentOrderRepository;
    @Autowired
    private JpaProductRepository jpaProductRepository;
    @Autowired
    private HttpOperator httpOperator;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Value("${spring.myapp.toss-payments.http.read-timeout-millis}")
    private int readTimeoutMillisLimit;

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

    @Test
    @DisplayName("Checkout 요청 시 주문이 READY / NOT_STARTED 상태로 생성되며, 저장 된 ORDER_ID와 올바른 총 금액을 반환한다.")
    void checkout_Success() throws Exception {
        // given
        CheckoutRequest checkoutRequest = CheckoutRequest.builder()
                .userId(1L)
                .orderedProductList(List.of(
                        OrderedProduct.builder()
                                .productId(1L)
                                .quantity(1)
                                .build(),
                        OrderedProduct.builder()
                                .productId(2L)
                                .quantity(2)
                                .build()
                ))
                .build();

        // when
        ResultActions perform = mockMvc.perform(post("/api/v1/payments/checkout")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(checkoutRequest)));

        // then
        PaymentEvent updatedPaymentEvent = getPaymentEvent();

        PaymentEventStatus status = updatedPaymentEvent.getStatus();
        BigDecimal totalAmount = updatedPaymentEvent.getPaymentOrderList().stream()
                .map(PaymentOrder::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        perform.andExpect(status().isOk())
                .andExpect(result -> {
                    String responseContent = result.getResponse().getContentAsString();

                    BasicResponse<CheckoutResponse> apiResponse = objectMapper.readValue(
                            responseContent,
                            new TypeReference<>() {
                            }
                    );

                    CheckoutResponse checkoutResponse = apiResponse.getData();

                    assertThat(checkoutResponse.getOrderId()).isEqualTo(updatedPaymentEvent.getOrderId());
                    assertThat(checkoutResponse.getTotalAmount()).isEqualTo(totalAmount);
                });

        assertThat(status).isEqualTo(PaymentEventStatus.READY);
        assertThat(updatedPaymentEvent.getPaymentOrderList())
                .allMatch(paymentOrder -> paymentOrder.getStatus() == PaymentOrderStatus.NOT_STARTED);
    }

    @Test
    @DisplayName("Payment Confirm 요청이 성공하면 결제가 승인되고 DONE / SUCCESS 상태로 변경되면서 재고가 감소한다.")
    void confirmPayment_Success() throws Exception {
        // given
        final int INIT_PRODUCT_1_STOCK = 1;
        final int INIT_PRODUCT_2_STOCK = 2;
        final int ORDERED_QUANTITY_1 = 1;
        final int ORDERED_QUANTITY_2 = 2;

        jdbcTemplate.update(PAYMENT_EVENT_INSERT_SQL,
                1L, 1L, 2L, "Ogu T 포함 2건", TEST_ORDER_ID, null, PaymentEventStatus.READY.name(), null, null, 0);
        jdbcTemplate.update(PAYMENT_ORDER_INSERT_SQL,
                1L, 1L, TEST_ORDER_ID, 1L, ORDERED_QUANTITY_1, PaymentOrderStatus.NOT_STARTED.name(), TEST_TOTAL_AMOUNT_1);
        jdbcTemplate.update(PAYMENT_ORDER_INSERT_SQL,
                2L, 1L, TEST_ORDER_ID, 2L, ORDERED_QUANTITY_2, PaymentOrderStatus.NOT_STARTED.name(), TEST_TOTAL_AMOUNT_2);
        jdbcTemplate.update(UPDATE_PRODUCT_STOCK_SQL, INIT_PRODUCT_1_STOCK, 1L);
        jdbcTemplate.update(UPDATE_PRODUCT_STOCK_SQL, INIT_PRODUCT_2_STOCK, 2L);

        PaymentConfirmRequest confirmRequest = PaymentConfirmRequest.builder()
                .userId(1L)
                .orderId(TEST_ORDER_ID)
                .amount(BigDecimal.valueOf(TEST_TOTAL_AMOUNT_1 + TEST_TOTAL_AMOUNT_2))
                .paymentKey(TEST_PAYMENT_KEY)
                .build();

        // when
        ResultActions perform = mockMvc.perform(
                post("/api/v1/payments/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(confirmRequest))
        );

        // then
        PaymentEvent updatedPaymentEvent = getPaymentEvent();

        Product afterProduct1 = jpaProductRepository.findById(1L).orElseThrow().toDomain();
        Product afterProduct2 = jpaProductRepository.findById(2L).orElseThrow().toDomain();

        perform.andExpect(status().isOk())
                .andExpect(result -> {
                    String responseContent = result.getResponse().getContentAsString();
                    BasicResponse<PaymentConfirmResponse> apiResponse = objectMapper.readValue(
                            responseContent,
                            new TypeReference<>() {
                            }
                    );
                    PaymentConfirmResponse paymentConfirmResponse = apiResponse.getData();
                    assertThat(paymentConfirmResponse.getOrderId())
                            .isEqualTo(TEST_ORDER_ID);
                    assertThat(paymentConfirmResponse.getAmount())
                            .isEqualByComparingTo(BigDecimal.valueOf(TEST_TOTAL_AMOUNT_1 + TEST_TOTAL_AMOUNT_2));
                });

        assertThat(updatedPaymentEvent.getPaymentKey()).isEqualTo(TEST_PAYMENT_KEY);
        assertThat(updatedPaymentEvent.getApprovedAt()).isNotNull();
        assertThat(updatedPaymentEvent.getStatus()).isEqualTo(PaymentEventStatus.DONE);

        assertThat(updatedPaymentEvent.getPaymentOrderList())
                .allMatch(order -> order.getStatus() == PaymentOrderStatus.SUCCESS);

        assertThat(afterProduct1.getStock())
                .isEqualTo(INIT_PRODUCT_1_STOCK - ORDERED_QUANTITY_1);
        assertThat(afterProduct2.getStock())
                .isEqualTo(INIT_PRODUCT_2_STOCK - ORDERED_QUANTITY_2);
    }

    @Test
    @DisplayName("Payment Confirm 요청 중 재고가 부족하면 결제가 실패하고 FAILED / FAIL 상태로 변경되면서 재고는 변하지 않는다.")
    void confirmPayment_Failure_StockNotEnough() throws Exception {
        // given
        final int INIT_PRODUCT_1_STOCK = 1;
        final int INIT_PRODUCT_2_STOCK = 2;
        final int ORDERED_QUANTITY_1 = 1;
        final int ORDERED_QUANTITY_2 = 3;

        jdbcTemplate.update(PAYMENT_EVENT_INSERT_SQL,
                1L, 1L, 2L, "Ogu T 포함 2건", TEST_ORDER_ID, null, PaymentEventStatus.READY.name(), null, null, 0);
        jdbcTemplate.update(PAYMENT_ORDER_INSERT_SQL,
                1L, 1L, TEST_ORDER_ID, 1L, ORDERED_QUANTITY_1, PaymentOrderStatus.NOT_STARTED.name(), TEST_TOTAL_AMOUNT_1);
        jdbcTemplate.update(PAYMENT_ORDER_INSERT_SQL,
                2L, 1L, TEST_ORDER_ID, 2L, ORDERED_QUANTITY_2, PaymentOrderStatus.NOT_STARTED.name(), TEST_TOTAL_AMOUNT_2);
        jdbcTemplate.update(UPDATE_PRODUCT_STOCK_SQL, INIT_PRODUCT_1_STOCK, 1L);
        jdbcTemplate.update(UPDATE_PRODUCT_STOCK_SQL, INIT_PRODUCT_2_STOCK, 2L);

        PaymentConfirmRequest confirmRequest = PaymentConfirmRequest.builder()
                .userId(1L)
                .orderId(TEST_ORDER_ID)
                .amount(BigDecimal.valueOf(TEST_TOTAL_AMOUNT_1 + TEST_TOTAL_AMOUNT_2))
                .paymentKey(TEST_PAYMENT_KEY)
                .build();

        // when
        ResultActions perform = mockMvc.perform(
                post("/api/v1/payments/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(confirmRequest))
        );

        // then
        PaymentEvent updatedPaymentEvent = getPaymentEvent();
        Product afterProduct1 = jpaProductRepository.findById(1L).orElseThrow().toDomain();
        Product afterProduct2 = jpaProductRepository.findById(2L).orElseThrow().toDomain();

        perform.andExpect(status().isBadRequest());

        assertThat(updatedPaymentEvent.getPaymentKey()).isNull();
        assertThat(updatedPaymentEvent.getApprovedAt()).isNull();
        assertThat(updatedPaymentEvent.getStatus()).isEqualTo(PaymentEventStatus.FAILED);

        assertThat(updatedPaymentEvent.getPaymentOrderList())
                .allMatch(order -> order.getStatus() == PaymentOrderStatus.FAIL);

        assertThat(afterProduct1.getStock()).isEqualTo(INIT_PRODUCT_1_STOCK);
        assertThat(afterProduct2.getStock()).isEqualTo(INIT_PRODUCT_2_STOCK);
    }

    @Test
    @DisplayName("Payment Confirm 요청 중 재시도 가능 오류가 발생하면 결제는 실패하고 UNKNOWN / UNKNOWN 상태로 변경되면서 재고는 감소된 상태로 유지된다.")
    void confirmPayment_Failure_RetryableError() throws Exception {
        // given
        final int INIT_PRODUCT_1_STOCK = 1;
        final int INIT_PRODUCT_2_STOCK = 2;
        final int ORDERED_QUANTITY_1 = 1;
        final int ORDERED_QUANTITY_2 = 2;

        jdbcTemplate.update(PAYMENT_EVENT_INSERT_SQL,
                1L, 1L, 2L, "Ogu T 포함 2건", TEST_ORDER_ID, null, PaymentEventStatus.READY.name(), null, null, 0);
        jdbcTemplate.update(PAYMENT_ORDER_INSERT_SQL,
                1L, 1L, TEST_ORDER_ID, 1L, ORDERED_QUANTITY_1, PaymentOrderStatus.NOT_STARTED.name(), TEST_TOTAL_AMOUNT_1);
        jdbcTemplate.update(PAYMENT_ORDER_INSERT_SQL,
                2L, 1L, TEST_ORDER_ID, 2L, ORDERED_QUANTITY_2, PaymentOrderStatus.NOT_STARTED.name(), TEST_TOTAL_AMOUNT_2);
        jdbcTemplate.update(UPDATE_PRODUCT_STOCK_SQL, INIT_PRODUCT_1_STOCK, 1L);
        jdbcTemplate.update(UPDATE_PRODUCT_STOCK_SQL, INIT_PRODUCT_2_STOCK, 2L);

        PaymentConfirmRequest confirmRequest = PaymentConfirmRequest.builder()
                .userId(1L)
                .orderId(TEST_ORDER_ID)
                .amount(BigDecimal.valueOf(TEST_TOTAL_AMOUNT_1 + TEST_TOTAL_AMOUNT_2))
                .paymentKey(TEST_PAYMENT_KEY)
                .build();

        ReflectionTestUtils.invokeMethod(httpOperator, "addErrorInPostRequest",
                TossPaymentErrorCode.PROVIDER_ERROR.name(),
                TossPaymentErrorCode.PROVIDER_ERROR.getDescription()
        );

        // when
        ResultActions perform = mockMvc.perform(
                post("/api/v1/payments/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(confirmRequest))
        );

        // then
        PaymentEvent updatedPaymentEvent = getPaymentEvent();

        perform.andExpect(status().isBadRequest());

        assertThat(updatedPaymentEvent.getPaymentKey()).isEqualTo(TEST_PAYMENT_KEY);
        assertThat(updatedPaymentEvent.getApprovedAt()).isNull();
        assertThat(updatedPaymentEvent.getStatus()).isEqualTo(PaymentEventStatus.UNKNOWN);

        assertThat(updatedPaymentEvent.getPaymentOrderList())
                .allMatch(order -> order.getStatus() == PaymentOrderStatus.UNKNOWN);

        Product afterProduct1 = jpaProductRepository.findById(1L).orElseThrow().toDomain();
        Product afterProduct2 = jpaProductRepository.findById(2L).orElseThrow().toDomain();
        assertThat(afterProduct1.getStock())
                .isEqualTo(INIT_PRODUCT_1_STOCK - ORDERED_QUANTITY_1);
        assertThat(afterProduct2.getStock())
                .isEqualTo(INIT_PRODUCT_2_STOCK - ORDERED_QUANTITY_2);
    }

    @Test
    @DisplayName("Payment Confirm 요청 중 재시도 불가능 오류가 발생하면 결제는 실패하고 FAILED / FAIL 상태로 변경되면서 재고는 다시 복구된다.")
    void confirmPayment_Failure_NonRetryableError() throws Exception {
        // given
        final int INIT_PRODUCT_1_STOCK = 1;
        final int INIT_PRODUCT_2_STOCK = 2;
        final int ORDERED_QUANTITY_1 = 1;
        final int ORDERED_QUANTITY_2 = 2;

        jdbcTemplate.update(PAYMENT_EVENT_INSERT_SQL,
                1L, 1L, 2L, "Ogu T 포함 2건", TEST_ORDER_ID, null, PaymentEventStatus.READY.name(), null, null, 0);
        jdbcTemplate.update(PAYMENT_ORDER_INSERT_SQL,
                1L, 1L, TEST_ORDER_ID, 1L, ORDERED_QUANTITY_1, PaymentOrderStatus.NOT_STARTED.name(), TEST_TOTAL_AMOUNT_1);
        jdbcTemplate.update(PAYMENT_ORDER_INSERT_SQL,
                2L, 1L, TEST_ORDER_ID, 2L, ORDERED_QUANTITY_2, PaymentOrderStatus.NOT_STARTED.name(), TEST_TOTAL_AMOUNT_2);
        jdbcTemplate.update(UPDATE_PRODUCT_STOCK_SQL, INIT_PRODUCT_1_STOCK, 1L);
        jdbcTemplate.update(UPDATE_PRODUCT_STOCK_SQL, INIT_PRODUCT_2_STOCK, 2L);

        PaymentConfirmRequest confirmRequest = PaymentConfirmRequest.builder()
                .userId(1L)
                .orderId(TEST_ORDER_ID)
                .amount(BigDecimal.valueOf(TEST_TOTAL_AMOUNT_1 + TEST_TOTAL_AMOUNT_2))
                .paymentKey(TEST_PAYMENT_KEY)
                .build();

        ReflectionTestUtils.invokeMethod(httpOperator, "addErrorInPostRequest",
                TossPaymentErrorCode.INVALID_STOPPED_CARD.name(),
                TossPaymentErrorCode.INVALID_STOPPED_CARD.getDescription()
        );

        // when
        ResultActions perform = mockMvc.perform(
                post("/api/v1/payments/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(confirmRequest))
        );

        // then
        PaymentEvent updatedPaymentEvent = getPaymentEvent();
        Product afterProduct1 = jpaProductRepository.findById(1L).orElseThrow().toDomain();
        Product afterProduct2 = jpaProductRepository.findById(2L).orElseThrow().toDomain();

        perform.andExpect(status().isBadRequest());

        assertThat(updatedPaymentEvent.getPaymentKey()).isEqualTo(TEST_PAYMENT_KEY);
        assertThat(updatedPaymentEvent.getApprovedAt()).isNull();
        assertThat(updatedPaymentEvent.getStatus()).isEqualTo(PaymentEventStatus.FAILED);

        assertThat(updatedPaymentEvent.getPaymentOrderList())
                .allMatch(order -> order.getStatus() == PaymentOrderStatus.FAIL);

        assertThat(afterProduct1.getStock()).isEqualTo(INIT_PRODUCT_1_STOCK);
        assertThat(afterProduct2.getStock()).isEqualTo(INIT_PRODUCT_2_STOCK);
    }

    @Test
    @DisplayName("Payment Confirm 요청 중 Read Timeout 발생하면 결제는 실패하고 UNKNOWN / UNKNOWN 상태로 변경되면서 재고는 감소된 상태로 유지된다.")
    void confirmPayment_Failure_NetworkReadTimeout() throws Exception {
        // given
        final int INIT_PRODUCT_1_STOCK = 1;
        final int INIT_PRODUCT_2_STOCK = 2;
        final int ORDERED_QUANTITY_1 = 1;
        final int ORDERED_QUANTITY_2 = 2;

        jdbcTemplate.update(PAYMENT_EVENT_INSERT_SQL,
                1L, 1L, 2L, "Ogu T 포함 2건", TEST_ORDER_ID, null, PaymentEventStatus.READY.name(), null, null, 0);
        jdbcTemplate.update(PAYMENT_ORDER_INSERT_SQL,
                1L, 1L, TEST_ORDER_ID, 1L, ORDERED_QUANTITY_1, PaymentOrderStatus.NOT_STARTED.name(), TEST_TOTAL_AMOUNT_1);
        jdbcTemplate.update(PAYMENT_ORDER_INSERT_SQL,
                2L, 1L, TEST_ORDER_ID, 2L, ORDERED_QUANTITY_2, PaymentOrderStatus.NOT_STARTED.name(), TEST_TOTAL_AMOUNT_2);
        jdbcTemplate.update(UPDATE_PRODUCT_STOCK_SQL, INIT_PRODUCT_1_STOCK, 1L);
        jdbcTemplate.update(UPDATE_PRODUCT_STOCK_SQL, INIT_PRODUCT_2_STOCK, 2L);

        PaymentConfirmRequest confirmRequest = PaymentConfirmRequest.builder()
                .userId(1L)
                .orderId(TEST_ORDER_ID)
                .amount(BigDecimal.valueOf(TEST_TOTAL_AMOUNT_1 + TEST_TOTAL_AMOUNT_2))
                .paymentKey(TEST_PAYMENT_KEY)
                .build();

        ReflectionTestUtils.invokeMethod(httpOperator, "setDelayRange",
                readTimeoutMillisLimit + 1000, readTimeoutMillisLimit + 2000
        );

        // when
        ResultActions perform = mockMvc.perform(
                post("/api/v1/payments/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(confirmRequest))
        );

        // then
        PaymentEvent updatedPaymentEvent = getPaymentEvent();

        perform.andExpect(status().isBadRequest());

        assertThat(updatedPaymentEvent.getPaymentKey()).isEqualTo(TEST_PAYMENT_KEY);
        assertThat(updatedPaymentEvent.getApprovedAt()).isNull();
        assertThat(updatedPaymentEvent.getStatus()).isEqualTo(PaymentEventStatus.UNKNOWN);

        assertThat(updatedPaymentEvent.getPaymentOrderList())
                .allMatch(order -> order.getStatus() == PaymentOrderStatus.UNKNOWN);

        Product afterProduct1 = jpaProductRepository.findById(1L).orElseThrow().toDomain();
        Product afterProduct2 = jpaProductRepository.findById(2L).orElseThrow().toDomain();
        assertThat(afterProduct1.getStock())
                .isEqualTo(INIT_PRODUCT_1_STOCK - ORDERED_QUANTITY_1);
        assertThat(afterProduct2.getStock())
                .isEqualTo(INIT_PRODUCT_2_STOCK - ORDERED_QUANTITY_2);
    }

    private PaymentEvent getPaymentEvent() {
        PaymentEvent updatedPaymentEvent = jpaPaymentEventRepository
                .findAll()
                .getFirst()
                .toDomain(new ArrayList<>());
        List<PaymentOrder> paymentOrderList = jpaPaymentOrderRepository.findByPaymentEventId(
                        updatedPaymentEvent.getId())
                .stream()
                .map(PaymentOrderEntity::toDomain)
                .toList();
        updatedPaymentEvent.addPaymentOrderList(paymentOrderList);
        return updatedPaymentEvent;
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        public HttpOperator httpOperator() {
            return new FakeTossHttpOperator();
        }
    }
}
