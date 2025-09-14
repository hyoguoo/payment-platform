package com.hyoguoo.paymentplatform.payment.infrastructure.entity;

import com.hyoguoo.paymentplatform.core.common.infrastructure.BaseEntity;
import com.hyoguoo.paymentplatform.payment.domain.PaymentHistory;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "payment_history")
public class PaymentHistoryEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "payment_event_id", nullable = false)
    private Long paymentEventId;

    @Column(name = "order_id", nullable = false)
    private String orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status")
    private PaymentEventStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_status", nullable = false)
    private PaymentEventStatus currentStatus;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "retry_count")
    private Integer retryCount;

    @Column(name = "change_status_at", nullable = false)
    private LocalDateTime changeStatusAt;

    public static PaymentHistoryEntity from(PaymentHistory paymentHistory) {
        return PaymentHistoryEntity.builder()
                .id(paymentHistory.getId())
                .paymentEventId(paymentHistory.getPaymentEventId())
                .orderId(paymentHistory.getOrderId())
                .previousStatus(paymentHistory.getPreviousStatus())
                .currentStatus(paymentHistory.getCurrentStatus())
                .reason(paymentHistory.getReason())
                .retryCount(paymentHistory.getRetryCount())
                .changeStatusAt(paymentHistory.getChangeStatusAt())
                .build();
    }

    public PaymentHistory toDomain() {
        return PaymentHistory.builder()
                .id(id)
                .paymentEventId(paymentEventId)
                .orderId(orderId)
                .previousStatus(previousStatus)
                .currentStatus(currentStatus)
                .reason(reason)
                .retryCount(retryCount)
                .changeStatusAt(changeStatusAt)
                .build();
    }
}
