package study.paymentintegrationserver.dto.order;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

@Getter
@RequiredArgsConstructor
public class OrderConfirmRequest {

    @NotNull(message = "userId must not be null")
    @DecimalMin(value = "0", message = "userId must be positive")
    private final Long userId;
    @NotNull(message = "orderId must not be null")
    private final String orderId;
    @NotNull(message = "amount must not be null")
    @DecimalMin(value = "0", message = "amount must be positive")
    private final BigDecimal amount;
    @NotNull(message = "paymentKey must not be null")
    private final String paymentKey;
}
