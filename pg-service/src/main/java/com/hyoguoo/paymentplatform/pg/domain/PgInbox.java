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

    /**
     * K5: fixed Instant 주입 오버로드 — 시간 결정성 테스트용.
     * 호출자(PgInboxRepositoryImpl)가 {@code clock.instant()} 를 전달한다.
     *
     * @param orderId 주문 ID
     * @param amount  결제 금액
     * @param now     현재 Instant (clock.instant() 전달)
     */
    public static PgInbox create(String orderId, Long amount, Instant now) {
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

    /**
     * NONE → IN_PROGRESS 도메인 전이.
     * SQL CAS 전에 도메인 객체가 사전 검증하는 역할을 한다(옵션 A — SQL CAS 가 race window 최종 가드).
     *
     * @throws IllegalStateException NONE 이 아닌 상태에서 호출 시
     */
    public void markInProgress() {
        if (this.status != PgInboxStatus.NONE) {
            throw new IllegalStateException(
                    "PgInbox.markInProgress: status must be NONE but was " + this.status);
        }
        this.status = PgInboxStatus.IN_PROGRESS;
        this.updatedAt = Instant.now();
    }

    /**
     * K5: fixed Instant 주입 오버로드 — NONE → IN_PROGRESS 전이 + updatedAt 결정성.
     * 호출자(PgInboxRepositoryImpl)가 {@code clock.instant()} 를 전달한다.
     *
     * @param updatedAt 갱신 시각 (clock.instant() 전달)
     * @throws IllegalStateException NONE 이 아닌 상태에서 호출 시
     */
    public void markInProgress(Instant updatedAt) {
        if (this.status != PgInboxStatus.NONE) {
            throw new IllegalStateException(
                    "PgInbox.markInProgress: status must be NONE but was " + this.status);
        }
        this.status = PgInboxStatus.IN_PROGRESS;
        this.updatedAt = updatedAt;
    }

    /**
     * IN_PROGRESS → APPROVED 도메인 전이.
     *
     * @param storedStatusResult 벤더 응답 JSON
     * @throws IllegalStateException IN_PROGRESS 가 아닌 상태에서 호출 시
     */
    public void markApproved(String storedStatusResult) {
        if (this.status != PgInboxStatus.IN_PROGRESS) {
            throw new IllegalStateException(
                    "PgInbox.markApproved: status must be IN_PROGRESS but was " + this.status);
        }
        this.status = PgInboxStatus.APPROVED;
        this.storedStatusResult = storedStatusResult;
        this.updatedAt = Instant.now();
    }

    /**
     * K5: fixed Instant 주입 오버로드 — IN_PROGRESS → APPROVED 전이 + updatedAt 결정성.
     *
     * @param storedStatusResult 벤더 응답 JSON
     * @param updatedAt          갱신 시각 (clock.instant() 전달)
     * @throws IllegalStateException IN_PROGRESS 가 아닌 상태에서 호출 시
     */
    public void markApproved(String storedStatusResult, Instant updatedAt) {
        if (this.status != PgInboxStatus.IN_PROGRESS) {
            throw new IllegalStateException(
                    "PgInbox.markApproved: status must be IN_PROGRESS but was " + this.status);
        }
        this.status = PgInboxStatus.APPROVED;
        this.storedStatusResult = storedStatusResult;
        this.updatedAt = updatedAt;
    }

    /**
     * IN_PROGRESS → FAILED 도메인 전이.
     *
     * @param storedStatusResult 벤더 응답 JSON
     * @param reasonCode         실패 사유 코드
     * @throws IllegalStateException IN_PROGRESS 가 아닌 상태에서 호출 시
     */
    public void markFailed(String storedStatusResult, String reasonCode) {
        if (this.status != PgInboxStatus.IN_PROGRESS) {
            throw new IllegalStateException(
                    "PgInbox.markFailed: status must be IN_PROGRESS but was " + this.status);
        }
        this.status = PgInboxStatus.FAILED;
        this.storedStatusResult = storedStatusResult;
        this.reasonCode = reasonCode;
        this.updatedAt = Instant.now();
    }

    /**
     * K5: fixed Instant 주입 오버로드 — IN_PROGRESS → FAILED 전이 + updatedAt 결정성.
     *
     * @param storedStatusResult 벤더 응답 JSON
     * @param reasonCode         실패 사유 코드
     * @param updatedAt          갱신 시각 (clock.instant() 전달)
     * @throws IllegalStateException IN_PROGRESS 가 아닌 상태에서 호출 시
     */
    public void markFailed(String storedStatusResult, String reasonCode, Instant updatedAt) {
        if (this.status != PgInboxStatus.IN_PROGRESS) {
            throw new IllegalStateException(
                    "PgInbox.markFailed: status must be IN_PROGRESS but was " + this.status);
        }
        this.status = PgInboxStatus.FAILED;
        this.storedStatusResult = storedStatusResult;
        this.reasonCode = reasonCode;
        this.updatedAt = updatedAt;
    }

    /**
     * non-terminal → QUARANTINED 도메인 전이.
     * DLQ consumer 또는 FCG 에서 격리 시 호출한다.
     *
     * @param storedStatusResult 벤더 응답 JSON (nullable)
     * @param reasonCode         격리 사유 코드 (e.g., "RETRY_EXHAUSTED")
     * @throws IllegalStateException 이미 terminal 상태에서 호출 시 (불변식 6c)
     */
    public void markQuarantined(String storedStatusResult, String reasonCode) {
        if (this.status.isTerminal()) {
            throw new IllegalStateException(
                    "PgInbox.markQuarantined: status is already terminal: " + this.status);
        }
        this.status = PgInboxStatus.QUARANTINED;
        this.storedStatusResult = storedStatusResult;
        this.reasonCode = reasonCode;
        this.updatedAt = Instant.now();
    }

    /**
     * K5: fixed Instant 주입 오버로드 — non-terminal → QUARANTINED 전이 + updatedAt 결정성.
     *
     * @param storedStatusResult 벤더 응답 JSON (nullable)
     * @param reasonCode         격리 사유 코드
     * @param updatedAt          갱신 시각 (clock.instant() 전달)
     * @throws IllegalStateException 이미 terminal 상태에서 호출 시 (불변식 6c)
     */
    public void markQuarantined(String storedStatusResult, String reasonCode, Instant updatedAt) {
        if (this.status.isTerminal()) {
            throw new IllegalStateException(
                    "PgInbox.markQuarantined: status is already terminal: " + this.status);
        }
        this.status = PgInboxStatus.QUARANTINED;
        this.storedStatusResult = storedStatusResult;
        this.reasonCode = reasonCode;
        this.updatedAt = updatedAt;
    }

    /**
     * @deprecated 호출처 없음 — markInProgress/markApproved/markFailed/markQuarantined 로 교체.
     *             K4 이후 제거 예정.
     */
    @Deprecated(since = "K4", forRemoval = true)
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

    /**
     * @deprecated 호출처 FakePgInboxRepository 에서만 사용 — markApproved/markFailed/markQuarantined 로 교체.
     *             K4 이후 제거 예정.
     */
    @Deprecated(since = "K4", forRemoval = true)
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
