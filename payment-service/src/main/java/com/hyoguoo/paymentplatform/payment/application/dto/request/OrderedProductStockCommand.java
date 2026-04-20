package com.hyoguoo.paymentplatform.payment.application.dto.request;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class OrderedProductStockCommand {

    private final Long productId;
    private final Integer stock;
}
