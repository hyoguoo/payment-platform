package study.paymentintegrationserver.exception;

import lombok.Getter;

@Getter
public enum ProductErrorMessage {

    NOT_FOUND("Not exist product id"),
    NOT_ENOUGH_STOCK("Not enough stock");

    private final String message;

    ProductErrorMessage(String message) {
        this.message = message;
    }
}
