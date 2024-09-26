package com.hyoguoo.paymentplatform.payment.domain;

import com.hyoguoo.paymentplatform.payment.application.dto.vo.OrderedProduct;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentStatusException;
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
    @SuppressWarnings("unused")
    protected PaymentOrder(
            PaymentEvent paymentEvent,
            OrderedProduct orderedProduct,
            ProductInfo productInfo
    ) {
        this.paymentEventId = paymentEvent.getId();
        this.orderId = paymentEvent.getOrderId();
        this.productId = orderedProduct.getProductId();
        this.quantity = orderedProduct.getQuantity();
        this.totalAmount = productInfo.getPrice()
                .multiply(BigDecimal.valueOf(orderedProduct.getQuantity()));

        this.status = PaymentOrderStatus.NOT_STARTED;
    }

    public void execute() {
        if (this.status != PaymentOrderStatus.NOT_STARTED &&
                this.status != PaymentOrderStatus.EXECUTING &&
                this.status != PaymentOrderStatus.UNKNOWN) {
            throw PaymentStatusException.of(PaymentErrorCode.INVALID_STATUS_TO_EXECUTE);
        }
        this.status = PaymentOrderStatus.EXECUTING;
    }

    public void fail() {
        if (this.status != PaymentOrderStatus.EXECUTING &&
                this.status != PaymentOrderStatus.UNKNOWN) {
            throw PaymentStatusException.of(PaymentErrorCode.INVALID_STATUS_TO_FAIL);
        }
        this.status = PaymentOrderStatus.FAIL;
    }

    public void success() {
        if (this.status != PaymentOrderStatus.EXECUTING &&
                this.status != PaymentOrderStatus.UNKNOWN) {
            throw PaymentStatusException.of(PaymentErrorCode.INVALID_STATUS_TO_SUCCESS);
        }
        this.status = PaymentOrderStatus.SUCCESS;
    }

    public void unknown() {
        if (this.status != PaymentOrderStatus.NOT_STARTED &&
                this.status != PaymentOrderStatus.EXECUTING) {
            throw PaymentStatusException.of(PaymentErrorCode.INVALID_STATUS_TO_SUCCESS);
        }
        this.status = PaymentOrderStatus.UNKNOWN;
    }
}
