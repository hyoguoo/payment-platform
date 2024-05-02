package study.paymentintegrationserver.dto.order;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class OrderCancelRequest {

    @NotNull
    private final String orderId;

    @NotNull
    private final String cancelReason;
}
