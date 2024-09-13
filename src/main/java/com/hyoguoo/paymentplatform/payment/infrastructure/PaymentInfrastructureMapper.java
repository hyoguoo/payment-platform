package com.hyoguoo.paymentplatform.payment.infrastructure;

import com.hyoguoo.paymentplatform.payment.application.dto.response.TossPaymentDetails;
import com.hyoguoo.paymentplatform.payment.infrastructure.dto.response.TossPaymentResponse;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentInfrastructureMapper {

    public static TossPaymentDetails toPaymentDetails(TossPaymentResponse tossPaymentResponse) {
        return TossPaymentDetails.builder()
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
