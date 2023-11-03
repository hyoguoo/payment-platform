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

    private final Long userId;
    private final String orderId;
    private final BigDecimal amount;
    private final OrderProduct orderProduct;

    public OrderInfo toEntity(User user, Product product) {
        return OrderInfo.builder()
                .user(user)
                .product(product)
                .orderId(this.orderId)
                .quantity(this.orderProduct.getQuantity())
                .totalAmount(this.amount)
                .build();
    }
}
