package com.hyoguoo.paymentplatform.order.presentation.dto.request;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class OrderConfirmRequest {

    private final Long userId;
    private final String orderId;
    private final BigDecimal amount;
    private final String paymentKey;
}
