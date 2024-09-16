package com.hyoguoo.paymentplatform.payment.infrastructure.entity;

import com.hyoguoo.paymentplatform.core.common.infrastructure.BaseEntity;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "payment_orders")
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentOrderEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

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

    @Enumerated(EnumType.STRING)
    @Column(name = "order_status", nullable = false)
    private PaymentOrderStatus paymentOrderStatus;

    @Column(name = "requested_at")
    private LocalDateTime requestedAt;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "last_transaction_key")
    private String lastTransactionKey;

    public static PaymentOrderEntity from(PaymentOrder paymentOrder) {
        return PaymentOrderEntity.builder()
                .id(paymentOrder.getId())
                .userId(paymentOrder.getUserId())
                .productId(paymentOrder.getProductId())
                .orderId(paymentOrder.getOrderId())
                .paymentKey(paymentOrder.getPaymentKey())
                .orderName(paymentOrder.getOrderName())
                .method(paymentOrder.getMethod())
                .quantity(paymentOrder.getQuantity())
                .totalAmount(paymentOrder.getTotalAmount())
                .paymentOrderStatus(paymentOrder.getPaymentOrderStatus())
                .requestedAt(paymentOrder.getRequestedAt())
                .approvedAt(paymentOrder.getApprovedAt())
                .lastTransactionKey(paymentOrder.getLastTransactionKey())
                .build();
    }

    public PaymentOrder toDomain() {
        return PaymentOrder.allArgsBuilder()
                .id(this.id)
                .userId(this.userId)
                .productId(this.productId)
                .orderId(this.orderId)
                .paymentKey(this.paymentKey)
                .orderName(this.orderName)
                .method(this.method)
                .quantity(this.quantity)
                .totalAmount(this.totalAmount)
                .paymentOrderStatus(this.paymentOrderStatus)
                .requestedAt(this.requestedAt)
                .approvedAt(this.approvedAt)
                .lastTransactionKey(this.lastTransactionKey)
                .allArgsBuild();
    }
}
