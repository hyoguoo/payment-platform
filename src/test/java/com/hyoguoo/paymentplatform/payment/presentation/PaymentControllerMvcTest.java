package com.hyoguoo.paymentplatform.payment.presentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.core.common.service.port.UUIDProvider;
import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.response.PaymentConfirmAsyncResult;
import com.hyoguoo.paymentplatform.payment.application.dto.response.PaymentConfirmAsyncResult.ResponseType;
import com.hyoguoo.paymentplatform.payment.presentation.dto.request.PaymentConfirmRequest;
import com.hyoguoo.paymentplatform.payment.presentation.port.PaymentCheckoutService;
import com.hyoguoo.paymentplatform.payment.presentation.port.PaymentConfirmService;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PaymentController.class)
class PaymentControllerMvcTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PaymentConfirmService paymentConfirmService;

    @MockBean
    private PaymentCheckoutService paymentCheckoutService;

    @MockBean
    private UUIDProvider uuidProvider;

    @Test
    @DisplayName("ResponseType.SYNC_200 일 때 confirm()은 HTTP 200을 반환한다. (PORT-02)")
    void confirmPayment_SyncAdapter_Returns200() throws Exception {
        // given
        PaymentConfirmRequest confirmRequest = PaymentConfirmRequest.builder()
                .userId(1L)
                .orderId("order-1")
                .amount(BigDecimal.valueOf(1000))
                .paymentKey("payment-key-1")
                .build();

        when(paymentConfirmService.confirm(any(PaymentConfirmCommand.class)))
                .thenReturn(PaymentConfirmAsyncResult.builder()
                        .responseType(ResponseType.SYNC_200)
                        .orderId("order-1")
                        .amount(BigDecimal.valueOf(1000))
                        .build());

        // when / then
        mockMvc.perform(post("/api/v1/payments/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(confirmRequest)))
                .andExpect(status().isOk());
    }
}
