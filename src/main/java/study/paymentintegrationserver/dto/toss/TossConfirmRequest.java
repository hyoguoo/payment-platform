package study.paymentintegrationserver.dto.toss;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import study.paymentintegrationserver.dto.order.OrderConfirmRequest;

import java.math.BigDecimal;

@Getter
@RequiredArgsConstructor
public class TossConfirmRequest {

    @NotNull(message = "orderId must not be null")
    private final String orderId;
    @NotNull(message = "amount must not be null")
    @DecimalMin(value = "0", message = "amount must be positive")
    private final BigDecimal amount;
    @NotNull(message = "paymentKey must not be null")
    private final String paymentKey;

    public static TossConfirmRequest createByOrderConfirmRequest(OrderConfirmRequest orderConfirmRequest) {
        return new TossConfirmRequest(
                orderConfirmRequest.getOrderId(),
                orderConfirmRequest.getAmount(),
                orderConfirmRequest.getPaymentKey()
        );
    }
}
