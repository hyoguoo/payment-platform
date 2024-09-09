package com.hyoguoo.paymentplatform.order.application.dto.request;

import com.hyoguoo.paymentplatform.order.domain.OrderInfo;
import com.hyoguoo.paymentplatform.order.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.order.domain.dto.UserInfo;
import com.hyoguoo.paymentplatform.order.application.dto.vo.OrderProduct;
import java.math.BigDecimal;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@Builder
@RequiredArgsConstructor
public class OrderCreateInfo {

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
