package com.hyoguoo.paymentplatform.payment.presentation;

import com.hyoguoo.paymentplatform.payment.application.dto.request.CheckoutCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.response.CheckoutResult;
import com.hyoguoo.paymentplatform.payment.presentation.dto.request.CheckoutRequest;
import com.hyoguoo.paymentplatform.payment.presentation.dto.response.CheckoutResponse;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentPresentationMapper {

    public static CheckoutCommand toCheckoutCommand(CheckoutRequest request) {
        return CheckoutCommand.builder()
                .userId(request.getUserId())
                .amount(request.getAmount())
                .orderedProductList(request.getOrderedProductList())
                .build();
    }

    public static CheckoutResponse toCheckoutResponse(CheckoutResult result) {
        return CheckoutResponse.builder()
                .orderId(result.getOrderId())
                .build();
    }
}
