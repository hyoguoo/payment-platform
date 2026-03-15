package com.hyoguoo.paymentplatform.payment.presentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.core.common.service.port.UUIDProvider;
import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.response.PaymentConfirmAsyncResult;
import com.hyoguoo.paymentplatform.payment.application.dto.response.PaymentConfirmAsyncResult.ResponseType;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentLoadUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentOutboxUseCase;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentFoundException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import com.hyoguoo.paymentplatform.payment.presentation.dto.request.PaymentConfirmRequest;
import com.hyoguoo.paymentplatform.payment.presentation.port.PaymentCheckoutService;
import com.hyoguoo.paymentplatform.payment.presentation.port.PaymentConfirmService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Optional;
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
    private PaymentLoadUseCase paymentLoadUseCase;

    @MockBean
    private PaymentOutboxUseCase paymentOutboxUseCase;

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

    @Test
    @DisplayName("DONE 상태 PaymentEvent 조회 시 200 OK와 status=DONE, approvedAt non-null을 반환한다. (STATUS-01, STATUS-02)")
    void getPaymentStatus_Done_Returns200() throws Exception {
        // given
        LocalDateTime approvedAt = LocalDateTime.of(2024, 1, 1, 12, 0, 0);
        PaymentEvent doneEvent = PaymentEvent.allArgsBuilder()
                .id(1L)
                .buyerId(1L)
                .sellerId(2L)
                .orderName("테스트 주문")
                .orderId("order-done")
                .paymentKey("payment-key-done")
                .status(PaymentEventStatus.DONE)
                .approvedAt(approvedAt)
                .retryCount(0)
                .paymentOrderList(new ArrayList<>())
                .allArgsBuild();

        when(paymentOutboxUseCase.findActiveOutboxStatus("order-done"))
                .thenReturn(Optional.empty());
        when(paymentLoadUseCase.getPaymentEventByOrderId("order-done"))
                .thenReturn(doneEvent);

        // when / then
        mockMvc.perform(get("/api/v1/payments/{orderId}/status", "order-done"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value("order-done"))
                .andExpect(jsonPath("$.data.status").value("DONE"))
                .andExpect(jsonPath("$.data.approvedAt").isNotEmpty());
    }

    @Test
    @DisplayName("READY 상태 PaymentEvent 조회 시 200 OK와 status=PROCESSING, approvedAt=null을 반환한다. (STATUS-03)")
    void getPaymentStatus_Processing_Returns200() throws Exception {
        // given
        PaymentEvent readyEvent = PaymentEvent.allArgsBuilder()
                .id(1L)
                .buyerId(1L)
                .sellerId(2L)
                .orderName("테스트 주문")
                .orderId("order-ready")
                .status(PaymentEventStatus.READY)
                .retryCount(0)
                .paymentOrderList(new ArrayList<>())
                .allArgsBuild();

        when(paymentOutboxUseCase.findActiveOutboxStatus("order-ready"))
                .thenReturn(Optional.empty());
        when(paymentLoadUseCase.getPaymentEventByOrderId("order-ready"))
                .thenReturn(readyEvent);

        // when / then
        mockMvc.perform(get("/api/v1/payments/{orderId}/status", "order-ready"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value("order-ready"))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.approvedAt").doesNotExist());
    }

    @Test
    @DisplayName("존재하지 않는 orderId로 Status 조회 시 404 Not Found를 반환한다. (STATUS-01)")
    void getPaymentStatus_NotFound() throws Exception {
        // given
        when(paymentOutboxUseCase.findActiveOutboxStatus("non-existent"))
                .thenReturn(Optional.empty());
        when(paymentLoadUseCase.getPaymentEventByOrderId("non-existent"))
                .thenThrow(PaymentFoundException.of(PaymentErrorCode.PAYMENT_EVENT_NOT_FOUND));

        // when / then
        mockMvc.perform(get("/api/v1/payments/{orderId}/status", "non-existent"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Outbox에 PENDING 레코드가 있으면 status=PENDING을 반환한다. (OUTBOX-01)")
    void getPaymentStatus_OutboxPending_ReturnsPending() throws Exception {
        // given
        when(paymentOutboxUseCase.findActiveOutboxStatus("order-pending"))
                .thenReturn(Optional.of(PaymentOutboxStatus.PENDING));

        // when / then
        mockMvc.perform(get("/api/v1/payments/{orderId}/status", "order-pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value("order-pending"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.approvedAt").doesNotExist());
    }

    @Test
    @DisplayName("Outbox에 IN_FLIGHT 레코드가 있으면 status=PROCESSING을 반환한다. (OUTBOX-01)")
    void getPaymentStatus_OutboxInFlight_ReturnsProcessing() throws Exception {
        // given
        when(paymentOutboxUseCase.findActiveOutboxStatus("order-in-flight"))
                .thenReturn(Optional.of(PaymentOutboxStatus.IN_FLIGHT));

        // when / then
        mockMvc.perform(get("/api/v1/payments/{orderId}/status", "order-in-flight"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value("order-in-flight"))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.approvedAt").doesNotExist());
    }
}
