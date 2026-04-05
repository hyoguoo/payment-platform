package com.hyoguoo.paymentplatform.payment.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentCommandUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentLoadUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentOutboxUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentTransactionCoordinator;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentGatewayInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.vo.PaymentDetails;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossNonRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@DisplayName("OutboxProcessingService 테스트")
class OutboxProcessingServiceTest {

    private static final String ORDER_ID = "order-123";
    private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 3, 15, 12, 0, 0);

    private PaymentOutboxUseCase mockPaymentOutboxUseCase;
    private PaymentLoadUseCase mockPaymentLoadUseCase;
    private PaymentCommandUseCase mockPaymentCommandUseCase;
    private PaymentTransactionCoordinator mockTransactionCoordinator;
    private OutboxProcessingService outboxProcessingService;

    @BeforeEach
    void setUp() {
        mockPaymentOutboxUseCase = Mockito.mock(PaymentOutboxUseCase.class);
        mockPaymentLoadUseCase = Mockito.mock(PaymentLoadUseCase.class);
        mockPaymentCommandUseCase = Mockito.mock(PaymentCommandUseCase.class);
        mockTransactionCoordinator = Mockito.mock(PaymentTransactionCoordinator.class);

        outboxProcessingService = new OutboxProcessingService(
                mockPaymentOutboxUseCase,
                mockPaymentLoadUseCase,
                mockPaymentCommandUseCase,
                mockTransactionCoordinator
        );
    }

    @Test
    @DisplayName("process - 정상 흐름: claimToInFlight → confirmPaymentWithGateway → executePaymentSuccessCompletionWithOutbox 순서로 호출")
    void process_정상흐름_성공_완료까지_호출() throws Exception {
        // given
        PaymentOutbox inFlightOutbox = createInFlightOutbox(ORDER_ID);
        PaymentEvent paymentEvent = createPaymentEvent(ORDER_ID);
        PaymentGatewayInfo gatewayInfo = createGatewayInfo(FIXED_NOW);

        given(mockPaymentOutboxUseCase.claimToInFlight(ORDER_ID)).willReturn(Optional.of(inFlightOutbox));
        given(mockPaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(paymentEvent);
        given(mockPaymentCommandUseCase.confirmPaymentWithGateway(any(PaymentConfirmCommand.class)))
                .willReturn(gatewayInfo);

        // when
        outboxProcessingService.process(ORDER_ID);

        // then
        then(mockPaymentOutboxUseCase).should(times(1)).claimToInFlight(ORDER_ID);
        then(mockPaymentCommandUseCase).should(times(1)).confirmPaymentWithGateway(any(PaymentConfirmCommand.class));
        then(mockTransactionCoordinator).should(times(1))
                .executePaymentSuccessCompletionWithOutbox(any(PaymentEvent.class), any(LocalDateTime.class),
                        any(PaymentOutbox.class));
    }

    @Test
    @DisplayName("process - claimToInFlight empty 반환: Toss API를 호출하지 않는다")
    void process_claimToInFlight_empty_Toss미호출() throws Exception {
        // given
        given(mockPaymentOutboxUseCase.claimToInFlight(ORDER_ID)).willReturn(Optional.empty());

        // when
        outboxProcessingService.process(ORDER_ID);

        // then
        then(mockPaymentCommandUseCase).should(never()).confirmPaymentWithGateway(any());
    }

    @Test
    @DisplayName("process - PaymentTossRetryableException 재시도 가능: incrementRetryOrFail() 호출, 보상 트랜잭션 미호출")
    void process_retryable예외_incrementRetryOrFail_호출() throws Exception {
        // given
        PaymentOutbox inFlightOutbox = createInFlightOutbox(ORDER_ID);
        PaymentEvent paymentEvent = createPaymentEvent(ORDER_ID);

        given(mockPaymentOutboxUseCase.claimToInFlight(ORDER_ID)).willReturn(Optional.of(inFlightOutbox));
        given(mockPaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(paymentEvent);
        given(mockPaymentCommandUseCase.confirmPaymentWithGateway(any(PaymentConfirmCommand.class)))
                .willThrow(PaymentTossRetryableException.of(PaymentErrorCode.TOSS_RETRYABLE_ERROR));
        given(mockPaymentOutboxUseCase.incrementRetryOrFail(ORDER_ID, inFlightOutbox)).willReturn(false);

        // when
        outboxProcessingService.process(ORDER_ID);

        // then
        then(mockPaymentOutboxUseCase).should(times(1)).incrementRetryOrFail(ORDER_ID, inFlightOutbox);
        then(mockTransactionCoordinator).should(never())
                .executePaymentFailureCompensationWithOutbox(any(), any(), any(), any());
    }

    @Test
    @DisplayName("process - PaymentTossRetryableException 소진: executePaymentFailureCompensationWithOutbox() 호출")
    void process_retryable예외_소진_executePaymentFailureCompensationWithOutbox_호출() throws Exception {
        // given
        PaymentOutbox inFlightOutbox = createInFlightOutbox(ORDER_ID);
        PaymentEvent paymentEvent = createPaymentEvent(ORDER_ID);

        given(mockPaymentOutboxUseCase.claimToInFlight(ORDER_ID)).willReturn(Optional.of(inFlightOutbox));
        given(mockPaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(paymentEvent);
        given(mockPaymentCommandUseCase.confirmPaymentWithGateway(any(PaymentConfirmCommand.class)))
                .willThrow(PaymentTossRetryableException.of(PaymentErrorCode.TOSS_RETRYABLE_ERROR));
        given(mockPaymentOutboxUseCase.incrementRetryOrFail(ORDER_ID, inFlightOutbox)).willReturn(true);

        // when
        outboxProcessingService.process(ORDER_ID);

        // then
        then(mockPaymentOutboxUseCase).should(times(1)).incrementRetryOrFail(ORDER_ID, inFlightOutbox);
        then(mockTransactionCoordinator).should(times(1))
                .executePaymentFailureCompensationWithOutbox(any(PaymentEvent.class), any(), anyString(),
                        any(PaymentOutbox.class));
    }

    @Test
    @DisplayName("process - PaymentTossNonRetryableException: executePaymentFailureCompensationWithOutbox() 호출, incrementRetryOrFail 미호출")
    void process_nonRetryable예외_executePaymentFailureCompensationWithOutbox_호출() throws Exception {
        // given
        PaymentOutbox inFlightOutbox = createInFlightOutbox(ORDER_ID);
        PaymentEvent paymentEvent = createPaymentEvent(ORDER_ID);

        given(mockPaymentOutboxUseCase.claimToInFlight(ORDER_ID)).willReturn(Optional.of(inFlightOutbox));
        given(mockPaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(paymentEvent);
        given(mockPaymentCommandUseCase.confirmPaymentWithGateway(any(PaymentConfirmCommand.class)))
                .willThrow(PaymentTossNonRetryableException.of(PaymentErrorCode.TOSS_NON_RETRYABLE_ERROR));

        // when
        outboxProcessingService.process(ORDER_ID);

        // then
        then(mockTransactionCoordinator).should(times(1))
                .executePaymentFailureCompensationWithOutbox(any(PaymentEvent.class), any(), anyString(),
                        any(PaymentOutbox.class));
        then(mockPaymentOutboxUseCase).should(never()).incrementRetryOrFail(any(), any());
    }

    @Test
    @DisplayName("process - getPaymentEventByOrderId() 예외 발생 시 incrementRetryOrFail() 호출")
    void process_paymentEvent_로드실패_incrementRetryOrFail_호출() throws Exception {
        // given
        PaymentOutbox inFlightOutbox = createInFlightOutbox(ORDER_ID);

        given(mockPaymentOutboxUseCase.claimToInFlight(ORDER_ID)).willReturn(Optional.of(inFlightOutbox));
        willThrow(new RuntimeException("event not found"))
                .given(mockPaymentLoadUseCase).getPaymentEventByOrderId(ORDER_ID);

        // when
        outboxProcessingService.process(ORDER_ID);

        // then
        then(mockPaymentOutboxUseCase).should(times(1)).incrementRetryOrFail(ORDER_ID, inFlightOutbox);
        then(mockPaymentCommandUseCase).should(never()).confirmPaymentWithGateway(any());
    }

    private PaymentOutbox createInFlightOutbox(String orderId) {
        return PaymentOutbox.allArgsBuilder()
                .id(1L)
                .orderId(orderId)
                .status(PaymentOutboxStatus.IN_FLIGHT)
                .retryCount(0)
                .allArgsBuild();
    }

    private PaymentEvent createPaymentEvent(String orderId) {
        return PaymentEvent.allArgsBuilder()
                .id(1L)
                .buyerId(1L)
                .orderId(orderId)
                .paymentKey("payment-key-123")
                .status(PaymentEventStatus.IN_PROGRESS)
                .paymentOrderList(Collections.emptyList())
                .allArgsBuild();
    }

    private PaymentGatewayInfo createGatewayInfo(LocalDateTime approvedAt) {
        return PaymentGatewayInfo.builder()
                .paymentKey("payment-key-123")
                .orderId(ORDER_ID)
                .paymentDetails(
                        PaymentDetails.builder()
                                .approvedAt(approvedAt)
                                .totalAmount(BigDecimal.valueOf(10000))
                                .build()
                )
                .build();
    }
}
