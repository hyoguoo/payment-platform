package com.hyoguoo.paymentplatform.payment.domain.dto;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TossPaymentInfo {

    private final String paymentKey;
    private final String orderName;
    private final String method;
    private final double totalAmount;
    private final String status;
    private final LocalDateTime requestedAt;
    private final LocalDateTime approvedAt;
    private final String lastTransactionKey;
}