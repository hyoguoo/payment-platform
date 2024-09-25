package com.hyoguoo.paymentplatform.paymentgateway.domain;

import com.hyoguoo.paymentplatform.paymentgateway.domain.enums.PaymentConfirmResultStatus;
import com.hyoguoo.paymentplatform.paymentgateway.domain.vo.TossPaymentDetails;
import com.hyoguoo.paymentplatform.paymentgateway.domain.vo.TossPaymentFailure;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TossPaymentInfo {

    private final String paymentKey;
    private final String orderId;
    private final PaymentConfirmResultStatus paymentConfirmResultStatus;
    private final TossPaymentDetails paymentDetails;
    private final TossPaymentFailure paymentFailure;
}
