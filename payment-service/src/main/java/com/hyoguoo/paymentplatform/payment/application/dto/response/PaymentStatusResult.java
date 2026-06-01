package com.hyoguoo.paymentplatform.payment.application.dto.response;

import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PaymentStatusResult {

    public enum StatusType {
        PENDING,
        PROCESSING,
        DONE,
        FAILED
    }

    private final String orderId;
    private final StatusType status;
    // TODO T3: approvedAt → Instant (D1/D3). PaymentEvent.approvedAt 전환에 따라 동반 전환.
    private final Instant approvedAt;
}
