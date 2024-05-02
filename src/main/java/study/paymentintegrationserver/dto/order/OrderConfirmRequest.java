package study.paymentintegrationserver.dto.order;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class OrderConfirmRequest {

    @NotNull
    private final Long userId;

    @NotNull
    private final String orderId;

    @NotNull
    private final BigDecimal amount;

    @NotNull
    private final String paymentKey;
}
