package com.hyoguoo.paymentplatform.payment.domain.dto;

import com.hyoguoo.paymentplatform.payment.domain.dto.enums.PaymentConfirmResult;
import com.hyoguoo.paymentplatform.payment.domain.dto.vo.TossPaymentDetails;
import com.hyoguoo.paymentplatform.payment.domain.dto.vo.TossPaymentFailure;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TossPaymentInfo {

    private final String paymentKey;
    private final String orderId;
    private final PaymentConfirmResult paymentConfirmResult;
    private final TossPaymentDetails paymentDetails;
    private final TossPaymentFailure paymentFailure;
}
