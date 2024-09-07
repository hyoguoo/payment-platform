package com.hyoguoo.paymentplatform.order.domain;

import com.hyoguoo.paymentplatform.order.domain.enums.OrderStatus;
import com.hyoguoo.paymentplatform.order.exception.OrderStatusException;
import com.hyoguoo.paymentplatform.order.exception.OrderValidException;
import com.hyoguoo.paymentplatform.order.exception.common.OrderErrorCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;
import study.paymentintegrationserver.dto.order.OrderConfirmRequest;
import study.paymentintegrationserver.dto.toss.TossPaymentResponse;

@Getter
@Builder(builderMethodName = "allArgsBuilder")
public class OrderInfo {

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
    private OrderStatus orderStatus;
    private LocalDateTime requestedAt;
    private LocalDateTime approvedAt;
    private String lastTransactionKey;

    @Builder
    protected OrderInfo(Long userId, Long productId, Integer quantity, BigDecimal totalAmount) {
        this.userId = userId;
        this.productId = productId;
        this.quantity = quantity;
        this.totalAmount = totalAmount;

        this.orderId = generateOrderId();
        this.orderStatus = OrderStatus.READY;

        this.validateProductInfo(totalAmount, quantity);
    }

    private static String generateOrderId() {
        return ORDER_ID_PREFIX + System.currentTimeMillis();
    }

    private void validateProductInfo(BigDecimal totalAmount, Integer quantity) {
        // TODO: this.product는 불가능하므로 대신 product dto 받아와서 유효성 검증, order가 주체적으로 수행하도록 변경
//        this.product.validateStock(quantity);

//        BigDecimal totalPrice = this.product.calculateTotalPrice(quantity);
//        if (totalAmount.compareTo(totalPrice) != 0) {
        if (false) {
            throw OrderValidException.of(OrderErrorCode.INVALID_TOTAL_AMOUNT);
        }
    }

    public OrderInfo confirmOrder(
            TossPaymentResponse paymentInfo,
            OrderConfirmRequest orderConfirmRequest
    ) {
        if (!paymentInfo.getStatus().equals(OrderStatus.DONE.getStatusName())) {
            throw OrderStatusException.of(OrderErrorCode.NOT_DONE_PAYMENT);
        }

        this.validateOrderInfo(paymentInfo, orderConfirmRequest);

        updateOrderPaymentInfo(paymentInfo);

        return this;
    }

    public void validateInProgressOrder(
            TossPaymentResponse paymentInfo,
            OrderConfirmRequest orderConfirmRequest
    ) {
        if (!paymentInfo.getStatus().equals(OrderStatus.IN_PROGRESS.getStatusName())) {
            throw OrderStatusException.of(OrderErrorCode.NOT_IN_PROGRESS_ORDER);
        }

        this.validateOrderInfo(paymentInfo, orderConfirmRequest);
    }

    public OrderInfo cancelOrder(TossPaymentResponse paymentInfo) {
        if (!this.paymentKey.equals(paymentInfo.getPaymentKey())) {
            throw OrderValidException.of(OrderErrorCode.INVALID_PAYMENT_KEY);
        }

        if (!paymentInfo.getStatus().equals(OrderStatus.CANCELED.getStatusName())) {
            throw OrderStatusException.of(OrderErrorCode.NOT_CANCELED_PAYMENT);
        }

        updateOrderPaymentInfo(paymentInfo);

        return this;
    }

    public OrderInfo updatePaymentInfo(TossPaymentResponse paymentInfo) {
        updateOrderPaymentInfo(paymentInfo);

        return this;
    }

    private void updateOrderPaymentInfo(TossPaymentResponse paymentInfo) {
        this.requestedAt = paymentInfo.getRequestedAt() == null ? null :
                LocalDateTime.parse(
                        paymentInfo.getRequestedAt(),
                        TossPaymentResponse.DATE_TIME_FORMATTER
                );
        this.approvedAt = paymentInfo.getApprovedAt() == null ? null :
                LocalDateTime.parse(
                        paymentInfo.getApprovedAt(),
                        TossPaymentResponse.DATE_TIME_FORMATTER
                );
        this.lastTransactionKey = paymentInfo.getLastTransactionKey();
        this.orderName = paymentInfo.getOrderName();
        this.paymentKey = paymentInfo.getPaymentKey();
        this.orderStatus = OrderStatus.of(paymentInfo.getStatus());
        this.method = paymentInfo.getMethod();
    }

    private void validateOrderInfo(
            TossPaymentResponse paymentInfo,
            OrderConfirmRequest orderConfirmRequest
    ) {
        if (!this.orderId.equals(orderConfirmRequest.getOrderId())) {
            throw OrderValidException.of(OrderErrorCode.INVALID_ORDER_ID);
        }

        // TODO: this.user는 불가능하므로 대신 user dto 받아와서 유효성 검증, order가 주체적으로 수행하도록 변경
//        if (!this.user.getId().equals(orderConfirmRequest.getUserId())) {
//            throw OrderValidException.of(OrderErrorCode.INVALID_USER_ID);
//        }

        if (!paymentInfo.getPaymentKey().equals(orderConfirmRequest.getPaymentKey())) {
            throw OrderValidException.of(OrderErrorCode.INVALID_PAYMENT_KEY);
        }

        if (!compareAmounts(paymentInfo, orderConfirmRequest)) {
            throw OrderValidException.of(OrderErrorCode.INVALID_TOTAL_AMOUNT);
        }
    }

    private boolean compareAmounts(
            TossPaymentResponse paymentInfo,
            OrderConfirmRequest orderConfirmRequest
    ) {
        // TODO: this.product는 불가능하므로 대신 product dto 받아와서 유효성 검증, order가 주체적으로 수행하도록 변경
        BigDecimal paymentInfoTotalAmount = BigDecimal.valueOf(paymentInfo.getTotalAmount());
        BigDecimal orderConfirmRequestAmount = orderConfirmRequest.getAmount();
//        BigDecimal orderInfoAmount = this.product.calculateTotalPrice(this.quantity);

        return orderConfirmRequestAmount.compareTo(paymentInfoTotalAmount) == 0;
//        return orderConfirmRequestAmount.compareTo(paymentInfoTotalAmount) == 0 &&
//                orderInfoAmount.compareTo(paymentInfoTotalAmount) == 0 &&
//                orderInfoAmount.compareTo(orderConfirmRequestAmount) == 0;
    }
}
