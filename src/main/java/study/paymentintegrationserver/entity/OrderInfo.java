package study.paymentintegrationserver.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import study.paymentintegrationserver.dto.order.OrderConfirmRequest;
import study.paymentintegrationserver.dto.toss.TossPaymentResponse;
import study.paymentintegrationserver.exception.OrderInfoErrorMessage;
import study.paymentintegrationserver.exception.OrderInfoException;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Entity
@Builder
@Table(name = "order_info")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderInfo extends BaseTime {

    private static final String ORDER_ID_PREFIX = "ORDER-";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Builder.Default
    @Column(name = "order_id", nullable = false)
    private String orderId = generateOrderId();

    @Column(name = "payment_key")
    private String paymentKey;

    @Column(name = "order_name")
    private String orderName;

    @Column(name = "method")
    private String method;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    @Builder.Default
    @Column(name = "status", nullable = false)
    private String status = OrderStatus.READY.getStatusName();

    @Column(name = "requested_at")
    private LocalDateTime requestedAt;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "last_transaction_key")
    private String lastTransactionKey;

    @SuppressWarnings("java:S107")
    protected OrderInfo(Long id, User user, Product product, String orderId, String paymentKey, String orderName, String method, Integer quantity, BigDecimal totalAmount, String status, LocalDateTime requestedAt, LocalDateTime approvedAt, String lastTransactionKey) {
        this.id = id;
        this.user = user;
        this.product = product;
        this.orderId = orderId;
        this.paymentKey = paymentKey;
        this.orderName = orderName;
        this.method = method;
        this.quantity = quantity;
        this.totalAmount = totalAmount;
        this.status = status;
        this.requestedAt = requestedAt;
        this.approvedAt = approvedAt;
        this.lastTransactionKey = lastTransactionKey;

        this.validateProductInfo(totalAmount, quantity);
    }

    private static String generateOrderId() {
        return ORDER_ID_PREFIX + System.currentTimeMillis();
    }

    private void validateProductInfo(BigDecimal totalAmount, Integer quantity) {
        this.product.validateStock(quantity);

        BigDecimal totalPrice = this.product.getPrice().multiply(BigDecimal.valueOf(quantity));
        if (totalAmount.compareTo(totalPrice) != 0) {
            throw OrderInfoException.of(OrderInfoErrorMessage.INVALID_TOTAL_AMOUNT);
        }
    }

    public OrderInfo confirmOrder(TossPaymentResponse paymentInfo, OrderConfirmRequest orderConfirmRequest) {
        if (!paymentInfo.getStatus().equals(OrderStatus.DONE.getStatusName())) {
            throw OrderInfoException.of(OrderInfoErrorMessage.NOT_DONE_PAYMENT);
        }

        this.validateOrderInfo(paymentInfo, orderConfirmRequest);

        updateOrderPaymentInfo(paymentInfo);

        return this;
    }

    public OrderInfo cancelOrder(TossPaymentResponse paymentInfo) {
        if (!this.paymentKey.equals(paymentInfo.getPaymentKey())) {
            throw OrderInfoException.of(OrderInfoErrorMessage.INVALID_PAYMENT_KEY);
        }

        if (!paymentInfo.getStatus().equals(OrderStatus.CANCELED.getStatusName())) {
            throw OrderInfoException.of(OrderInfoErrorMessage.NOT_CANCELED_PAYMENT);
        }

        updateOrderPaymentInfo(paymentInfo);

        return this;
    }

    public OrderInfo updatePaymentInfo(TossPaymentResponse paymentInfo) {
        updateOrderPaymentInfo(paymentInfo);

        return this;
    }

    private void updateOrderPaymentInfo(TossPaymentResponse paymentInfo) {
        this.approvedAt = LocalDateTime.parse(paymentInfo.getApprovedAt(), TossPaymentResponse.DATE_TIME_FORMATTER);
        this.lastTransactionKey = paymentInfo.getLastTransactionKey();
        this.orderName = paymentInfo.getOrderName();
        this.paymentKey = paymentInfo.getPaymentKey();
        this.requestedAt = LocalDateTime.parse(paymentInfo.getRequestedAt(), TossPaymentResponse.DATE_TIME_FORMATTER);
        this.status = paymentInfo.getStatus();
        this.method = paymentInfo.getMethod();
    }

    public void validateOrderInfo(TossPaymentResponse paymentInfo, OrderConfirmRequest orderConfirmRequest) {
        if (!this.orderId.equals(orderConfirmRequest.getOrderId())) {
            throw OrderInfoException.of(OrderInfoErrorMessage.INVALID_ORDER_ID);
        }

        if (!this.user.getId().equals(orderConfirmRequest.getUserId())) {
            throw OrderInfoException.of(OrderInfoErrorMessage.INVALID_USER_ID);
        }

        if (!paymentInfo.getPaymentKey().equals(orderConfirmRequest.getPaymentKey())) {
            throw OrderInfoException.of(OrderInfoErrorMessage.INVALID_PAYMENT_KEY);
        }

        if (!compareAmounts(paymentInfo, orderConfirmRequest)) {
            throw OrderInfoException.of(OrderInfoErrorMessage.INVALID_TOTAL_AMOUNT);
        }
    }

    private boolean compareAmounts(TossPaymentResponse paymentInfo, OrderConfirmRequest orderConfirmRequest) {
        BigDecimal paymentInfoTotalAmount = BigDecimal.valueOf(paymentInfo.getTotalAmount());
        BigDecimal orderConfirmRequestAmount = orderConfirmRequest.getAmount();
        BigDecimal orderInfoAmount = this.product.getPrice().multiply(BigDecimal.valueOf(this.quantity));

        return orderInfoAmount.compareTo(paymentInfoTotalAmount) == 0 &&
               orderInfoAmount.compareTo(orderConfirmRequestAmount) == 0 &&
               orderConfirmRequestAmount.compareTo(paymentInfoTotalAmount) == 0;
    }

    @Getter
    enum OrderStatus {
        READY("READY"), CANCELED("CANCELED"), DONE("DONE");

        private final String statusName;

        OrderStatus(String statusName) {
            this.statusName = statusName;
        }
    }
}
