package com.hyoguoo.paymentplatform.product.presentation.dto;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ProductInfoResponse {

    private final Long id;
    private final String name;
    private final BigDecimal price;
    private final Integer stock;
    private final Long sellerId;
}
