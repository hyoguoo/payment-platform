package com.hyoguoo.paymentplatform.payment.presentation;

import com.hyoguoo.paymentplatform.payment.application.dto.vo.OrderProduct;
import com.hyoguoo.paymentplatform.payment.presentation.dto.request.CheckoutRequest;
import com.hyoguoo.paymentplatform.payment.presentation.dto.response.CheckoutResponse;
import com.hyoguoo.paymentplatform.payment.application.dto.request.CheckoutCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.response.CheckoutResult;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentPresentationMapper {

    public static CheckoutCommand toCheckoutCommand(CheckoutRequest request) {
        OrderProduct orderProduct = OrderProduct.builder()
                .productId(request.getProductId())
                .quantity(request.getQuantity())
                .build();

        return CheckoutCommand.builder()
                .userId(request.getUserId())
                .amount(request.getAmount())
                .orderProduct(orderProduct)
                .build();
    }

    public static CheckoutResponse toCheckoutResponse(CheckoutResult result) {
        return CheckoutResponse.builder()
                .orderId(result.getOrderId())
                .build();
    }
}
