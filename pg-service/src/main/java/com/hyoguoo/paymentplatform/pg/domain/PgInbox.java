package com.hyoguoo.paymentplatform.pg.domain;

import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import java.time.Instant;

/**
 * pg-service business inbox 도메인 POJO.
 * JPA 엔티티(PgInboxEntity) 와 도메인 객체를 분리해 hexagonal 경계를 유지한다.
 *
 * <p>amount: 원화 최소 단위 정수 (BigDecimal → Long scale=0 변환은 {@code AmountConverter} 경유).
 */
public class PgInbox {

    private final String orderId;
    private PgInboxStatus status;
    private final Long amount;
    private String storedStatusResult;
    private String reasonCode;
    private final Instant createdAt;
    private Instant updatedAt;
    /**
     * PCS-9 (V3 migration): listener PENDING INSERT 시 기록한 벤더 결제 키.
     * 워커(PgInboxProcessor)가 inboxId 기반 재조회 후 PgConfirmRequest 구성에 사용한다.
     */
    private final String paymentKey;
    /**
     * PCS-9 (V3 migration): listener PENDING INSERT 시 기록한 벤더 타입 (e.g., "TOSS_PAYMENTS").
     */
    private final String vendorType;

    private PgInbox(
            String orderId,
            PgInboxStatus status,
            Long amount,
            String storedStatusResult,
            String reasonCode,
            Instant createdAt,
            Instant updatedAt,
            String paymentKey,
            String vendorType) {
        this.orderId = orderId;
        this.status = status;
        this.amount = amount;
        this.storedStatusResult = storedStatusResult;
        this.reasonCode = reasonCode;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.paymentKey = paymentKey;
        this.vendorType = vendorType;
    }

    /**
     * 정상 경로 신규 inbox 생성 — PENDING 상태로 시작.
     * listener TX 에서 PENDING INSERT 시 사용.
     *
     * @param orderId    주문 ID
     * @param amount     결제 금액
     * @param paymentKey 벤더 결제 키 (PCS-9 V3)
     * @param vendorType 벤더 타입 문자열 (PCS-9 V3)
     */
    public static PgInbox create(String orderId, Long amount, String paymentKey, String vendorType) {
        Instant now = Instant.now();
        return new PgInbox(orderId, PgInboxStatus.PENDING, amount, null, null, now, now, paymentKey, vendorType);
    }

    /**
     * 하위 호환 오버로드 — paymentKey / vendorType null 기본값.
     * 기존 테스트 코드 호환성 유지 (PCS-9).
     *
     * @param orderId 주문 ID
     * @param amount  결제 금액
     */
    public static PgInbox create(String orderId, Long amount) {
        Instant now = Instant.now();
        return new PgInbox(orderId, PgInboxStatus.PENDING, amount, null, null, now, now, null, null);
    }

    /**
     * fixed Instant 주입 오버로드 — 시간 결정성 테스트용.
     * 호출자(PgInboxRepositoryImpl)가 {@code clock.instant()} 를 전달한다.
     *
     * @param orderId    주문 ID
     * @param amount     결제 금액
     * @param now        현재 Instant (clock.instant() 전달)
     * @param paymentKey 벤더 결제 키 (PCS-9 V3)
     * @param vendorType 벤더 타입 문자열 (PCS-9 V3)
     */
    public static PgInbox create(String orderId, Long amount, Instant now, String paymentKey, String vendorType) {
        return new PgInbox(orderId, PgInboxStatus.PENDING, amount, null, null, now, now, paymentKey, vendorType);
    }

    /**
     * fixed Instant 주입 오버로드 — paymentKey / vendorType null 기본값. 하위 호환.
     *
     * @param orderId 주문 ID
     * @param amount  결제 금액
     * @param now     현재 Instant (clock.instant() 전달)
     */
    public static PgInbox create(String orderId, Long amount, Instant now) {
        return new PgInbox(orderId, PgInboxStatus.PENDING, amount, null, null, now, now, null, null);
    }

    /**
     * 보정 경로 전용 — PENDING 우회, 바로 IN_PROGRESS 신설.
     * {@code DuplicateApprovalHandler.handleDbAbsent*} 호출 한정 (§1.8 봉인).
     *
     * @param orderId 주문 ID
     * @param amount  결제 금액
     */
    public static PgInbox createDirectInProgress(String orderId, Long amount) {
        Instant now = Instant.now();
        return new PgInbox(orderId, PgInboxStatus.IN_PROGRESS, amount, null, null, now, now, null, null);
    }

    /**
     * 보정 경로 전용 — PENDING 우회, 바로 terminal 상태(APPROVED / QUARANTINED) 신설.
     * {@code DuplicateApprovalHandler.handleDbAbsent*} 호출 한정 (§1.8 봉인).
     *
     * @param orderId            주문 ID
     * @param amount             결제 금액
     * @param terminalStatus     APPROVED 또는 QUARANTINED (terminal 이어야 함)
     * @param storedStatusResult 벤더 응답 JSON
     * @throws IllegalArgumentException terminal 이 아닌 status 전달 시
     */
    public static PgInbox createDirectTerminal(
            String orderId, Long amount, PgInboxStatus terminalStatus, String storedStatusResult) {
        if (!terminalStatus.isTerminal()) {
            throw new IllegalArgumentException(
                    "PgInbox.createDirectTerminal: status must be terminal but was " + terminalStatus);
        }
        Instant now = Instant.now();
        return new PgInbox(orderId, terminalStatus, amount, storedStatusResult, null, now, now, null, null);
    }

    public static PgInbox of(
            String orderId,
            PgInboxStatus status,
            Long amount,
            String storedStatusResult,
            String reasonCode,
            Instant createdAt,
            Instant updatedAt) {
        return new PgInbox(orderId, status, amount, storedStatusResult, reasonCode, createdAt, updatedAt, null, null);
    }

    public static PgInbox of(
            String orderId,
            PgInboxStatus status,
            Long amount,
            String storedStatusResult,
            String reasonCode,
            Instant createdAt,
            Instant updatedAt,
            String paymentKey,
            String vendorType) {
        return new PgInbox(orderId, status, amount, storedStatusResult, reasonCode,
                createdAt, updatedAt, paymentKey, vendorType);
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

    public String getPaymentKey() {
        return paymentKey;
    }

    public String getVendorType() {
        return vendorType;
    }

    /**
     * PENDING → IN_PROGRESS 도메인 전이.
     * SQL CAS 전에 도메인 객체가 사전 검증하는 역할을 한다(옵션 A — SQL CAS 가 race window 최종 가드).
     * PCS-2: NONE 폐기 후 진입 조건이 PENDING 으로 변경됨.
     *
     * @throws IllegalStateException PENDING 이 아닌 상태에서 호출 시
     */
    public void markInProgress() {
        if (this.status != PgInboxStatus.PENDING) {
            throw new IllegalStateException(
                    "PgInbox.markInProgress: status must be PENDING but was " + this.status);
        }
        this.status = PgInboxStatus.IN_PROGRESS;
        this.updatedAt = Instant.now();
    }

    /**
     * fixed Instant 주입 오버로드 — PENDING → IN_PROGRESS 전이 + updatedAt 결정성.
     * 호출자(PgInboxRepositoryImpl)가 {@code clock.instant()} 를 전달한다.
     * PCS-2: NONE 폐기 후 진입 조건이 PENDING 으로 변경됨.
     *
     * @param updatedAt 갱신 시각 (clock.instant() 전달)
     * @throws IllegalStateException PENDING 이 아닌 상태에서 호출 시
     */
    public void markInProgress(Instant updatedAt) {
        if (this.status != PgInboxStatus.PENDING) {
            throw new IllegalStateException(
                    "PgInbox.markInProgress: status must be PENDING but was " + this.status);
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
     * fixed Instant 주입 오버로드 —IN_PROGRESS → APPROVED 전이 + updatedAt 결정성.
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
     * fixed Instant 주입 오버로드 —IN_PROGRESS → FAILED 전이 + updatedAt 결정성.
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
     * fixed Instant 주입 오버로드 —non-terminal → QUARANTINED 전이 + updatedAt 결정성.
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

}
