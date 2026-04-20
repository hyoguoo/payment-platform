package com.hyoguoo.paymentplatform.paymentgateway.presentation;

import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.NicepayCancelCommand;
import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.NicepayConfirmCommand;
import com.hyoguoo.paymentplatform.paymentgateway.domain.NicepayPaymentInfo;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.dto.request.NicepayCancelRequest;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.dto.request.NicepayConfirmRequest;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.dto.response.NicepayPaymentResponse;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class NicepayGatewayPresentationMapper {

    public static NicepayPaymentResponse toNicepayPaymentResponse(
            NicepayPaymentInfo nicepayPaymentInfo
    ) {
        return NicepayPaymentResponse.builder()
                .tid(nicepayPaymentInfo.getTid())
                .orderId(nicepayPaymentInfo.getOrderId())
                .amount(nicepayPaymentInfo.getAmount())
                .status(nicepayPaymentInfo.getStatus())
                .resultCode(nicepayPaymentInfo.getResultCode())
                .resultMsg(nicepayPaymentInfo.getResultMsg())
                .paidAt(nicepayPaymentInfo.getPaidAt())
                .build();
    }

    public static NicepayConfirmCommand toNicepayConfirmCommand(
            NicepayConfirmRequest nicepayConfirmRequest
    ) {
        return NicepayConfirmCommand.builder()
                .tid(nicepayConfirmRequest.getTid())
                .amount(nicepayConfirmRequest.getAmount())
                .build();
    }

    public static NicepayCancelCommand toNicepayCancelCommand(
            NicepayCancelRequest nicepayCancelRequest
    ) {
        return NicepayCancelCommand.builder()
                .tid(nicepayCancelRequest.getTid())
                .reason(nicepayCancelRequest.getReason())
                .orderId(nicepayCancelRequest.getOrderId())
                .build();
    }
}
