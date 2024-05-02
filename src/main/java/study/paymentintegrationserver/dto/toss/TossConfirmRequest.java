package study.paymentintegrationserver.dto.toss;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import study.paymentintegrationserver.dto.order.OrderConfirmRequest;

@Getter
@RequiredArgsConstructor
public class TossConfirmRequest {

    @NotNull
    private final String orderId;

    @NotNull
    private final BigDecimal amount;

    @NotNull
    private final String paymentKey;

    public static TossConfirmRequest createByOrderConfirmRequest(
            OrderConfirmRequest orderConfirmRequest
    ) {
        return new TossConfirmRequest(
                orderConfirmRequest.getOrderId(),
                orderConfirmRequest.getAmount(),
                orderConfirmRequest.getPaymentKey()
        );
    }
}
