package com.hyoguoo.paymentplatform.paymentgateway.application.dto.response;

import java.time.format.DateTimeFormatter;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
@Builder
public class TossPaymentDetails {

    public static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private final String paymentKey;
    private final String orderName;
    private final String method;
    private final double totalAmount;
    private final String status;
    private final String requestedAt;
    private final String approvedAt;
    private final String lastTransactionKey;
}
