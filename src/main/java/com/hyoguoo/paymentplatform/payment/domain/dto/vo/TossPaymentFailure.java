package com.hyoguoo.paymentplatform.payment.domain.dto.vo;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TossPaymentFailure {

    private final String errorCode;
    private final String errorMessage;
}
