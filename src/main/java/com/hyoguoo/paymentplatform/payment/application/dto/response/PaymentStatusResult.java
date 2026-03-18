package com.hyoguoo.paymentplatform.payment.application.dto.response;

import java.time.LocalDateTime;
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
    private final LocalDateTime approvedAt;
}
