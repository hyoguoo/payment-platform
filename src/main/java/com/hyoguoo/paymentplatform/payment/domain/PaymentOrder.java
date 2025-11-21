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

    public static PaymentOrder create(
            PaymentEvent paymentEvent,
            OrderedProduct orderedProduct,
            ProductInfo productInfo
    ) {
        BigDecimal totalAmount = productInfo.getPrice()
                .multiply(BigDecimal.valueOf(orderedProduct.getQuantity()));

        return PaymentOrder.allArgsBuilder()
                .paymentEventId(paymentEvent.getId())
                .orderId(paymentEvent.getOrderId())
                .productId(orderedProduct.getProductId())
                .quantity(orderedProduct.getQuantity())
                .totalAmount(totalAmount)
                .status(PaymentOrderStatus.NOT_STARTED)
                .allArgsBuild();
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
        if (this.status != PaymentOrderStatus.NOT_STARTED &&
                this.status != PaymentOrderStatus.EXECUTING &&
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
                this.status != PaymentOrderStatus.EXECUTING &&
                this.status != PaymentOrderStatus.UNKNOWN) {
            throw PaymentStatusException.of(PaymentErrorCode.INVALID_STATUS_TO_UNKNOWN);
        }
        this.status = PaymentOrderStatus.UNKNOWN;
    }

    public void expire() {
        if (this.status != PaymentOrderStatus.NOT_STARTED) {
            throw PaymentStatusException.of(PaymentErrorCode.INVALID_STATUS_TO_EXPIRE);
        }
        this.status = PaymentOrderStatus.EXPIRED;
    }
}
