package study.paymentintegrationserver.exception;

import lombok.Getter;

@Getter
public enum OrderInfoErrorMessage {

    NOT_FOUND("Not exist order id"),
    INVALID_TOTAL_AMOUNT("Invalid total amount"),
    INVALID_ORDER_ID("Invalid order id"),
    INVALID_USER_ID("Invalid user id"),
    INVALID_PAYMENT_KEY("Invalid payment key");

    private final String message;

    OrderInfoErrorMessage(String message) {
        this.message = message;
    }
}
