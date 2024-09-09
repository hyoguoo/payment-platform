package com.hyoguoo.paymentplatform.payment.presentation.dto.request;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@Builder
@RequiredArgsConstructor
public class TossConfirmClientRequest {

    private final String orderId;
    private final BigDecimal amount;
    private final String paymentKey;
    private final String idempotencyKey;
}
