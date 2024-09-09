package com.hyoguoo.paymentplatform.product.presentation.dto;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@Builder
@RequiredArgsConstructor
public class ProductInfoClientResponse {

    private final Long id;
    private final String name;
    private final BigDecimal price;
    private final Integer stock;
}
