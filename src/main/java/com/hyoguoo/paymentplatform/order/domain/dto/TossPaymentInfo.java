package com.hyoguoo.paymentplatform.order.domain.dto;

import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import study.paymentintegrationserver.dto.toss.TossPaymentResponse;

@Getter
@Builder
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class TossPaymentInfo {

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

    public static TossPaymentInfo of(TossPaymentResponse tossPaymentResponse) {
        return TossPaymentInfo.builder()
                .paymentKey(tossPaymentResponse.getPaymentKey())
                .orderName(tossPaymentResponse.getOrderName())
                .method(tossPaymentResponse.getMethod())
                .totalAmount(tossPaymentResponse.getTotalAmount())
                .status(tossPaymentResponse.getStatus())
                .requestedAt(tossPaymentResponse.getRequestedAt())
                .approvedAt(tossPaymentResponse.getApprovedAt())
                .lastTransactionKey(tossPaymentResponse.getLastTransactionKey())
                .build();
    }
}
