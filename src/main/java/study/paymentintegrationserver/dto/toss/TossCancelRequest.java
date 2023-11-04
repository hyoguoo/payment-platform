package study.paymentintegrationserver.dto.toss;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import study.paymentintegrationserver.dto.order.OrderCancelRequest;

@Getter
@RequiredArgsConstructor
public class TossCancelRequest {

    private final String cancelReason;

    public static TossCancelRequest createByOrderCancelRequest(OrderCancelRequest orderCancelRequest) {
        return new TossCancelRequest(
                orderCancelRequest.getCancelReason()
        );
    }
}
