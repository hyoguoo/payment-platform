package com.hyoguoo.paymentplatform.payment.exception;

import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import lombok.Getter;

@Getter
public class PaymentGatewayStatusUnmappedException extends RuntimeException {

    private final String code;
    private final String message;

    private PaymentGatewayStatusUnmappedException(PaymentErrorCode errorCode, String rawStatus) {
        super(errorCode.getMessage() + " rawStatus=" + rawStatus);
        this.code = errorCode.getCode();
        this.message = errorCode.getMessage();
    }

    public static PaymentGatewayStatusUnmappedException of(String rawStatus) {
        return new PaymentGatewayStatusUnmappedException(
                PaymentErrorCode.UNMAPPED_GATEWAY_STATUS,
                rawStatus
        );
    }
}
