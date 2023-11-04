package study.paymentintegrationserver.exception;

public class PaymentException extends RuntimeException {

    private PaymentException(PaymentErrorMessage paymentErrorMessage) {
        super(paymentErrorMessage.getMessage());
    }

    public static PaymentException of(PaymentErrorMessage paymentErrorMessage) {
        return new PaymentException(paymentErrorMessage);
    }
}
