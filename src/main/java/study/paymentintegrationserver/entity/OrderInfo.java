package study.paymentintegrationserver.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import study.paymentintegrationserver.domain.TossPayments;

import java.math.BigDecimal;

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

    @Builder.Default
    @Column(name = "status", nullable = false)
    private String status = "READY";

    @Column(name = "requested_at")
    private String requestedAt;

    @Column(name = "approved_at")
    private String approvedAt;

    @Column(name = "last_transaction_key")
    private String lastTransactionKey;

    public OrderInfo confirmOrder(TossPayments paymentInfo) {
        this.approvedAt = paymentInfo.getApprovedAt();
        this.lastTransactionKey = paymentInfo.getLastTransactionKey();
        this.orderName = paymentInfo.getOrderName();
        this.paymentKey = paymentInfo.getPaymentKey();
        this.requestedAt = paymentInfo.getRequestedAt();
        this.status = paymentInfo.getStatus();
        this.method = paymentInfo.getMethod();

        return this;
    }
}
