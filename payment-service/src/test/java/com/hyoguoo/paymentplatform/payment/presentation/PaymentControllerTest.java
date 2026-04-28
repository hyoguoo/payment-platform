package com.hyoguoo.paymentplatform.payment.presentation;


import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hyoguoo.paymentplatform.payment.core.test.BaseIntegrationTest;
import com.hyoguoo.paymentplatform.payment.core.response.BasicResponse;
import com.hyoguoo.paymentplatform.mixin.BasicResponseMixin;
import com.hyoguoo.paymentplatform.mixin.CheckoutResponseMixin;
import com.hyoguoo.paymentplatform.mixin.PaymentConfirmResponseMixin;
import com.hyoguoo.paymentplatform.mixin.PaymentStatusApiResponseMixin;
import com.hyoguoo.paymentplatform.payment.application.dto.vo.OrderedProduct;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentOrderEntity;
import com.hyoguoo.paymentplatform.payment.infrastructure.repository.JpaPaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.infrastructure.repository.JpaPaymentOrderRepository;
import com.hyoguoo.paymentplatform.payment.presentation.dto.request.CheckoutRequest;
import com.hyoguoo.paymentplatform.payment.presentation.dto.response.CheckoutResponse;
import com.hyoguoo.paymentplatform.payment.presentation.dto.response.PaymentConfirmResponse;
import com.hyoguoo.paymentplatform.payment.presentation.dto.response.PaymentStatusApiResponse;
import com.hyoguoo.paymentplatform.payment.presentation.dto.response.PaymentStatusResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

class PaymentControllerTest extends BaseIntegrationTest {

    private static final String PAYMENT_EVENT_INSERT_SQL = """
            INSERT INTO payment_event
                (id, buyer_id, seller_id, order_name, order_id, payment_key, status, approved_at, executed_at, retry_count, created_at, updated_at)
            VALUES
                (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
            """;
    private static final String TEST_ORDER_ID = "55996af6-e5b5-47e5-ac3c-44508ee6fd6b";
    private static final String TEST_PAYMENT_KEY = "tviva20240929050058zeWv3";

    private final ObjectMapper objectMapper = new ObjectMapper();
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JpaPaymentEventRepository jpaPaymentEventRepository;
    @Autowired
    private JpaPaymentOrderRepository jpaPaymentOrderRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.addMixIn(CheckoutResponse.class, CheckoutResponseMixin.class);
        objectMapper.addMixIn(PaymentConfirmResponse.class, PaymentConfirmResponseMixin.class);
        objectMapper.addMixIn(BasicResponse.class, BasicResponseMixin.class);
        objectMapper.addMixIn(PaymentStatusApiResponse.class, PaymentStatusApiResponseMixin.class);
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

        perform.andExpect(status().isCreated())
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
    @DisplayName("DONE 상태의 PaymentEvent를 조회하면 200 OK와 함께 orderId, status=DONE, approvedAt이 반환된다. (STATUS-01, STATUS-02)")
    void getPaymentStatus_Done_Success() throws Exception {
        // given
        jdbcTemplate.update(PAYMENT_EVENT_INSERT_SQL,
                1L, 1L, 2L, "테스트 주문", TEST_ORDER_ID, TEST_PAYMENT_KEY,
                PaymentEventStatus.DONE.name(), "2024-01-01 12:00:00", "2024-01-01 12:00:00", 0);

        // when
        ResultActions perform = mockMvc.perform(
                get("/api/v1/payments/{orderId}/status", TEST_ORDER_ID)
        );

        // then
        perform.andExpect(status().isOk())
                .andExpect(result -> {
                    String responseContent = result.getResponse().getContentAsString();
                    BasicResponse<PaymentStatusApiResponse> apiResponse = objectMapper.readValue(
                            responseContent,
                            new TypeReference<>() {
                            }
                    );
                    PaymentStatusApiResponse statusResponse = apiResponse.getData();
                    assertThat(statusResponse.getOrderId()).isEqualTo(TEST_ORDER_ID);
                    assertThat(statusResponse.getStatus()).isEqualTo(PaymentStatusResponse.DONE);
                    assertThat(statusResponse.getApprovedAt()).isNotNull();
                });
    }

    @Test
    @DisplayName("READY 상태의 PaymentEvent를 조회하면 200 OK와 함께 status=PROCESSING, approvedAt=null이 반환된다. (STATUS-03)")
    void getPaymentStatus_Processing_Success() throws Exception {
        // given
        jdbcTemplate.update(PAYMENT_EVENT_INSERT_SQL,
                1L, 1L, 2L, "테스트 주문", TEST_ORDER_ID, null,
                PaymentEventStatus.READY.name(), null, null, 0);

        // when
        ResultActions perform = mockMvc.perform(
                get("/api/v1/payments/{orderId}/status", TEST_ORDER_ID)
        );

        // then
        perform.andExpect(status().isOk())
                .andExpect(result -> {
                    String responseContent = result.getResponse().getContentAsString();
                    BasicResponse<PaymentStatusApiResponse> apiResponse = objectMapper.readValue(
                            responseContent,
                            new TypeReference<>() {
                            }
                    );
                    PaymentStatusApiResponse statusResponse = apiResponse.getData();
                    assertThat(statusResponse.getOrderId()).isEqualTo(TEST_ORDER_ID);
                    assertThat(statusResponse.getStatus()).isEqualTo(PaymentStatusResponse.PROCESSING);
                    assertThat(statusResponse.getApprovedAt()).isNull();
                });
    }

    @Test
    @DisplayName("존재하지 않는 orderId로 Status 조회 시 404 Not Found가 반환된다. (STATUS-01)")
    void getPaymentStatus_NotFound() throws Exception {
        // when
        ResultActions perform = mockMvc.perform(
                get("/api/v1/payments/{orderId}/status", "non-existent-order-id")
        );

        // then
        perform.andExpect(status().isNotFound());
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

}
