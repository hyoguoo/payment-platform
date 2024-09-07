package com.hyoguoo.paymentplatform.order.infrastucture.entity;

import com.hyoguoo.paymentplatform.core.common.infrastructure.BaseEntity;
import com.hyoguoo.paymentplatform.order.domain.OrderInfo;
import com.hyoguoo.paymentplatform.order.domain.enums.OrderStatus;
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
@Table(name = "order_info")
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class OrderInfoEntity extends BaseEntity {

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
    private OrderStatus orderStatus;

    @Column(name = "requested_at")
    private LocalDateTime requestedAt;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "last_transaction_key")
    private String lastTransactionKey;

    public static OrderInfoEntity from(OrderInfo orderInfo) {
        return OrderInfoEntity.builder()
                .id(orderInfo.getId())
                .userId(orderInfo.getUserId())
                .productId(orderInfo.getProductId())
                .orderId(orderInfo.getOrderId())
                .paymentKey(orderInfo.getPaymentKey())
                .orderName(orderInfo.getOrderName())
                .method(orderInfo.getMethod())
                .quantity(orderInfo.getQuantity())
                .totalAmount(orderInfo.getTotalAmount())
                .orderStatus(orderInfo.getOrderStatus())
                .requestedAt(orderInfo.getRequestedAt())
                .approvedAt(orderInfo.getApprovedAt())
                .lastTransactionKey(orderInfo.getLastTransactionKey())
                .build();
    }

    public OrderInfo toDomain() {
        return OrderInfo.allArgsBuilder()
                .id(this.id)
                .userId(this.userId)
                .productId(this.productId)
                .orderId(this.orderId)
                .paymentKey(this.paymentKey)
                .orderName(this.orderName)
                .method(this.method)
                .quantity(this.quantity)
                .totalAmount(this.totalAmount)
                .orderStatus(this.orderStatus)
                .requestedAt(this.requestedAt)
                .approvedAt(this.approvedAt)
                .lastTransactionKey(this.lastTransactionKey)
                .build();
    }
}
