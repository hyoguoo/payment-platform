package study.paymentintegrationserver.dto.order;

import lombok.Getter;
import study.paymentintegrationserver.entity.OrderInfo;

@Getter
public class OrderCreateResponse {

    private final String orderId;

    public OrderCreateResponse(OrderInfo orderInfo) {
        this.orderId = orderInfo.getOrderId();
    }
}
