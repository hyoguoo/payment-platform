package com.hyoguoo.paymentplatform.order.exception;

import com.hyoguoo.paymentplatform.order.exception.common.OrderErrorCode;
import lombok.Getter;

@Getter
public class OrderStatusException extends RuntimeException {

    private final String code;
    private final String message;

    private OrderStatusException(OrderErrorCode code) {
        this.code = code.getCode();
        this.message = code.getMessage();
    }

    public static OrderStatusException of(OrderErrorCode errorCode) {
        return new OrderStatusException(errorCode);
    }
}
