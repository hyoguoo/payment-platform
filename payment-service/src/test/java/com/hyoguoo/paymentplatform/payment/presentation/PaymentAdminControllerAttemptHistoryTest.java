package com.hyoguoo.paymentplatform.payment.presentation;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hyoguoo.paymentplatform.payment.application.dto.admin.PaymentEventResult;
import com.hyoguoo.paymentplatform.payment.application.dto.admin.PgAttemptEntryInfo;
import com.hyoguoo.paymentplatform.payment.application.dto.admin.PgAttemptHistoryInfo;
import com.hyoguoo.paymentplatform.payment.application.port.out.PgAttemptHistoryPort;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentGatewayType;
import com.hyoguoo.paymentplatform.payment.exception.PgAttemptHistoryServiceRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import com.hyoguoo.paymentplatform.payment.presentation.port.AdminPaymentService;
import com.hyoguoo.paymentplatform.payment.presentation.port.PaymentRecoveryAdminService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PaymentAdminController.class)
class PaymentAdminControllerAttemptHistoryTest {

    private static final Long EVENT_ID = 1L;
    private static final String ORDER_ID = "order-1";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminPaymentService adminPaymentService;

    @MockitoBean
    private PaymentRecoveryAdminService paymentRecoveryAdminService;

    @MockitoBean
    private PgAttemptHistoryPort pgAttemptHistoryPort;

    @Test
    @DisplayName("상세 조회는 pg 이력 조회가 성공하면 모델에 시도 이력을 담는다.")
    void 상세_이력_정상_조회시_모델에_이력_담김() throws Exception {
        // given
        givenEventDetail();
        given(pgAttemptHistoryPort.getAttemptHistory(ORDER_ID)).willReturn(foundHistory());

        // when / then
        mockMvc.perform(get("/admin/payments/events/{eventId}", EVENT_ID))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("attemptHistory"))
                .andExpect(model().attribute("attemptHistoryUnavailable", false));
    }

    @Test
    @DisplayName("pg 이력 조회가 예외를 던져도 상세 화면(주문·상태 변경 이력)은 그대로 렌더된다.")
    void 상세_pg_조회_실패시_상세_렌더_유지() throws Exception {
        // given
        givenEventDetail();
        willThrow(new IllegalStateException("unexpected pg response"))
                .given(pgAttemptHistoryPort).getAttemptHistory(ORDER_ID);

        // when / then
        mockMvc.perform(get("/admin/payments/events/{eventId}", EVENT_ID))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("event"))
                .andExpect(model().attributeExists("orders"))
                .andExpect(model().attributeExists("histories"));
    }

    @Test
    @DisplayName("pg 이력 조회가 예외를 던지면 이력 조회 불가 상태가 모델에 담긴다.")
    void 상세_pg_조회_실패시_이력_조회불가_표시() throws Exception {
        // given
        givenEventDetail();
        willThrow(new IllegalStateException("unexpected pg response"))
                .given(pgAttemptHistoryPort).getAttemptHistory(ORDER_ID);

        // when / then
        mockMvc.perform(get("/admin/payments/events/{eventId}", EVENT_ID))
                .andExpect(status().isOk())
                .andExpect(model().attribute("attemptHistoryUnavailable", true))
                .andExpect(model().attributeDoesNotExist("attemptHistory"));
    }

    @Test
    @DisplayName("pg 이력 조회가 재시도 가능(타임아웃) 예외를 던져도 상세 화면은 그대로 렌더되고 조회 불가로 표시된다.")
    void 상세_pg_타임아웃시도_상세_렌더_유지() throws Exception {
        // given
        givenEventDetail();
        willThrow(PgAttemptHistoryServiceRetryableException.of(PaymentErrorCode.PG_SERVICE_UNAVAILABLE))
                .given(pgAttemptHistoryPort).getAttemptHistory(ORDER_ID);

        // when / then
        mockMvc.perform(get("/admin/payments/events/{eventId}", EVENT_ID))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("event"))
                .andExpect(model().attribute("attemptHistoryUnavailable", true))
                .andExpect(model().attributeDoesNotExist("attemptHistory"));
    }

    @Test
    @DisplayName("이력 없음(pg 가 주문을 모름)과 조회 불가(pg 장애)는 모델에서 서로 다르게 표현된다.")
    void 상세_이력없음과_조회불가_구분() throws Exception {
        // given: 이력 없음 — 정상 응답, found=false
        givenEventDetail();
        given(pgAttemptHistoryPort.getAttemptHistory(ORDER_ID))
                .willReturn(PgAttemptHistoryInfo.builder()
                        .orderId(ORDER_ID)
                        .found(false)
                        .finalStatus(null)
                        .finalizedAt(null)
                        .reasonCode(null)
                        .attempts(List.of())
                        .build());

        // when / then: 이력 없음은 조회 불가가 아니다 — attemptHistory 는 존재하고 found=false
        mockMvc.perform(get("/admin/payments/events/{eventId}", EVENT_ID))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("attemptHistory"))
                .andExpect(model().attribute("attemptHistoryUnavailable", false));
    }

    private void givenEventDetail() {
        PaymentEventResult eventResult = PaymentEventResult.builder()
                .id(EVENT_ID)
                .orderId(ORDER_ID)
                .orderName("test order")
                .status(PaymentEventStatus.DONE)
                .gatewayType(PaymentGatewayType.TOSS)
                .totalAmount(BigDecimal.valueOf(10_000))
                .createdAt(Instant.parse("2026-07-27T09:00:00Z"))
                .executedAt(Instant.parse("2026-07-27T09:00:05Z"))
                .approvedAt(Instant.parse("2026-07-27T09:00:10Z"))
                .build();

        given(adminPaymentService.getPaymentEventDetail(EVENT_ID)).willReturn(eventResult);
        given(adminPaymentService.getPaymentOrdersByEventId(EVENT_ID)).willReturn(List.of());
        given(adminPaymentService.getPaymentHistoriesByEventId(EVENT_ID)).willReturn(List.of());
    }

    private PgAttemptHistoryInfo foundHistory() {
        return PgAttemptHistoryInfo.builder()
                .orderId(ORDER_ID)
                .found(true)
                .finalStatus("DONE")
                .finalizedAt(Instant.parse("2026-07-27T09:00:10Z"))
                .reasonCode(null)
                .attempts(List.of(
                        PgAttemptEntryInfo.builder()
                                .attemptNo(Optional.of(1))
                                .reservedAt(Instant.parse("2026-07-27T09:00:00Z"))
                                .scheduledAt(Instant.parse("2026-07-27T09:00:00Z"))
                                .publishedAt(Instant.parse("2026-07-27T09:00:01Z"))
                                .exhausted(false)
                                .normalAttempt(true)
                                .build(),
                        PgAttemptEntryInfo.builder()
                                .attemptNo(Optional.empty())
                                .reservedAt(Instant.parse("2026-07-27T09:00:02Z"))
                                .scheduledAt(Instant.parse("2026-07-27T09:00:20Z"))
                                .publishedAt(null)
                                .exhausted(false)
                                .normalAttempt(false)
                                .build()
                ))
                .build();
    }
}
