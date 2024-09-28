package com.hyoguoo.paymentplatform.payment.presentation;


import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.IntegrationTest;
import com.hyoguoo.paymentplatform.core.response.BasicResponse;
import com.hyoguoo.paymentplatform.mixin.BasicResponseMixin;
import com.hyoguoo.paymentplatform.mixin.CheckoutResponseMixin;
import com.hyoguoo.paymentplatform.payment.application.dto.vo.OrderedProduct;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentOrderEntity;
import com.hyoguoo.paymentplatform.payment.infrastructure.repostitory.JpaPaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.infrastructure.repostitory.JpaPaymentOrderRepository;
import com.hyoguoo.paymentplatform.payment.presentation.dto.request.CheckoutRequest;
import com.hyoguoo.paymentplatform.payment.presentation.dto.response.CheckoutResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

class PaymentCheckoutControllerTest extends IntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JpaPaymentEventRepository jpaPaymentEventRepository;
    @Autowired
    private JpaPaymentOrderRepository jpaPaymentOrderRepository;

    @BeforeEach
    void setUp() {
        objectMapper.addMixIn(CheckoutResponse.class, CheckoutResponseMixin.class);
        objectMapper.addMixIn(BasicResponse.class, BasicResponseMixin.class);
        jpaPaymentEventRepository.deleteAll();
        jpaPaymentOrderRepository.deleteAll();
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
        PaymentEvent paymentEvent = jpaPaymentEventRepository
                .findAll()
                .getFirst()
                .toDomain(new ArrayList<>());
        List<PaymentOrder> paymentOrderList = jpaPaymentOrderRepository.findByPaymentEventId(paymentEvent.getId())
                .stream()
                .map(PaymentOrderEntity::toDomain)
                .toList();
        paymentEvent.addPaymentOrderList(paymentOrderList);

        PaymentEventStatus status = paymentEvent.getStatus();
        BigDecimal totalAmount = paymentOrderList.stream()
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

                    assertThat(checkoutResponse.getOrderId()).isEqualTo(paymentEvent.getOrderId());
                    assertThat(checkoutResponse.getTotalAmount()).isEqualTo(totalAmount);
                });

        assertThat(status).isEqualTo(PaymentEventStatus.READY);
        assertThat(paymentOrderList)
                .allMatch(paymentOrder -> paymentOrder.getStatus() == PaymentOrderStatus.NOT_STARTED);
    }
}
