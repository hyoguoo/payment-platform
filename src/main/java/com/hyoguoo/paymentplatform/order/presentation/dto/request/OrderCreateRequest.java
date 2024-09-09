package com.hyoguoo.paymentplatform.order.presentation.dto.request;

import com.hyoguoo.paymentplatform.order.domain.OrderInfo;
import com.hyoguoo.paymentplatform.order.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.order.domain.dto.UserInfo;
import com.hyoguoo.paymentplatform.order.presentation.dto.vo.OrderProduct;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class OrderCreateRequest {

    private final Long userId;
    private final BigDecimal amount;
    private final OrderProduct orderProduct;

    public OrderInfo toDomain(UserInfo userInfo, ProductInfo productInfo) {
        return OrderInfo.requiredBuilder()
                .userId(userInfo.getId())
                .productId(productInfo.getId())
                .quantity(this.orderProduct.getQuantity())
                .totalAmount(this.amount)
                .requiredBuild();
    }
}
