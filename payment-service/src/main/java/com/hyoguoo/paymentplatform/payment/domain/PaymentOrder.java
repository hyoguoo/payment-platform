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
                this.status != PaymentOrderStatus.EXECUTING) {
            throw PaymentStatusException.of(PaymentErrorCode.INVALID_STATUS_TO_EXECUTE);
        }
        this.status = PaymentOrderStatus.EXECUTING;
    }

    public void fail() {
        if (this.status != PaymentOrderStatus.NOT_STARTED &&
                this.status != PaymentOrderStatus.EXECUTING) {
            throw PaymentStatusException.of(PaymentErrorCode.INVALID_STATUS_TO_FAIL);
        }
        this.status = PaymentOrderStatus.FAIL;
    }

    public void success() {
        if (this.status != PaymentOrderStatus.EXECUTING) {
            throw PaymentStatusException.of(PaymentErrorCode.INVALID_STATUS_TO_SUCCESS);
        }
        this.status = PaymentOrderStatus.SUCCESS;
    }

    public void expire() {
        if (this.status != PaymentOrderStatus.NOT_STARTED) {
            throw PaymentStatusException.of(PaymentErrorCode.INVALID_STATUS_TO_EXPIRE);
        }
        this.status = PaymentOrderStatus.EXPIRED;
    }

    /**
     * Reconciler가 IN_PROGRESS → READY 복원 시 주문을 NOT_STARTED 로 되돌린다.
     * READY 상태의 불변식(모든 주문이 NOT_STARTED) 을 유지해 이후 execute() 와 expire() 가
     * 정상 경로를 밟을 수 있도록 보장한다.
     * EXECUTING 상태에서만 유효하다.
     */
    public void resetToNotStarted() {
        if (this.status != PaymentOrderStatus.EXECUTING) {
            throw PaymentStatusException.of(PaymentErrorCode.INVALID_STATUS_TO_RESET);
        }
        this.status = PaymentOrderStatus.NOT_STARTED;
    }
}
