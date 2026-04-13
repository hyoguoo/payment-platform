package com.hyoguoo.paymentplatform.payment.application.dto.request;

import com.hyoguoo.paymentplatform.payment.application.dto.vo.OrderedProduct;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentGatewayType;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class CheckoutCommand {

    private final Long userId;
    private final List<OrderedProduct> orderedProductList;
    private final String idempotencyKey;
    private final PaymentGatewayType gatewayType;
}
