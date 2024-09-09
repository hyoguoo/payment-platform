package com.hyoguoo.paymentplatform.order.exception;

import com.hyoguoo.paymentplatform.order.exception.common.OrderErrorCode;
import lombok.Getter;

@Getter
public class OrderValidException extends RuntimeException {

    private final String code;
    private final String message;

    private OrderValidException(OrderErrorCode code) {
        this.code = code.getCode();
        this.message = code.getMessage();
    }

    public static OrderValidException of(OrderErrorCode errorCode) {
        return new OrderValidException(errorCode);
    }
}
