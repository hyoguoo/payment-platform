package com.hyoguoo.paymentplatform.payment.infrastructure.entity;

import com.hyoguoo.paymentplatform.core.common.infrastructure.BaseEntity;
import com.hyoguoo.paymentplatform.payment.domain.PaymentProcess;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentProcessStatus;
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
@Table(name = "payment_process")
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentProcessEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "order_id", nullable = false, unique = true, length = 100)
    private String orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentProcessStatus status;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "failed_at")
    private LocalDateTime failedAt;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    public static PaymentProcessEntity from(PaymentProcess paymentProcess) {
        return PaymentProcessEntity.builder()
                .id(paymentProcess.getId())
                .orderId(paymentProcess.getOrderId())
                .status(paymentProcess.getStatus())
                .completedAt(paymentProcess.getCompletedAt())
                .failedAt(paymentProcess.getFailedAt())
                .failureReason(paymentProcess.getFailureReason())
                .build();
    }

    public PaymentProcess toDomain() {
        return PaymentProcess.allArgsBuilder()
                .id(id)
                .orderId(orderId)
                .status(status)
                .completedAt(completedAt)
                .failedAt(failedAt)
                .failureReason(failureReason)
                .createdAt(getCreatedAt())
                .updatedAt(getUpdatedAt())
                .allArgsBuild();
    }
}
