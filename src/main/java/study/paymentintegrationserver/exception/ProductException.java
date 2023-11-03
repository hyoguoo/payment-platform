package study.paymentintegrationserver.exception;

public class ProductException extends RuntimeException {

    private ProductException(ProductErrorMessage productErrorMessage) {
        super(productErrorMessage.getMessage());
    }

    public static ProductException of(ProductErrorMessage productErrorMessage) {
        return new ProductException(productErrorMessage);
    }
}
