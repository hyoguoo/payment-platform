package com.hyoguoo.paymentplatform.paymentgateway.presentation;

import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.TossCancelCommand;
import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.TossConfirmCommand;
import com.hyoguoo.paymentplatform.paymentgateway.application.dto.response.TossPaymentResult;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.dto.request.TossCancelRequest;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.dto.request.TossConfirmRequest;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.dto.response.TossPaymentResponse;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentGatewayPresentationMapper {

    public static TossPaymentResponse toTossDetailsResponse(
            TossPaymentResult tossPaymentResult
    ) {
        return TossPaymentResponse.builder()
                .paymentKey(tossPaymentResult.getPaymentKey())
                .orderName(tossPaymentResult.getOrderName())
                .method(tossPaymentResult.getMethod())
                .totalAmount(tossPaymentResult.getTotalAmount())
                .status(tossPaymentResult.getStatus())
                .requestedAt(tossPaymentResult.getRequestedAt())
                .approvedAt(tossPaymentResult.getApprovedAt())
                .lastTransactionKey(tossPaymentResult.getLastTransactionKey())
                .build();
    }

    public static TossConfirmCommand toTossConfirmCommand(
            TossConfirmRequest tossConfirmRequest
    ) {
        return TossConfirmCommand.builder()
                .orderId(tossConfirmRequest.getOrderId())
                .amount(tossConfirmRequest.getAmount())
                .paymentKey(tossConfirmRequest.getPaymentKey())
                .build();
    }

    public static TossCancelCommand toTossCancelCommand(
            TossCancelRequest tossCancelRequest
    ) {
        return TossCancelCommand.builder()
                .cancelReason(tossCancelRequest.getCancelReason())
                .paymentKey(tossCancelRequest.getPaymentKey())
                .build();
    }
}
