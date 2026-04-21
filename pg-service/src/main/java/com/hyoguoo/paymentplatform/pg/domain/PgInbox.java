package com.hyoguoo.paymentplatform.pg.domain;

import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import java.time.Instant;

/**
 * pg-service business inbox 도메인 POJO.
 * T2a-04에서 JPA 엔티티·Flyway 스키마로 승격 예정. 지금은 plain POJO.
 *
 * <p>amount: 원화 최소 단위 정수 (BigDecimal → Long scale=0 변환 규약은 T2a-04에서 적용).
 */
public class PgInbox {

    private final String orderId;
    private PgInboxStatus status;
    private final Long amount;
    private String storedStatusResult;
    private String reasonCode;
    private final Instant createdAt;
    private Instant updatedAt;

    private PgInbox(
            String orderId,
            PgInboxStatus status,
            Long amount,
            String storedStatusResult,
            String reasonCode,
            Instant createdAt,
            Instant updatedAt) {
        this.orderId = orderId;
        this.status = status;
        this.amount = amount;
        this.storedStatusResult = storedStatusResult;
        this.reasonCode = reasonCode;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static PgInbox create(String orderId, Long amount) {
        Instant now = Instant.now();
        return new PgInbox(orderId, PgInboxStatus.NONE, amount, null, null, now, now);
    }

    public static PgInbox of(
            String orderId,
            PgInboxStatus status,
            Long amount,
            String storedStatusResult,
            String reasonCode,
            Instant createdAt,
            Instant updatedAt) {
        return new PgInbox(orderId, status, amount, storedStatusResult, reasonCode, createdAt, updatedAt);
    }

    public String getOrderId() {
        return orderId;
    }

    public PgInboxStatus getStatus() {
        return status;
    }

    public Long getAmount() {
        return amount;
    }

    public String getStoredStatusResult() {
        return storedStatusResult;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public PgInbox withStatus(PgInboxStatus newStatus) {
        return new PgInbox(
                this.orderId,
                newStatus,
                this.amount,
                this.storedStatusResult,
                this.reasonCode,
                this.createdAt,
                Instant.now());
    }

    public PgInbox withResult(PgInboxStatus newStatus, String storedStatusResult, String reasonCode) {
        return new PgInbox(
                this.orderId,
                newStatus,
                this.amount,
                storedStatusResult,
                reasonCode,
                this.createdAt,
                Instant.now());
    }
}
