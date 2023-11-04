package study.paymentintegrationserver.dto.order;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class OrderProduct {

    @NotNull(message = "productId must not be null")
    private final Long productId;
    @NotNull(message = "quantity must not be null")
    @DecimalMin(value = "0", message = "quantity must be positive")
    private final Integer quantity;
}
