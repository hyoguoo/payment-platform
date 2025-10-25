package com.hyoguoo.paymentplatform.paymentgateway.application.usecase;

import com.hyoguoo.paymentplatform.payment.application.metrics.annotation.ErrorCode;
import com.hyoguoo.paymentplatform.payment.application.metrics.annotation.TossApiMetric;
import com.hyoguoo.paymentplatform.paymentgateway.domain.TossPaymentInfo;
import com.hyoguoo.paymentplatform.paymentgateway.domain.enums.PaymentConfirmResultStatus;
import com.hyoguoo.paymentplatform.paymentgateway.domain.vo.TossPaymentFailure;
import com.hyoguoo.paymentplatform.paymentgateway.exception.common.TossPaymentErrorCode;
import org.springframework.stereotype.Component;

@Component
public class TossApiFailureUseCase {

    @TossApiMetric(value = TossApiMetric.Type.RETRYABLE_FAILURE)
    public TossPaymentInfo handleTossApiFailure(@ErrorCode TossPaymentErrorCode tossPaymentErrorCode) {
        PaymentConfirmResultStatus paymentConfirmResultStatus = PaymentConfirmResultStatus.of(
                tossPaymentErrorCode
        );

        TossPaymentFailure paymentFailure = TossPaymentFailure.builder()
                .code(tossPaymentErrorCode.name())
                .message(tossPaymentErrorCode.getDescription())
                .build();

        return TossPaymentInfo.builder()
                .paymentConfirmResultStatus(paymentConfirmResultStatus)
                .paymentFailure(paymentFailure)
                .build();
    }
}
