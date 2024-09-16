package com.hyoguoo.paymentplatform.paymentgateway.infrastructure;

import com.hyoguoo.paymentplatform.paymentgateway.application.dto.response.TossPaymentDetails;
import com.hyoguoo.paymentplatform.paymentgateway.infrastructure.dto.response.TossPaymentResponse;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentGatewayInfrastructureMapper {

    public static TossPaymentDetails toPaymentDetails(TossPaymentResponse tossPaymentResponse) {
        LocalDateTime requestedAt = tossPaymentResponse.getRequestedAt() == null
                ? null
                : LocalDateTime.parse(
                        tossPaymentResponse.getRequestedAt(),
                        TossPaymentResponse.DATE_TIME_FORMATTER
                );
        LocalDateTime approvedAt = tossPaymentResponse.getApprovedAt() == null
                ? null
                : LocalDateTime.parse(
                        tossPaymentResponse.getApprovedAt(),
                        TossPaymentResponse.DATE_TIME_FORMATTER
                );
        return TossPaymentDetails.builder()
                .paymentKey(tossPaymentResponse.getPaymentKey())
                .orderName(tossPaymentResponse.getOrderName())
                .method(tossPaymentResponse.getMethod())
                .totalAmount(tossPaymentResponse.getTotalAmount())
                .status(tossPaymentResponse.getStatus())
                .requestedAt(requestedAt)
                .approvedAt(approvedAt)
                .lastTransactionKey(tossPaymentResponse.getLastTransactionKey())
                .build();
    }
}
