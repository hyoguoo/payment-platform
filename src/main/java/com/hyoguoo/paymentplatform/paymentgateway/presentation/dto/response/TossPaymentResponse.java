package com.hyoguoo.paymentplatform.paymentgateway.presentation.dto.response;

import com.hyoguoo.paymentplatform.paymentgateway.domain.enums.PaymentConfirmResult;
import com.hyoguoo.paymentplatform.paymentgateway.domain.vo.TossPaymentDetails;
import com.hyoguoo.paymentplatform.paymentgateway.domain.vo.TossPaymentFailure;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TossPaymentResponse {

    private final String paymentKey;
    private final String orderId;
    private final PaymentConfirmResult paymentConfirmResult;
    private final TossPaymentDetails paymentDetails;
    private final TossPaymentFailure paymentFailure;
}
