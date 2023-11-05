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

    @NotNull(message = "userId must not be null")
    @DecimalMin(value = "0", message = "userId must be positive")
    private final Long userId;
    @NotNull(message = "amount must not be null")
    @DecimalMin(value = "0", message = "amount must be positive")
    private final BigDecimal amount;
    @NotNull(message = "orderProduct must not be null")
    private final OrderProduct orderProduct;

    public OrderInfo toEntity(User user, Product product) {
        return OrderInfo.builder()
                .user(user)
                .product(product)
                .quantity(this.orderProduct.getQuantity())
                .totalAmount(this.amount)
                .build();
    }
}
