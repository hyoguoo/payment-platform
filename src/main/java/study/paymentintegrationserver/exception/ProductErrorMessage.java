package study.paymentintegrationserver.exception;

import lombok.Getter;

@Getter
public enum ProductErrorMessage {

    NOT_FOUND("Not exist product id"),
    NOT_ENOUGH_STOCK("Not enough stock"),
    NOT_CORRECT_PRODUCT("Not correct product"),
    NOT_NEGATIVE_NUMBER_TO_CALCULATE_STOCK("Not negative number to calculate stock");

    private final String message;

    ProductErrorMessage(String message) {
        this.message = message;
    }
}
