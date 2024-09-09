package com.hyoguoo.paymentplatform.payment.application.dto.request;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@Builder
@RequiredArgsConstructor
public class TossConfirmRequest {

    private final String orderId;
    private final BigDecimal amount;
    private final String paymentKey;
}
