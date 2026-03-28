package com.hyoguoo.paymentplatform.payment.listener;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentCommandUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentLoadUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentOutboxUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentTransactionCoordinator;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentGatewayInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.enums.PaymentConfirmResultStatus;
import com.hyoguoo.paymentplatform.payment.domain.dto.vo.PaymentDetails;
import com.hyoguoo.paymentplatform.payment.domain.dto.enums.TossPaymentStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import com.hyoguoo.paymentplatform.payment.domain.event.PaymentConfirmEvent;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossNonRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentValidException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@DisplayName("OutboxImmediateEventHandler 테스트")
class OutboxImmediateEventHandlerTest {

    private OutboxImmediateEventHandler handler;

    private PaymentOutboxUseCase mockPaymentOutboxUseCase;
    private PaymentLoadUseCase mockPaymentLoadUseCase;
    private PaymentCommandUseCase mockPaymentCommandUseCase;
    private PaymentTransactionCoordinator mockTransactionCoordinator;

    @BeforeEach
    void setUp() {
        mockPaymentOutboxUseCase = Mockito.mock(PaymentOutboxUseCase.class);
        mockPaymentLoadUseCase = Mockito.mock(PaymentLoadUseCase.class);
        mockPaymentCommandUseCase = Mockito.mock(PaymentCommandUseCase.class);
        mockTransactionCoordinator = Mockito.mock(PaymentTransactionCoordinator.class);

        handler = new OutboxImmediateEventHandler(
                mockPaymentOutboxUseCase,
                mockPaymentLoadUseCase,
                mockPaymentCommandUseCase,
                mockTransactionCoordinator
        );
    }

    @Nested
    @DisplayName("handle() 성공 시나리오")
    class SuccessScenario {

        @Test
        @DisplayName("성공 시 claimToInFlight 후 confirmPaymentWithGateway를 호출한다")
        void handle_성공_claimToInFlight_후_confirmPaymentWithGateway_호출한다() throws Exception {
            // given
            String orderId = "order-123";
            PaymentConfirmEvent event = PaymentConfirmEvent.of(orderId);
            PaymentOutbox outbox = createOutbox(orderId);
            PaymentEvent paymentEvent = createPaymentEvent(orderId);

            given(mockPaymentOutboxUseCase.findByOrderId(orderId)).willReturn(Optional.of(outbox));
            given(mockPaymentOutboxUseCase.claimToInFlight(outbox)).willReturn(true);
            given(mockPaymentLoadUseCase.getPaymentEventByOrderId(orderId)).willReturn(paymentEvent);
            given(mockPaymentCommandUseCase.confirmPaymentWithGateway(any())).willReturn(createGatewayInfo());

            // when
            handler.handle(event);

            // then
            then(mockPaymentCommandUseCase).should(times(1)).confirmPaymentWithGateway(any(PaymentConfirmCommand.class));
        }

        @Test
        @DisplayName("성공 시 executePaymentSuccessCompletionWithOutbox를 호출하고 markDone은 호출하지 않는다")
        void handle_성공_executePaymentSuccessCompletionWithOutbox_호출한다() throws Exception {
            // given
            String orderId = "order-123";
            PaymentConfirmEvent event = PaymentConfirmEvent.of(orderId);
            PaymentOutbox outbox = createOutbox(orderId);
            PaymentEvent paymentEvent = createPaymentEvent(orderId);
            PaymentGatewayInfo gatewayInfo = createGatewayInfo();

            given(mockPaymentOutboxUseCase.findByOrderId(orderId)).willReturn(Optional.of(outbox));
            given(mockPaymentOutboxUseCase.claimToInFlight(outbox)).willReturn(true);
            given(mockPaymentLoadUseCase.getPaymentEventByOrderId(orderId)).willReturn(paymentEvent);
            given(mockPaymentCommandUseCase.confirmPaymentWithGateway(any())).willReturn(gatewayInfo);

            // when
            handler.handle(event);

            // then
            then(mockTransactionCoordinator).should(times(1))
                    .executePaymentSuccessCompletionWithOutbox(eq(paymentEvent), any(LocalDateTime.class), eq(outbox));
        }
    }

    @Nested
    @DisplayName("handle() 실패 시나리오")
    class FailureScenario {

        @Test
        @DisplayName("retryable 실패 시 incrementRetryOrFail을 호출한다")
        void handle_retryable_실패_시_incrementRetryOrFail_호출한다() throws Exception {
            // given
            String orderId = "order-123";
            PaymentConfirmEvent event = PaymentConfirmEvent.of(orderId);
            PaymentOutbox outbox = createOutbox(orderId);
            PaymentEvent paymentEvent = createPaymentEvent(orderId);

            given(mockPaymentOutboxUseCase.findByOrderId(orderId)).willReturn(Optional.of(outbox));
            given(mockPaymentOutboxUseCase.claimToInFlight(outbox)).willReturn(true);
            given(mockPaymentLoadUseCase.getPaymentEventByOrderId(orderId)).willReturn(paymentEvent);
            given(mockPaymentCommandUseCase.confirmPaymentWithGateway(any()))
                    .willThrow(PaymentTossRetryableException.of(PaymentErrorCode.TOSS_RETRYABLE_ERROR));

            // when
            handler.handle(event);

            // then
            then(mockPaymentOutboxUseCase).should(times(1)).incrementRetryOrFail(orderId, outbox);
            then(mockTransactionCoordinator).should(times(0)).executePaymentFailureCompensationWithOutbox(any(), any(), any(), any());
        }

        @Test
        @DisplayName("non-retryable 실패 시 executePaymentFailureCompensationWithOutbox를 호출하고 markFailed는 호출하지 않는다")
        void handle_nonRetryable_실패_시_executePaymentFailureCompensationWithOutbox_호출한다() throws Exception {
            // given
            String orderId = "order-123";
            PaymentConfirmEvent event = PaymentConfirmEvent.of(orderId);
            PaymentOutbox outbox = createOutbox(orderId);
            PaymentEvent paymentEvent = createPaymentEvent(orderId);

            given(mockPaymentOutboxUseCase.findByOrderId(orderId)).willReturn(Optional.of(outbox));
            given(mockPaymentOutboxUseCase.claimToInFlight(outbox)).willReturn(true);
            given(mockPaymentLoadUseCase.getPaymentEventByOrderId(orderId)).willReturn(paymentEvent);
            given(mockPaymentCommandUseCase.confirmPaymentWithGateway(any()))
                    .willThrow(PaymentTossNonRetryableException.of(PaymentErrorCode.TOSS_NON_RETRYABLE_ERROR));

            // when
            handler.handle(event);

            // then
            then(mockTransactionCoordinator).should(times(1))
                    .executePaymentFailureCompensationWithOutbox(eq(paymentEvent), anyList(), anyString(), eq(outbox));
        }

        @Test
        @DisplayName("outbox 미존재 시 이후 로직을 처리하지 않는다")
        void handle_outbox_미존재_시_아무것도_처리하지_않는다() throws Exception {
            // given
            String orderId = "order-123";
            PaymentConfirmEvent event = PaymentConfirmEvent.of(orderId);

            given(mockPaymentOutboxUseCase.findByOrderId(orderId)).willReturn(Optional.empty());

            // when
            handler.handle(event);

            // then
            then(mockPaymentOutboxUseCase).should(times(0)).claimToInFlight(any());
            then(mockPaymentLoadUseCase).should(times(0)).getPaymentEventByOrderId(anyString());
        }

        @Test
        @DisplayName("claimToInFlight 실패 시 이후 로직을 건너뛴다")
        void handle_claimToInFlight_실패_시_이후_로직을_건너뛴다() throws Exception {
            // given
            String orderId = "order-123";
            PaymentConfirmEvent event = PaymentConfirmEvent.of(orderId);
            PaymentOutbox outbox = createOutbox(orderId);

            given(mockPaymentOutboxUseCase.findByOrderId(orderId)).willReturn(Optional.of(outbox));
            given(mockPaymentOutboxUseCase.claimToInFlight(outbox)).willReturn(false);

            // when
            handler.handle(event);

            // then
            then(mockPaymentLoadUseCase).should(times(0)).getPaymentEventByOrderId(anyString());
            then(mockPaymentCommandUseCase).should(times(0)).confirmPaymentWithGateway(any());
        }

        @Test
        @DisplayName("paymentEvent 로드 실패 시 incrementRetryOrFail을 호출한다")
        void handle_paymentEvent_로드_실패_시_incrementRetryOrFail_호출한다() throws Exception {
            // given
            String orderId = "order-123";
            PaymentConfirmEvent event = PaymentConfirmEvent.of(orderId);
            PaymentOutbox outbox = createOutbox(orderId);

            given(mockPaymentOutboxUseCase.findByOrderId(orderId)).willReturn(Optional.of(outbox));
            given(mockPaymentOutboxUseCase.claimToInFlight(outbox)).willReturn(true);
            given(mockPaymentLoadUseCase.getPaymentEventByOrderId(orderId))
                    .willThrow(new RuntimeException("DB error"));

            // when
            handler.handle(event);

            // then
            then(mockPaymentOutboxUseCase).should(times(1)).incrementRetryOrFail(orderId, outbox);
            then(mockPaymentCommandUseCase).should(times(0)).confirmPaymentWithGateway(any());
        }
    }

    private PaymentOutbox createOutbox(String orderId) {
        return PaymentOutbox.allArgsBuilder()
                .id(1L)
                .orderId(orderId)
                .status(PaymentOutboxStatus.PENDING)
                .retryCount(0)
                .allArgsBuild();
    }

    private PaymentEvent createPaymentEvent(String orderId) {
        return PaymentEvent.allArgsBuilder()
                .id(1L)
                .orderId(orderId)
                .status(PaymentEventStatus.IN_PROGRESS)
                .paymentOrderList(Collections.emptyList())
                .allArgsBuild();
    }

    private PaymentGatewayInfo createGatewayInfo() {
        return PaymentGatewayInfo.builder()
                .paymentKey("payment-key-123")
                .orderId("order-123")
                .paymentConfirmResultStatus(PaymentConfirmResultStatus.SUCCESS)
                .paymentDetails(PaymentDetails.builder()
                        .totalAmount(BigDecimal.valueOf(15000))
                        .status(TossPaymentStatus.DONE)
                        .approvedAt(LocalDateTime.now())
                        .build())
                .build();
    }
}
