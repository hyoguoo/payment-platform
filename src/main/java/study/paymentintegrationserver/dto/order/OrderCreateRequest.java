package study.paymentintegrationserver.dto.order;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
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

    @NotNull(message = "userId must not be null")
    @DecimalMin(value = "0", message = "userId must be positive")
    private final Long userId;
    @NotNull(message = "amount must not be null")
    @DecimalMin(value = "0", message = "amount must be positive")
    private final BigDecimal amount;
    @NotNull(message = "orderProduct must not be null")
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
