package com.hyoguoo.paymentplatform.payment.infrastructure.entity;

import com.hyoguoo.paymentplatform.payment.core.common.infrastructure.BaseEntity;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentGatewayType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
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

    // DB 컬럼 바인딩 전용 — payment-service 는 벤더를 직접 호출하지 않는다(pg-service 가 전담).
    @Enumerated(EnumType.STRING)
    @Column(name = "gateway_type", nullable = false)
    private PaymentGatewayType gatewayType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PaymentEventStatus status;

    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "retry_count")
    private Integer retryCount;

    @Column(name = "status_reason")
    private String statusReason;

    @Column(name = "last_status_changed_at")
    private LocalDateTime lastStatusChangedAt;

    // DB 컬럼 quarantine_compensation_pending 은 스키마에 유지(dead column — 후속 V2 ALTER 정리 대상).
    // 도메인에서는 제거 — QUARANTINED 는 홀딩 상태이며 재고 복구 대상이 아니다.

    public static PaymentEventEntity from(PaymentEvent paymentEvent) {
        return PaymentEventEntity.builder()
                .id(paymentEvent.getId())
                .buyerId(paymentEvent.getBuyerId())
                .sellerId(paymentEvent.getSellerId())
                .orderName(paymentEvent.getOrderName())
                .orderId(paymentEvent.getOrderId())
                .paymentKey(paymentEvent.getPaymentKey())
                .gatewayType(paymentEvent.getGatewayType())
                .status(paymentEvent.getStatus())
                .executedAt(paymentEvent.getExecutedAt())
                .approvedAt(paymentEvent.getApprovedAt())
                .retryCount(paymentEvent.getRetryCount())
                .statusReason(paymentEvent.getStatusReason())
                .lastStatusChangedAt(paymentEvent.getLastStatusChangedAt())
                .build();
    }

    public PaymentEvent toDomain(List<PaymentOrder> paymentOrderList) {
        return PaymentEvent.allArgsBuilder()
                .id(id)
                .buyerId(buyerId)
                .sellerId(sellerId)
                .orderName(orderName)
                .orderId(orderId)
                .paymentKey(paymentKey)
                .gatewayType(gatewayType)
                .status(status)
                .executedAt(executedAt)
                .approvedAt(approvedAt)
                .retryCount(retryCount)
                .statusReason(statusReason)
                .paymentOrderList(
                        new ArrayList<>(Optional.ofNullable(paymentOrderList)
                                .orElse(Collections.emptyList()))
                )
                .createdAt(getCreatedAt())
                .lastStatusChangedAt(lastStatusChangedAt)
                .allArgsBuild();
    }
}
