package com.hyoguoo.paymentplatform.payment.presentation;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hyoguoo.paymentplatform.payment.exception.PaymentStatusException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentValidException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import com.hyoguoo.paymentplatform.payment.presentation.port.AdminPaymentService;
import com.hyoguoo.paymentplatform.payment.presentation.port.PaymentRecoveryAdminService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PaymentAdminController.class)
class PaymentAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminPaymentService adminPaymentService;

    @MockitoBean
    private PaymentRecoveryAdminService paymentRecoveryAdminService;

    @Test
    @DisplayName("resolve-quarantine POST 는 orderId·reason 을 유스케이스 포트로 위임하고 상세 화면으로 리다이렉트한다.")
    void resolveQuarantine_DelegatesToUseCasePort_AndRedirects() throws Exception {
        // given
        willDoNothing().given(paymentRecoveryAdminService).resolveQuarantine("order-1", "vendor confirmed voided");

        // when / then
        mockMvc.perform(post("/admin/payments/events/{eventId}/resolve-quarantine", 1L)
                        .param("orderId", "order-1")
                        .param("reason", "vendor confirmed voided"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/payments/events/1"));

        verify(paymentRecoveryAdminService, times(1)).resolveQuarantine("order-1", "vendor confirmed voided");
    }

    @Test
    @DisplayName("resolve-quarantine POST 는 reason 파라미터가 누락되면 400 을 반환하고 유스케이스를 호출하지 않는다.")
    void resolveQuarantine_MissingReason_Returns400_AndDoesNotInvokeUseCase() throws Exception {
        // when / then
        mockMvc.perform(post("/admin/payments/events/{eventId}/resolve-quarantine", 1L)
                        .param("orderId", "order-1"))
                .andExpect(status().isBadRequest());

        verify(paymentRecoveryAdminService, never()).resolveQuarantine(anyString(), anyString());
    }

    @Test
    @DisplayName("resolve-quarantine POST 는 reason 이 공백이면 유스케이스 거부 사유를 flash 메시지로 담아 상세 화면으로 리다이렉트한다.")
    void resolveQuarantine_BlankReason_RedirectsWithFlashError() throws Exception {
        // given
        willThrow(PaymentValidException.of(PaymentErrorCode.QUARANTINE_RESOLVE_REASON_REQUIRED))
                .given(paymentRecoveryAdminService).resolveQuarantine("order-1", " ");

        // when / then
        mockMvc.perform(post("/admin/payments/events/{eventId}/resolve-quarantine", 1L)
                        .param("orderId", "order-1")
                        .param("reason", " "))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/payments/events/1?error"))
                .andExpect(flash().attribute("errorMessage",
                        PaymentErrorCode.QUARANTINE_RESOLVE_REASON_REQUIRED.getMessage()));

        verify(paymentRecoveryAdminService, times(1)).resolveQuarantine("order-1", " ");
    }

    @Test
    @DisplayName("resolve-quarantine POST 는 CAS 충돌(PaymentStatusException)도 flash 메시지로 담아 상세 화면으로 리다이렉트한다.")
    void resolveQuarantine_CasConflict_RedirectsWithFlashError() throws Exception {
        // given
        willThrow(PaymentStatusException.of(PaymentErrorCode.QUARANTINE_RESOLVE_CONFLICT))
                .given(paymentRecoveryAdminService).resolveQuarantine("order-1", "vendor confirmed voided");

        // when / then
        mockMvc.perform(post("/admin/payments/events/{eventId}/resolve-quarantine", 1L)
                        .param("orderId", "order-1")
                        .param("reason", "vendor confirmed voided"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/payments/events/1?error"))
                .andExpect(flash().attribute("errorMessage",
                        PaymentErrorCode.QUARANTINE_RESOLVE_CONFLICT.getMessage()));

        verify(paymentRecoveryAdminService, times(1)).resolveQuarantine("order-1", "vendor confirmed voided");
    }

    @Test
    @DisplayName("reprocess-dlq POST 는 orderId 를 유스케이스 포트로 위임하고 상세 화면으로 리다이렉트한다.")
    void reprocessDlq_DelegatesToUseCasePort_AndRedirects() throws Exception {
        // given
        willDoNothing().given(paymentRecoveryAdminService).reprocessDlq("order-1");

        // when / then
        mockMvc.perform(post("/admin/payments/events/{eventId}/reprocess-dlq", 1L)
                        .param("orderId", "order-1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/payments/events/1"));

        verify(paymentRecoveryAdminService, times(1)).reprocessDlq("order-1");
    }

    @Test
    @DisplayName("reprocess-dlq POST 는 나이 게이트 초과 시 유스케이스 거부 사유를 flash 메시지로 담아 상세 화면으로 리다이렉트한다.")
    void reprocessDlq_AgeGateExceeded_RedirectsWithFlashError() throws Exception {
        // given
        willThrow(PaymentValidException.of(PaymentErrorCode.DLQ_REPROCESS_AGE_GATE_EXCEEDED))
                .given(paymentRecoveryAdminService).reprocessDlq("order-1");

        // when / then
        mockMvc.perform(post("/admin/payments/events/{eventId}/reprocess-dlq", 1L)
                        .param("orderId", "order-1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/payments/events/1?error"))
                .andExpect(flash().attribute("errorMessage",
                        PaymentErrorCode.DLQ_REPROCESS_AGE_GATE_EXCEEDED.getMessage()));

        verify(paymentRecoveryAdminService, times(1)).reprocessDlq("order-1");
    }
}
