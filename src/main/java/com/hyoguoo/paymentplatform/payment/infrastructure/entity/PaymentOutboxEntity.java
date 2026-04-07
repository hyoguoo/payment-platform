package com.hyoguoo.paymentplatform.payment.infrastructure.entity;

import com.hyoguoo.paymentplatform.core.common.infrastructure.BaseEntity;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
        name = "payment_outbox",
        indexes = {
                @Index(name = "idx_payment_outbox_status_retry_created", columnList = "status, next_retry_at, created_at")
        }
)
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentOutboxEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "order_id", nullable = false, unique = true, length = 100)
    private String orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentOutboxStatus status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    @Column(name = "in_flight_at")
    private LocalDateTime inFlightAt;

    public static PaymentOutboxEntity from(PaymentOutbox paymentOutbox) {
        return PaymentOutboxEntity.builder()
                .id(paymentOutbox.getId())
                .orderId(paymentOutbox.getOrderId())
                .status(paymentOutbox.getStatus())
                .retryCount(paymentOutbox.getRetryCount())
                .nextRetryAt(paymentOutbox.getNextRetryAt())
                .inFlightAt(paymentOutbox.getInFlightAt())
                .build();
    }

    public PaymentOutbox toDomain() {
        return PaymentOutbox.allArgsBuilder()
                .id(id)
                .orderId(orderId)
                .status(status)
                .retryCount(retryCount)
                .nextRetryAt(nextRetryAt)
                .inFlightAt(inFlightAt)
                .createdAt(getCreatedAt())
                .updatedAt(getUpdatedAt())
                .allArgsBuild();
    }
}
