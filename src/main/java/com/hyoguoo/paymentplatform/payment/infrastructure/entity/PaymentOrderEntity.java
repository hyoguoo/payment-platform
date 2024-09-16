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
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "payment_order")
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentOrderEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "payment_event_id", nullable = false)
    private Long paymentEventId;

    @Column(name = "order_id", nullable = false)
    private String orderId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "amount", nullable = false)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PaymentOrderStatus status;

    public static PaymentOrderEntity from(PaymentOrder paymentOrder) {
        return PaymentOrderEntity.builder()
                .paymentEventId(paymentOrder.getPaymentEventId())
                .orderId(paymentOrder.getOrderId())
                .productId(paymentOrder.getProductId())
                .quantity(paymentOrder.getQuantity())
                .totalAmount(paymentOrder.getTotalAmount())
                .status(paymentOrder.getStatus())
                .build();
    }

    public PaymentOrder toDomain() {
        return PaymentOrder.allArgsBuilder()
                .paymentEventId(paymentEventId)
                .orderId(orderId)
                .productId(productId)
                .quantity(quantity)
                .totalAmount(totalAmount)
                .status(status)
                .allArgsBuild();
    }
}
