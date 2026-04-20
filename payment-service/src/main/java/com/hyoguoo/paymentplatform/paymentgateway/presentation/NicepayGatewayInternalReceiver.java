package com.hyoguoo.paymentplatform.paymentgateway.presentation;

import com.hyoguoo.paymentplatform.paymentgateway.application.usecase.NicepayApiCallUseCase;
import com.hyoguoo.paymentplatform.paymentgateway.domain.NicepayPaymentInfo;
import com.hyoguoo.paymentplatform.paymentgateway.exception.PaymentGatewayApiException;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.dto.request.NicepayCancelRequest;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.dto.request.NicepayConfirmRequest;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.dto.response.NicepayPaymentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NicepayGatewayInternalReceiver {

    private final NicepayApiCallUseCase nicepayApiCallUseCase;

    public NicepayPaymentResponse confirmPayment(
            NicepayConfirmRequest nicepayConfirmRequest
    ) throws PaymentGatewayApiException {
        NicepayPaymentInfo nicepayPaymentInfo = nicepayApiCallUseCase.executeConfirmPayment(
                NicepayGatewayPresentationMapper.toNicepayConfirmCommand(nicepayConfirmRequest)
        );
        return NicepayGatewayPresentationMapper.toNicepayPaymentResponse(nicepayPaymentInfo);
    }

    public NicepayPaymentResponse getPaymentInfoByTid(String tid) {
        NicepayPaymentInfo nicepayPaymentInfo = nicepayApiCallUseCase.getPaymentInfoByTid(tid);
        return NicepayGatewayPresentationMapper.toNicepayPaymentResponse(nicepayPaymentInfo);
    }

    public NicepayPaymentResponse getPaymentInfoByOrderId(String orderId)
            throws PaymentGatewayApiException {
        NicepayPaymentInfo nicepayPaymentInfo = nicepayApiCallUseCase.getPaymentInfoByOrderId(
                orderId
        );
        return NicepayGatewayPresentationMapper.toNicepayPaymentResponse(nicepayPaymentInfo);
    }

    public NicepayPaymentResponse cancelPayment(
            NicepayCancelRequest nicepayCancelRequest
    ) {
        NicepayPaymentInfo nicepayPaymentInfo = nicepayApiCallUseCase.executeCancelPayment(
                NicepayGatewayPresentationMapper.toNicepayCancelCommand(nicepayCancelRequest)
        );
        return NicepayGatewayPresentationMapper.toNicepayPaymentResponse(nicepayPaymentInfo);
    }
}
