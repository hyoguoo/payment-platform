package com.hyoguoo.paymentplatform.payment.infrastructure.entity;

import com.hyoguoo.paymentplatform.core.common.infrastructure.BaseEntity;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "payment_event")
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentEventEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "buyer_id", nullable = false)
    private Long buyerId;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(name = "order_name", nullable = false)
    private String orderName;

    @Column(name = "order_id", nullable = false)
    private String orderId;

    @Column(name = "payment_key")
    private String paymentKey;

    @Column(name = "is_payment_done", nullable = false)
    private Boolean isPaymentDone;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    public static PaymentEventEntity from(PaymentEvent paymentEvent) {
        return PaymentEventEntity.builder()
                .buyerId(paymentEvent.getBuyerId())
                .sellerId(paymentEvent.getSellerId())
                .orderName(paymentEvent.getOrderName())
                .orderId(paymentEvent.getOrderId())
                .paymentKey(paymentEvent.getPaymentKey())
                .isPaymentDone(paymentEvent.getIsPaymentDone())
                .approvedAt(paymentEvent.getApprovedAt())
                .build();
    }

    public PaymentEvent toDomain() {
        return PaymentEvent.allArgsBuilder()
                .id(id)
                .buyerId(buyerId)
                .sellerId(sellerId)
                .orderName(orderName)
                .orderId(orderId)
                .paymentKey(paymentKey)
                .isPaymentDone(isPaymentDone)
                .approvedAt(approvedAt)
                .allArgsBuild();
    }
}
