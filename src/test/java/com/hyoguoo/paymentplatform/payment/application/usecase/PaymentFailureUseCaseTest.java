package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PaymentFailureUseCaseTest {

    private PaymentFailureUseCase paymentFailureUseCase;
    private PaymentCommandUseCase mockPaymentCommandUseCase;

    @BeforeEach
    void setUp() {
        mockPaymentCommandUseCase = Mockito.mock(PaymentCommandUseCase.class);
        paymentFailureUseCase = new PaymentFailureUseCase(mockPaymentCommandUseCase);
    }

    @Test
    @DisplayName("handleStockFailure 호출 시 markPaymentAsFail을 1회 호출하고 실패된 PaymentEvent를 반환한다")
    void handleStockFailure_CallsMarkPaymentAsFail_AndReturnsFailedEvent() {
        // given
        String failureMessage = "재고 부족";
        PaymentEvent paymentEvent = PaymentEvent.allArgsBuilder()
                .id(1L)
                .buyerId(1L)
                .orderId("order-123")
                .status(PaymentEventStatus.IN_PROGRESS)
                .paymentOrderList(java.util.Collections.emptyList())
                .allArgsBuild();

        PaymentEvent failedEvent = PaymentEvent.allArgsBuilder()
                .id(1L)
                .buyerId(1L)
                .orderId("order-123")
                .status(PaymentEventStatus.FAILED)
                .paymentOrderList(java.util.Collections.emptyList())
                .allArgsBuild();

        given(mockPaymentCommandUseCase.markPaymentAsFail(paymentEvent, failureMessage))
                .willReturn(failedEvent);

        // when
        PaymentEvent result = paymentFailureUseCase.handleStockFailure(paymentEvent, failureMessage);

        // then
        then(mockPaymentCommandUseCase).should(times(1))
                .markPaymentAsFail(paymentEvent, failureMessage);
        assertThat(result.getStatus()).isEqualTo(PaymentEventStatus.FAILED);
    }
}
