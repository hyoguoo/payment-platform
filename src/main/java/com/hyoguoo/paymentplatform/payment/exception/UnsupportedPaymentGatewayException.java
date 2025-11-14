package com.hyoguoo.paymentplatform.payment.exception;

import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import com.hyoguoo.paymentplatform.payment.infrastructure.gateway.PaymentGatewayType;
import lombok.Getter;

@Getter
public class UnsupportedPaymentGatewayException extends RuntimeException {

    private final String code;
    private final String message;
    private final PaymentGatewayType requestedType;

    private UnsupportedPaymentGatewayException(
            PaymentErrorCode code,
            PaymentGatewayType requestedType
    ) {
        this.code = code.getCode();
        this.message = code.getMessage() + " [요청된 타입: " + requestedType + "]";
        this.requestedType = requestedType;
    }

    public static UnsupportedPaymentGatewayException of(PaymentGatewayType requestedType) {
        return new UnsupportedPaymentGatewayException(
                PaymentErrorCode.UNSUPPORTED_PAYMENT_GATEWAY,
                requestedType
        );
    }
}
