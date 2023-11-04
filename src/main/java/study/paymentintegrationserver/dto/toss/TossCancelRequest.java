package study.paymentintegrationserver.dto.toss;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import study.paymentintegrationserver.dto.order.OrderCancelRequest;

@Getter
@RequiredArgsConstructor
public class TossCancelRequest {

    @NotNull(message = "cancelReason must not be null")
    private final String cancelReason;

    public static TossCancelRequest createByOrderCancelRequest(OrderCancelRequest orderCancelRequest) {
        return new TossCancelRequest(
                orderCancelRequest.getCancelReason()
        );
    }
}
