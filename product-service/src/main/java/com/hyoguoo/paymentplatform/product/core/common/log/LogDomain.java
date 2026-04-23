package com.hyoguoo.paymentplatform.product.core.common.log;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum LogDomain {
    GLOBAL,
    PRODUCT,
    STOCK
}
