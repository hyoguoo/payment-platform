package study.paymentintegrationserver.exception;

public class OrderInfoException extends RuntimeException {

    private OrderInfoException(OrderInfoErrorMessage orderInfoErrorMessage) {
        super(orderInfoErrorMessage.getMessage());
    }

    public static OrderInfoException of(OrderInfoErrorMessage orderInfoErrorMessage) {
        return new OrderInfoException(orderInfoErrorMessage);
    }
}
