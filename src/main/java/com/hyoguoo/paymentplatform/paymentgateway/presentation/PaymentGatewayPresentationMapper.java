package com.hyoguoo.paymentplatform.paymentgateway.presentation;

import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.TossCancelCommand;
import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.TossConfirmCommand;
import com.hyoguoo.paymentplatform.paymentgateway.domain.TossPaymentInfo;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.dto.request.TossCancelRequest;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.dto.request.TossConfirmRequest;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.dto.response.TossPaymentResponse;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentGatewayPresentationMapper {

    public static TossPaymentResponse toTossDetailsResponse(
            TossPaymentInfo tossPaymentInfo
    ) {
        return TossPaymentResponse.builder()
                .paymentKey(tossPaymentInfo.getPaymentKey())
                .orderId(tossPaymentInfo.getOrderId())
                .paymentConfirmResult(tossPaymentInfo.getPaymentConfirmResult())
                .paymentDetails(tossPaymentInfo.getPaymentDetails())
                .paymentFailure(tossPaymentInfo.getPaymentFailure())
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
