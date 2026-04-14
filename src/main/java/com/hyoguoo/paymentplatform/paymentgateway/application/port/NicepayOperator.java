package com.hyoguoo.paymentplatform.paymentgateway.application.port;

import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.NicepayCancelCommand;
import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.NicepayConfirmCommand;
import com.hyoguoo.paymentplatform.paymentgateway.domain.NicepayPaymentInfo;
import com.hyoguoo.paymentplatform.paymentgateway.exception.PaymentGatewayApiException;

public interface NicepayOperator {

    NicepayPaymentInfo confirmPayment(NicepayConfirmCommand nicepayConfirmCommand)
            throws PaymentGatewayApiException;

    NicepayPaymentInfo getPaymentInfoByTid(String tid);

    NicepayPaymentInfo getPaymentInfoByOrderId(String orderId) throws PaymentGatewayApiException;

    NicepayPaymentInfo cancelPayment(NicepayCancelCommand nicepayCancelCommand);
}
