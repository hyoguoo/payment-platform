package study.paymentintegrationserver.exception;

import lombok.Getter;

@Getter
public enum OrderInfoErrorMessage {

    NOT_FOUND("Not exist order id");

    private final String message;

    OrderInfoErrorMessage(String message) {
        this.message = message;
    }
}
