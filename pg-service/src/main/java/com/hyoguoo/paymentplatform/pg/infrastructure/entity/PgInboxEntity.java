package com.hyoguoo.paymentplatform.pg.infrastructure.entity;

import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * pg_inbox 테이블 JPA 엔티티.
 * V1__pg_schema.sql의 5상태 business inbox 스키마와 매핑된다.
 */
@Getter
@Entity
@Table(name = "pg_inbox")
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PgInboxEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "order_id", nullable = false, unique = true)
    private String orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PgInboxStatus status;

    @Column(name = "amount", nullable = false)
    private Long amount;

    @Column(name = "stored_status_result", length = 50)
    private String storedStatusResult;

    @Column(name = "reason_code", length = 100)
    private String reasonCode;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static PgInboxEntity from(PgInbox inbox) {
        return PgInboxEntity.builder()
                .orderId(inbox.getOrderId())
                .status(inbox.getStatus())
                .amount(inbox.getAmount())
                .storedStatusResult(inbox.getStoredStatusResult())
                .reasonCode(inbox.getReasonCode())
                .createdAt(LocalDateTime.ofInstant(inbox.getCreatedAt(), ZoneOffset.UTC))
                .updatedAt(LocalDateTime.ofInstant(inbox.getUpdatedAt(), ZoneOffset.UTC))
                .build();
    }

    public PgInbox toDomain() {
        return PgInbox.of(
                orderId,
                status,
                amount,
                storedStatusResult,
                reasonCode,
                createdAt.toInstant(ZoneOffset.UTC),
                updatedAt.toInstant(ZoneOffset.UTC)
        );
    }
}
