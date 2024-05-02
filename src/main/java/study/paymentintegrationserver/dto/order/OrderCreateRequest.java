package study.paymentintegrationserver.dto.order;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import study.paymentintegrationserver.entity.OrderInfo;
import study.paymentintegrationserver.entity.Product;
import study.paymentintegrationserver.entity.User;

@Getter
@RequiredArgsConstructor
public class OrderCreateRequest {

    @NotNull
    private final Long userId;

    @NotNull
    private final BigDecimal amount;

    @NotNull
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
