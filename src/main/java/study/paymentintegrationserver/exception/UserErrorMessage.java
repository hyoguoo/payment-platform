package study.paymentintegrationserver.exception;

import lombok.Getter;

@Getter
public enum UserErrorMessage {

    NOT_FOUND("Not exist user id");

    private final String message;

    UserErrorMessage(String message) {
        this.message = message;
    }
}
