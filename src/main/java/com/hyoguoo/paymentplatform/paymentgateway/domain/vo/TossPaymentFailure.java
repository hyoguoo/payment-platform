package com.hyoguoo.paymentplatform.paymentgateway.domain.vo;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TossPaymentFailure {

    private final String errorCode;
    private final String errorMessage;
}
