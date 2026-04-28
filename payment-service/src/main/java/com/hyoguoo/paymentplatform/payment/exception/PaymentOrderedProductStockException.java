package com.hyoguoo.paymentplatform.payment.exception;

import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import lombok.Getter;

@Getter
public class PaymentOrderedProductStockException extends Exception {

    private final String code;
    private final String message;

    private PaymentOrderedProductStockException(PaymentErrorCode code) {
        this.code = code.getCode();
        this.message = code.getMessage();
    }

    public static PaymentOrderedProductStockException of(PaymentErrorCode errorCode) {
        return new PaymentOrderedProductStockException(errorCode);
    }
}
