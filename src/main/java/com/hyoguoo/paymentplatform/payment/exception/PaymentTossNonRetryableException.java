package com.hyoguoo.paymentplatform.payment.exception;

import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import lombok.Getter;

@Getter
public class PaymentTossNonRetryableException extends NonRetryableException {

    private PaymentTossNonRetryableException(PaymentErrorCode code) {
        super(code);
    }

    public static PaymentTossNonRetryableException of(PaymentErrorCode errorCode) {
        return new PaymentTossNonRetryableException(errorCode);
    }
}
