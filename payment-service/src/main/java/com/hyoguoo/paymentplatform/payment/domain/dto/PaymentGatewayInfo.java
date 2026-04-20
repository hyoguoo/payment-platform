package com.hyoguoo.paymentplatform.payment.domain.dto;

import com.hyoguoo.paymentplatform.payment.domain.dto.enums.PaymentConfirmResultStatus;
import com.hyoguoo.paymentplatform.payment.domain.dto.vo.PaymentDetails;
import com.hyoguoo.paymentplatform.payment.domain.dto.vo.PaymentFailure;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PaymentGatewayInfo {

    private final String paymentKey;
    private final String orderId;
    private final PaymentConfirmResultStatus paymentConfirmResultStatus;
    private final PaymentDetails paymentDetails;
    private final PaymentFailure paymentFailure;
}
