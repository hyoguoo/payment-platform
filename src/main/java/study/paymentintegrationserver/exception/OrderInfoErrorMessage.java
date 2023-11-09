package study.paymentintegrationserver.exception;

import lombok.Getter;

@Getter
public enum OrderInfoErrorMessage {

    NOT_FOUND("Not exist order id"),
    INVALID_TOTAL_AMOUNT("Invalid total amount"),
    INVALID_ORDER_ID("Invalid order id"),
    INVALID_USER_ID("Invalid user id"),
    INVALID_PAYMENT_KEY("Invalid payment key"),
    NOT_CANCELED_PAYMENT("Not canceled payment"),
    NOT_DONE_PAYMENT("Not done payment"),
    NOT_IN_PROGRESS_ORDER("Not in progress order");

    private final String message;

    OrderInfoErrorMessage(String message) {
        this.message = message;
    }
}
