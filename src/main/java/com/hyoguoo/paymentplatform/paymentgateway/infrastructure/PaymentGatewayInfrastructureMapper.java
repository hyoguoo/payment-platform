package com.hyoguoo.paymentplatform.paymentgateway.infrastructure;

import com.hyoguoo.paymentplatform.paymentgateway.application.dto.response.TossPaymentResult;
import com.hyoguoo.paymentplatform.paymentgateway.infrastructure.dto.response.TossPaymentApiResponse;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentGatewayInfrastructureMapper {

    public static TossPaymentResult toTossPaymentResult(TossPaymentApiResponse tossPaymentResponse) {
        LocalDateTime requestedAt = tossPaymentResponse.getRequestedAt() == null
                ? null
                : LocalDateTime.parse(
                        tossPaymentResponse.getRequestedAt(),
                        TossPaymentApiResponse.DATE_TIME_FORMATTER
                );
        LocalDateTime approvedAt = tossPaymentResponse.getApprovedAt() == null
                ? null
                : LocalDateTime.parse(
                        tossPaymentResponse.getApprovedAt(),
                        TossPaymentApiResponse.DATE_TIME_FORMATTER
                );
        return TossPaymentResult.builder()
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
