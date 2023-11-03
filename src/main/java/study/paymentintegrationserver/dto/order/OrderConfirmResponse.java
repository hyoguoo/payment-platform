package study.paymentintegrationserver.dto.order;

import lombok.Getter;
import study.paymentintegrationserver.entity.OrderInfo;

import java.math.BigDecimal;

@Getter
public class OrderConfirmResponse {

    private final String orderId;
    private final BigDecimal amount;

    public OrderConfirmResponse(OrderInfo orderInfo) {
        this.orderId = orderInfo.getOrderId();
        this.amount = orderInfo.getTotalAmount();
    }
}
