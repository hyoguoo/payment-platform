package study.paymentintegrationserver.exception;

import lombok.Getter;

@Getter
public enum PaymentErrorMessage {

    NOT_FOUND("Not exist payment info");

    private final String message;

    PaymentErrorMessage(String message) {
        this.message = message;
    }
}
