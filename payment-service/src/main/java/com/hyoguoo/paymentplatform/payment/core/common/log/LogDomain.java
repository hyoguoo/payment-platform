package com.hyoguoo.paymentplatform.payment.core.common.log;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum LogDomain {
    GLOBAL,
    PAYMENT,
    PAYMENT_GATEWAY,
    USER,
    PRODUCT
}
