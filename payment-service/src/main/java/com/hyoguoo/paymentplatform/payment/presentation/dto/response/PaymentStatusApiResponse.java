package com.hyoguoo.paymentplatform.payment.presentation.dto.response;

import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PaymentStatusApiResponse {

    private final String orderId;
    private final PaymentStatusResponse status;
    // TODO T3: approvedAt → Instant (D1/D3). PaymentStatusResult 전환에 따라 동반 전환.
    private final Instant approvedAt;
}
