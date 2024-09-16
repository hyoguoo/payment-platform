package com.hyoguoo.paymentplatform.payment.application.dto.request;

import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.UserInfo;
import com.hyoguoo.paymentplatform.payment.application.dto.vo.OrderProduct;
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

    public PaymentOrder toDomain(UserInfo userInfo, ProductInfo productInfo) {
        return PaymentOrder.requiredBuilder()
                .userInfo(userInfo)
                .productInfo(productInfo)
                .quantity(this.orderProduct.getQuantity())
                .totalAmount(this.amount)
                .requiredBuild();
    }
}
