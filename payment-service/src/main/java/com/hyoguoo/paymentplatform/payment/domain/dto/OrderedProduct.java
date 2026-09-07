package com.hyoguoo.paymentplatform.payment.domain.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class OrderedProduct {

    private final Long productId;
    private final Integer quantity;
}
