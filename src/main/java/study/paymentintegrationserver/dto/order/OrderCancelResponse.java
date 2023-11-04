package study.paymentintegrationserver.dto.order;

import lombok.Getter;
import study.paymentintegrationserver.entity.OrderInfo;

@Getter
public class OrderCancelResponse {

    private final Long id;

    public OrderCancelResponse(OrderInfo orderInfo) {
        this.id = orderInfo.getId();
    }
}
