package com.hyoguoo.paymentplatform.order.domain.dto;

import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@Builder
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class ProductInfo {

    private final Long id;
    private final String name;
    private final BigDecimal price;
    private final Integer stock;
}
