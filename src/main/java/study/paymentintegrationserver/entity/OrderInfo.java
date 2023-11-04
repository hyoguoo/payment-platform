package study.paymentintegrationserver.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import study.paymentintegrationserver.dto.toss.TossPaymentResponse;
import study.paymentintegrationserver.dto.order.OrderConfirmRequest;
import study.paymentintegrationserver.exception.OrderInfoErrorMessage;
import study.paymentintegrationserver.exception.OrderInfoException;

import java.math.BigDecimal;

@Getter
@Entity
@Builder
@Table(name = "order_info")
@NoArgsConstructor
@AllArgsConstructor
public class OrderInfo extends BaseTime {

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

    @Column(name = "order_id", nullable = false)
    private String orderId;

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

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "requested_at")
    private String requestedAt;

    @Column(name = "approved_at")
    private String approvedAt;

    @Column(name = "last_transaction_key")
    private String lastTransactionKey;

    public OrderInfo confirmOrder(TossPaymentResponse paymentInfo, OrderConfirmRequest orderConfirmRequest) {
        this.validateOrderInfo(paymentInfo, orderConfirmRequest);

        updateOrderPaymentInfo(paymentInfo);

        return this;
    }

    public OrderInfo cancelOrder(TossPaymentResponse paymentInfo) {
        updateOrderPaymentInfo(paymentInfo);

        return this;
    }

    public OrderInfo updatePaymentInfo(TossPaymentResponse paymentInfo) {
        updateOrderPaymentInfo(paymentInfo);

        return this;
    }

    private void updateOrderPaymentInfo(TossPaymentResponse paymentInfo) {
        this.approvedAt = paymentInfo.getApprovedAt();
        this.lastTransactionKey = paymentInfo.getLastTransactionKey();
        this.orderName = paymentInfo.getOrderName();
        this.paymentKey = paymentInfo.getPaymentKey();
        this.requestedAt = paymentInfo.getRequestedAt();
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

    // Builder Pattern 사용 시 자동으로 실행되는 클래스
    @SuppressWarnings("unused")
    public static class OrderInfoBuilder {
        private void validateProductInfo(BigDecimal totalAmount, Integer quantity) {
            this.product.validateStock(quantity);

            BigDecimal totalPrice = this.product.getPrice().multiply(BigDecimal.valueOf(quantity));
            if (totalAmount.compareTo(totalPrice) != 0) {
                throw OrderInfoException.of(OrderInfoErrorMessage.INVALID_TOTAL_AMOUNT);
            }
        }

        public OrderInfo build(BigDecimal totalAmount, Integer quantity) {
            this.validateProductInfo(totalAmount, quantity);

            return new OrderInfo(
                    this.id,
                    this.user,
                    this.product,
                    this.orderId,
                    this.paymentKey,
                    this.orderName,
                    this.method,
                    this.quantity,
                    this.totalAmount,
                    this.status,
                    this.requestedAt,
                    this.approvedAt,
                    this.lastTransactionKey
            );
        }
    }
}
