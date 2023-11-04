package study.paymentintegrationserver.dto.order;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import study.paymentintegrationserver.entity.OrderInfo;
import study.paymentintegrationserver.entity.Product;
import study.paymentintegrationserver.entity.User;

import java.math.BigDecimal;

@Getter
@RequiredArgsConstructor
public class OrderCreateRequest {

    private static final String ORDER_CREATE_STATUS = "READY";

    private final Long userId;
    private final BigDecimal amount;
    private final OrderProduct orderProduct;

    public OrderInfo toEntity(User user, Product product, String orderId) {
        return OrderInfo.builder()
                .user(user)
                .product(product)
                .orderId(orderId)
                .quantity(this.orderProduct.getQuantity())
                .totalAmount(this.amount)
                .status(ORDER_CREATE_STATUS)
                .build(amount, this.orderProduct.getQuantity());
    }
}
