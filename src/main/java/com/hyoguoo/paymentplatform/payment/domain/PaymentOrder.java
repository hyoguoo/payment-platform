package com.hyoguoo.paymentplatform.payment.domain;

import com.hyoguoo.paymentplatform.payment.application.dto.vo.OrderProduct;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentValidException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder(builderMethodName = "allArgsBuilder", buildMethodName = "allArgsBuild")
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentOrder {

    private Long id;
    private Long paymentEventId;
    private String orderId;
    private Long productId;
    private Integer quantity;
    private BigDecimal totalAmount;
    private PaymentOrderStatus status;

    @Builder(builderMethodName = "requiredBuilder", buildMethodName = "requiredBuild")
    protected PaymentOrder(
            PaymentEvent paymentEvent,
            OrderProduct orderProduct,
            ProductInfo productInfo
    ) {
        this.paymentEventId = paymentEvent.getId();
        this.orderId = paymentEvent.getOrderId();
        this.productId = orderProduct.getProductId();
        this.quantity = orderProduct.getQuantity();
        this.totalAmount = productInfo.getPrice().multiply(BigDecimal.valueOf(orderProduct.getQuantity()));

        this.status = PaymentOrderStatus.NOT_STARTED;

        validateTotalAmount(productInfo, orderProduct);
    }

    private void validateTotalAmount(
            ProductInfo productInfo,
            OrderProduct orderProduct
    ) {
        BigDecimal calculatedAmount = productInfo.getPrice()
                .multiply(BigDecimal.valueOf(orderProduct.getQuantity()));

        if (calculatedAmount.compareTo(this.totalAmount) != 0) {
            throw PaymentValidException.of(PaymentErrorCode.INVALID_TOTAL_AMOUNT);
        }
    }
}
