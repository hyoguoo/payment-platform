package com.hyoguoo.paymentplatform.payment.presentation.dto.response.admin;

import com.hyoguoo.paymentplatform.payment.application.dto.admin.PaymentOrderResult;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import java.math.BigDecimal;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PaymentOrderResponse {

    private final Long id;
    private final Long paymentEventId;
    private final String orderId;
    private final Long productId;
    private final Integer quantity;
    private final BigDecimal totalAmount;
    private final PaymentOrderStatus status;

    public static PaymentOrderResponse from(PaymentOrderResult result) {
        return PaymentOrderResponse.builder()
                .id(result.getId())
                .paymentEventId(result.getPaymentEventId())
                .orderId(result.getOrderId())
                .productId(result.getProductId())
                .quantity(result.getQuantity())
                .totalAmount(result.getAmount())
                .status(result.getStatus())
                .build();
    }
}
