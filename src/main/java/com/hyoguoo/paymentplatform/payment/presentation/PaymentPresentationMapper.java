package com.hyoguoo.paymentplatform.payment.presentation;

import com.hyoguoo.paymentplatform.payment.application.dto.request.CheckoutCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.response.CheckoutResult;
import com.hyoguoo.paymentplatform.payment.application.dto.response.PaymentConfirmResult;
import com.hyoguoo.paymentplatform.payment.presentation.dto.request.CheckoutRequest;
import com.hyoguoo.paymentplatform.payment.presentation.dto.request.PaymentConfirmRequest;
import com.hyoguoo.paymentplatform.payment.presentation.dto.response.CheckoutResponse;
import com.hyoguoo.paymentplatform.payment.presentation.dto.response.PaymentConfirmResponse;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentPresentationMapper {

    public static CheckoutCommand toCheckoutCommand(CheckoutRequest request) {
        return CheckoutCommand.builder()
                .userId(request.getUserId())
                .orderedProductList(request.getOrderedProductList())
                .build();
    }

    public static CheckoutResponse toCheckoutResponse(CheckoutResult result) {
        return CheckoutResponse.builder()
                .orderId(result.getOrderId())
                .totalAmount(result.getTotalAmount())
                .build();
    }

    public static PaymentConfirmCommand toPaymentConfirmCommand(
            PaymentConfirmRequest paymentConfirmRequest
    ) {
        return PaymentConfirmCommand.builder()
                .userId(paymentConfirmRequest.getUserId())
                .orderId(paymentConfirmRequest.getOrderId())
                .paymentKey(paymentConfirmRequest.getPaymentKey())
                .amount(paymentConfirmRequest.getAmount())
                .build();
    }

    public static PaymentConfirmResponse toPaymentConfirmResponse(
            PaymentConfirmResult paymentConfirmResult
    ) {
        return PaymentConfirmResponse.builder()
                .orderId(paymentConfirmResult.getOrderId())
                .amount(paymentConfirmResult.getAmount())
                .build();
    }
}
