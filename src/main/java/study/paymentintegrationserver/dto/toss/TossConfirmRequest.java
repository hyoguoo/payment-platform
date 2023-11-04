package study.paymentintegrationserver.dto.toss;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import study.paymentintegrationserver.dto.order.OrderConfirmRequest;

import java.math.BigDecimal;

@Getter
@RequiredArgsConstructor
public class TossConfirmRequest {

    private final String orderId;
    private final BigDecimal amount;
    private final String paymentKey;

    public static TossConfirmRequest createByOrderConfirmRequest(OrderConfirmRequest orderConfirmRequest) {
        return new TossConfirmRequest(
                orderConfirmRequest.getOrderId(),
                orderConfirmRequest.getAmount(),
                orderConfirmRequest.getPaymentKey()
        );
    }
}
