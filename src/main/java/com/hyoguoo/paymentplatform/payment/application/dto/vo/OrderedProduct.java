package com.hyoguoo.paymentplatform.payment.application.dto.vo;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class OrderedProduct {

    private final Long productId;
    private final Integer quantity;
}
