package com.hyoguoo.paymentplatform.order.application.dto.request;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@Builder
@RequiredArgsConstructor
public class TossConfirmInfo {

    private final String orderId;
    private final BigDecimal amount;
    private final String paymentKey;
    private final String idempotencyKey;
}