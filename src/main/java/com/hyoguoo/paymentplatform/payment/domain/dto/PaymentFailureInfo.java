package com.hyoguoo.paymentplatform.payment.domain.dto;

public record PaymentFailureInfo(
        String code,
        String message,
        boolean isRetryable
) {

}
