package study.paymentintegrationserver.dto.order;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class OrderProduct {

    @NotNull
    private final Long productId;

    @NotNull
    private final Integer quantity;
}
