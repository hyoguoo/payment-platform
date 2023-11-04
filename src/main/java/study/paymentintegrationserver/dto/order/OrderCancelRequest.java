package study.paymentintegrationserver.dto.order;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class OrderCancelRequest {

    private final String orderId;
    private final String cancelReason;
}
