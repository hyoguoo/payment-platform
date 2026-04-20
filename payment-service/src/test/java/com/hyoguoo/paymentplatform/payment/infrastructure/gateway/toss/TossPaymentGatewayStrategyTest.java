package com.hyoguoo.paymentplatform.payment.infrastructure.gateway.toss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentCancelRequest;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmRequest;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentGatewayType;
import com.hyoguoo.paymentplatform.paymentgateway.domain.enums.PaymentConfirmResultStatus;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.PaymentGatewayInternalReceiver;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.dto.request.TossCancelRequest;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.dto.request.TossConfirmRequest;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.dto.response.TossPaymentResponse;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TossPaymentGatewayStrategyTest {

    @Mock
    private PaymentGatewayInternalReceiver paymentGatewayInternalReceiver;

    @InjectMocks
    private TossPaymentGatewayStrategy strategy;

    @Test
    @DisplayName("confirm 호출 시 idempotencyKey로 orderId를 그대로 전달한다")
    void generateIdempotencyKey_confirm_orderId를_그대로_반환한다() {
        // given
        String orderId = "order-123";
        PaymentConfirmRequest request = new PaymentConfirmRequest(orderId, "payment-key", BigDecimal.TEN, PaymentGatewayType.TOSS);
        TossPaymentResponse mockResponse = TossPaymentResponse.builder()
                .paymentKey("payment-key")
                .orderId(orderId)
                .paymentConfirmResultStatus(PaymentConfirmResultStatus.SUCCESS)
                .build();
        given(paymentGatewayInternalReceiver.confirmPayment(any())).willReturn(mockResponse);
        ArgumentCaptor<TossConfirmRequest> captor = ArgumentCaptor.forClass(TossConfirmRequest.class);

        // when
        strategy.confirm(request);

        // then
        then(paymentGatewayInternalReceiver).should().confirmPayment(captor.capture());
        assertThat(captor.getValue().getIdempotencyKey()).isEqualTo(orderId);
    }

    @Test
    @DisplayName("cancel 호출 시 idempotencyKey로 paymentKey를 그대로 전달한다")
    void generateIdempotencyKey_cancel_paymentKey를_그대로_반환한다() {
        // given
        String paymentKey = "payment-key-abc";
        PaymentCancelRequest request = new PaymentCancelRequest(paymentKey, "order-123", "취소 사유", BigDecimal.TEN, PaymentGatewayType.TOSS);
        TossPaymentResponse mockResponse = TossPaymentResponse.builder()
                .paymentKey(paymentKey)
                .orderId("order-123")
                .paymentConfirmResultStatus(PaymentConfirmResultStatus.SUCCESS)
                .build();
        given(paymentGatewayInternalReceiver.cancelPayment(any())).willReturn(mockResponse);
        ArgumentCaptor<TossCancelRequest> captor = ArgumentCaptor.forClass(TossCancelRequest.class);

        // when
        strategy.cancel(request);

        // then
        then(paymentGatewayInternalReceiver).should().cancelPayment(captor.capture());
        assertThat(captor.getValue().getIdempotencyKey()).isEqualTo(paymentKey);
    }
}
