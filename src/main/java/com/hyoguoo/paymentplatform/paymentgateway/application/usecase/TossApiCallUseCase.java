package com.hyoguoo.paymentplatform.paymentgateway.application.usecase;

import com.hyoguoo.paymentplatform.payment.application.metrics.annotation.TossApiMetric;
import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.TossConfirmCommand;
import com.hyoguoo.paymentplatform.paymentgateway.application.port.TossOperator;
import com.hyoguoo.paymentplatform.paymentgateway.domain.TossPaymentInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TossApiCallUseCase {

    private final TossOperator tossOperator;

    @TossApiMetric(TossApiMetric.Type.SUCCESS)
    public TossPaymentInfo executeConfirmPayment(
            TossConfirmCommand tossConfirmCommand,
            String idempotencyKey
    ) throws com.hyoguoo.paymentplatform.paymentgateway.exception.PaymentGatewayApiException {
        return tossOperator.confirmPayment(tossConfirmCommand, idempotencyKey);
    }
}
