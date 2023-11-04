package study.paymentintegrationserver.dto.order;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class OrderCancelRequest {

    @NotNull(message = "orderId must not be null")
    private final String orderId;
    @NotNull(message = "cancelReason must not be null")
    private final String cancelReason;
}
