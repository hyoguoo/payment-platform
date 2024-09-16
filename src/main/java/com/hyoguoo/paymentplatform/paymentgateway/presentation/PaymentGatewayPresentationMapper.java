package com.hyoguoo.paymentplatform.paymentgateway.presentation;

import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.TossCancelRequest;
import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.TossConfirmRequest;
import com.hyoguoo.paymentplatform.paymentgateway.application.dto.response.TossPaymentDetails;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.dto.request.TossCancelClientRequest;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.dto.request.TossConfirmClientRequest;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.dto.response.TossDetailsClientResponse;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentGatewayPresentationMapper {

    public static TossDetailsClientResponse toTossDetailsClientResponse(
            TossPaymentDetails tossPaymentDetails
    ) {
        return TossDetailsClientResponse.builder()
                .paymentKey(tossPaymentDetails.getPaymentKey())
                .orderName(tossPaymentDetails.getOrderName())
                .method(tossPaymentDetails.getMethod())
                .totalAmount(tossPaymentDetails.getTotalAmount())
                .status(tossPaymentDetails.getStatus())
                .requestedAt(tossPaymentDetails.getRequestedAt())
                .approvedAt(tossPaymentDetails.getApprovedAt())
                .lastTransactionKey(tossPaymentDetails.getLastTransactionKey())
                .build();
    }

    public static TossConfirmRequest toTossConfirmRequest(
            TossConfirmClientRequest tossConfirmClientRequest
    ) {
        return TossConfirmRequest.builder()
                .orderId(tossConfirmClientRequest.getOrderId())
                .amount(tossConfirmClientRequest.getAmount())
                .paymentKey(tossConfirmClientRequest.getPaymentKey())
                .build();
    }

    public static TossCancelRequest toTossCancelRequest(
            TossCancelClientRequest tossCancelClientRequest
    ) {
        return TossCancelRequest.builder()
                .cancelReason(tossCancelClientRequest.getCancelReason())
                .paymentKey(tossCancelClientRequest.getPaymentKey())
                .build();
    }
}
