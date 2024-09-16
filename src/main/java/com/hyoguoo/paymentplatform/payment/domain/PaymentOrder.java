package com.hyoguoo.paymentplatform.payment.domain;

import com.hyoguoo.paymentplatform.payment.application.dto.request.OrderConfirmInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.TossPaymentInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.UserInfo;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentStatusException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentValidException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder(builderMethodName = "allArgsBuilder", buildMethodName = "allArgsBuild")
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentOrder {

    private static final String ORDER_ID_PREFIX = "ORDER-";

    private Long id;
    private Long userId;
    private Long productId;
    private String orderId;
    private String paymentKey;
    private String orderName;
    private String method;
    private Integer quantity;
    private BigDecimal totalAmount;
    private PaymentOrderStatus paymentOrderStatus;
    private LocalDateTime requestedAt;
    private LocalDateTime approvedAt;
    private String lastTransactionKey;

    @Builder(builderMethodName = "requiredBuilder", buildMethodName = "requiredBuild")
    protected PaymentOrder(
            UserInfo userInfo,
            ProductInfo productInfo,
            Integer quantity,
            BigDecimal totalAmount
    ) {
        this.userId = userInfo.getId();
        this.productId = productInfo.getId();
        this.quantity = quantity;
        this.totalAmount = totalAmount;

        this.orderId = generateOrderId();
        this.paymentOrderStatus = PaymentOrderStatus.READY;

        this.validateProductInfo(totalAmount, quantity, productInfo);
    }

    private static String generateOrderId() {
        return ORDER_ID_PREFIX + System.currentTimeMillis();
    }

    private void validateProductInfo(
            BigDecimal totalAmount,
            Integer quantity,
            ProductInfo productInfo
    ) {
        BigDecimal totalPrice = productInfo.getPrice().multiply(BigDecimal.valueOf(quantity));

        if (totalAmount.compareTo(totalPrice) != 0) {
            throw PaymentValidException.of(PaymentErrorCode.INVALID_TOTAL_AMOUNT);
        }
    }

    public PaymentOrder confirmOrder(
            TossPaymentInfo paymentInfo,
            OrderConfirmInfo orderConfirmInfo,
            UserInfo userInfo,
            ProductInfo productInfo
    ) {
        if (!paymentInfo.getStatus().equals(PaymentOrderStatus.DONE.getStatusName())) {
            throw PaymentStatusException.of(PaymentErrorCode.NOT_DONE_PAYMENT);
        }

        this.validateOrderInfo(paymentInfo, orderConfirmInfo, userInfo, productInfo);

        updateOrderPaymentInfo(paymentInfo);

        return this;
    }

    public void validateInProgressOrder(
            TossPaymentInfo paymentInfo,
            OrderConfirmInfo orderConfirmInfo,
            UserInfo userInfo,
            ProductInfo productInfo
    ) {
        if (!paymentInfo.getStatus().equals(PaymentOrderStatus.IN_PROGRESS.getStatusName())) {
            throw PaymentStatusException.of(PaymentErrorCode.NOT_IN_PROGRESS_ORDER);
        }

        this.validateOrderInfo(paymentInfo, orderConfirmInfo, userInfo, productInfo);
    }

    public PaymentOrder cancelOrder(TossPaymentInfo paymentInfo) {
        if (!this.paymentKey.equals(paymentInfo.getPaymentKey())) {
            throw PaymentValidException.of(PaymentErrorCode.INVALID_PAYMENT_KEY);
        }

        if (!paymentInfo.getStatus().equals(PaymentOrderStatus.CANCELED.getStatusName())) {
            throw PaymentStatusException.of(PaymentErrorCode.NOT_CANCELED_PAYMENT);
        }

        updateOrderPaymentInfo(paymentInfo);

        return this;
    }

    public PaymentOrder updatePaymentInfo(TossPaymentInfo paymentInfo) {
        updateOrderPaymentInfo(paymentInfo);

        return this;
    }

    private void updateOrderPaymentInfo(TossPaymentInfo paymentInfo) {
        this.requestedAt = paymentInfo.getRequestedAt() == null ? null :
                LocalDateTime.parse(
                        paymentInfo.getRequestedAt(),
                        TossPaymentInfo.DATE_TIME_FORMATTER
                );
        this.approvedAt = paymentInfo.getApprovedAt() == null ? null :
                LocalDateTime.parse(
                        paymentInfo.getApprovedAt(),
                        TossPaymentInfo.DATE_TIME_FORMATTER
                );
        this.lastTransactionKey = paymentInfo.getLastTransactionKey();
        this.orderName = paymentInfo.getOrderName();
        this.paymentKey = paymentInfo.getPaymentKey();
        this.paymentOrderStatus = PaymentOrderStatus.of(paymentInfo.getStatus());
        this.method = paymentInfo.getMethod();
    }

    private void validateOrderInfo(
            TossPaymentInfo paymentInfo,
            OrderConfirmInfo orderConfirmInfo,
            UserInfo userInfo,
            ProductInfo productInfo
    ) {
        if (!this.orderId.equals(orderConfirmInfo.getOrderId())) {
            throw PaymentValidException.of(PaymentErrorCode.INVALID_ORDER_ID);
        }

        if (!this.userId.equals(userInfo.getId())) {
            throw PaymentValidException.of(PaymentErrorCode.INVALID_USER_ID);
        }

        if (!paymentInfo.getPaymentKey().equals(orderConfirmInfo.getPaymentKey())) {
            throw PaymentValidException.of(PaymentErrorCode.INVALID_PAYMENT_KEY);
        }

        if (!compareAmounts(paymentInfo, orderConfirmInfo, productInfo)) {
            throw PaymentValidException.of(PaymentErrorCode.INVALID_TOTAL_AMOUNT);
        }
    }

    private boolean compareAmounts(
            TossPaymentInfo paymentInfo,
            OrderConfirmInfo orderConfirmRequest,
            ProductInfo productInfo
    ) {
        BigDecimal paymentInfoTotalAmount = BigDecimal.valueOf(paymentInfo.getTotalAmount());
        BigDecimal orderConfirmRequestAmount = orderConfirmRequest.getAmount();
        BigDecimal orderInfoAmount = productInfo.getPrice()
                .multiply(BigDecimal.valueOf(this.quantity));

        return orderConfirmRequestAmount.compareTo(paymentInfoTotalAmount) == 0 &&
                orderInfoAmount.compareTo(paymentInfoTotalAmount) == 0 &&
                orderInfoAmount.compareTo(orderConfirmRequestAmount) == 0;
    }
}
