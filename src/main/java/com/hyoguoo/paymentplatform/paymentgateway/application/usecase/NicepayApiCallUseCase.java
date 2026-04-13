package com.hyoguoo.paymentplatform.paymentgateway.application.usecase;

import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.NicepayCancelCommand;
import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.NicepayConfirmCommand;
import com.hyoguoo.paymentplatform.paymentgateway.application.port.NicepayOperator;
import com.hyoguoo.paymentplatform.paymentgateway.domain.NicepayPaymentInfo;
import com.hyoguoo.paymentplatform.paymentgateway.exception.PaymentGatewayApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NicepayApiCallUseCase {

    private final NicepayOperator nicepayOperator;

    public NicepayPaymentInfo executeConfirmPayment(
            NicepayConfirmCommand nicepayConfirmCommand
    ) throws PaymentGatewayApiException {
        return nicepayOperator.confirmPayment(nicepayConfirmCommand);
    }

    public NicepayPaymentInfo getPaymentInfoByTid(String tid) {
        return nicepayOperator.getPaymentInfoByTid(tid);
    }

    public NicepayPaymentInfo getPaymentInfoByOrderId(String orderId) {
        return nicepayOperator.getPaymentInfoByOrderId(orderId);
    }

    public NicepayPaymentInfo executeCancelPayment(
            NicepayCancelCommand nicepayCancelCommand
    ) {
        return nicepayOperator.cancelPayment(nicepayCancelCommand);
    }
}
