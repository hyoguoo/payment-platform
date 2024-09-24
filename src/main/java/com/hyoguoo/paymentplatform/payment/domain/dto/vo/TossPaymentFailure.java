package com.hyoguoo.paymentplatform.payment.domain.dto.vo;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TossPaymentFailure {

    private final String code;
    private final String message;
}
