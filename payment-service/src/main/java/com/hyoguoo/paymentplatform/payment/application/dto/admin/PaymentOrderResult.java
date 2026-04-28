package com.hyoguoo.paymentplatform.payment.application.dto.admin;

import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import java.math.BigDecimal;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PaymentOrderResult {

    private final Long id;
    private final Long paymentEventId;
    private final String orderId;
    private final Long productId;
    private final Integer quantity;
    private final BigDecimal amount;
    private final PaymentOrderStatus status;

    public static PaymentOrderResult from(PaymentOrder paymentOrder) {
        return PaymentOrderResult.builder()
                .id(paymentOrder.getId())
                .paymentEventId(paymentOrder.getPaymentEventId())
                .orderId(paymentOrder.getOrderId())
                .productId(paymentOrder.getProductId())
                .quantity(paymentOrder.getQuantity())
                .amount(paymentOrder.getTotalAmount())
                .status(paymentOrder.getStatus())
                .build();
    }
}
