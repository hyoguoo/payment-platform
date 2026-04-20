package com.hyoguoo.paymentplatform.product.application.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ProductStockCommand {

    private final Long productId;
    private final Integer stock;
}
