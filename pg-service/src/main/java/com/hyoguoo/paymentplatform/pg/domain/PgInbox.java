package com.hyoguoo.paymentplatform.pg.domain;

import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * pg-service business inbox 도메인 POJO.
 * JPA 엔티티(PgInboxEntity) 와 도메인 객체를 분리해 hexagonal 경계를 유지한다.
 *
 * <p>amount: 원화 최소 단위 정수 (BigDecimal → Long scale=0 변환은 {@code AmountConverter} 경유).
 *
 * <p><b>factory only 노출 룰</b> — 외부에서 {@code allArgsBuilder()} 직접 호출 금지.
 * builder 는 factory 내부 캡슐화 용도이며 외부 호출자는 아래 factory method 만 사용한다:
 * {@code create*}, {@code of}, {@code ofWithId}.
 *
 * <p>여러 생성 시나리오(정상 PENDING / 보정 IN_PROGRESS 우회 / 보정 terminal 우회 /
 * DB 복원 / test 픽스처)를 factory method 로 구분해 노출한다.
 * payment-service {@code PaymentOutbox} 와 동일한 Lombok builder 패턴을 따른다.
 */
@Getter
@Builder(builderMethodName = "allArgsBuilder", buildMethodName = "allArgsBuild")
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PgInbox {

    /**
     * DB row pk. JPA 어댑터가 toDomain() 에서 주입한다.
     * 신규 생성(INSERT 전) 객체에서는 null — id 필요 시 저장 후 반환값을 사용한다.
     * PgConfirmService 가 채널 재적재 시 inboxId 로 사용한다.
     */
    private final Long id;
    private final String orderId;
    private PgInboxStatus status;
    private final Long amount;
    private String storedStatusResult;
    private String reasonCode;
    private final Instant createdAt;
    private Instant updatedAt;
    /**
     * listener PENDING INSERT 시 기록한 벤더 결제 키.
     * 워커(PgInboxProcessor)가 inboxId 기반 재조회 후 PgConfirmRequest 구성에 사용한다.
     */
    private final String paymentKey;
    /**
     * listener PENDING INSERT 시 기록한 벤더 타입 (e.g., "TOSS_PAYMENTS").
     */
    private final String vendorType;

    /**
     * 정상 경로 신규 inbox 생성 — PENDING 상태로 시작.
     * listener TX 에서 PENDING INSERT 시 사용.
     *
     * <p>main 호출처 0건 (test 픽스처 전용) — {@code insertPending} native INSERT 가 정상 경로.
     *
     * @param orderId    주문 ID
     * @param amount     결제 금액
     * @param paymentKey 벤더 결제 키
     * @param vendorType 벤더 타입 문자열
     */
    public static PgInbox create(String orderId, Long amount, String paymentKey, String vendorType) {
        Instant now = Instant.now();
        return PgInbox.allArgsBuilder()
                .id(null)
                .orderId(orderId)
                .status(PgInboxStatus.PENDING)
                .amount(amount)
                .storedStatusResult(null)
                .reasonCode(null)
                .createdAt(now)
                .updatedAt(now)
                .paymentKey(paymentKey)
                .vendorType(vendorType)
                .allArgsBuild();
    }

    /**
     * 하위 호환 오버로드 — paymentKey / vendorType null 기본값.
     *
     * <p>main 호출처 0건 (test 픽스처 전용) — {@code insertPending} native INSERT 가 정상 경로.
     *
     * @param orderId 주문 ID
     * @param amount  결제 금액
     */
    public static PgInbox create(String orderId, Long amount) {
        Instant now = Instant.now();
        return PgInbox.allArgsBuilder()
                .id(null)
                .orderId(orderId)
                .status(PgInboxStatus.PENDING)
                .amount(amount)
                .storedStatusResult(null)
                .reasonCode(null)
                .createdAt(now)
                .updatedAt(now)
                .paymentKey(null)
                .vendorType(null)
                .allArgsBuild();
    }

    /**
     * fixed Instant 주입 오버로드 — 시간 결정성 테스트용.
     * 호출자(PgInboxRepositoryImpl)가 {@code clock.instant()} 를 전달한다.
     *
     * <p>main 호출처 0건 (test 픽스처 전용) — {@code insertPending} native INSERT 가 정상 경로.
     *
     * @param orderId    주문 ID
     * @param amount     결제 금액
     * @param now        현재 Instant (clock.instant() 전달)
     * @param paymentKey 벤더 결제 키
     * @param vendorType 벤더 타입 문자열
     */
    public static PgInbox create(String orderId, Long amount, Instant now, String paymentKey, String vendorType) {
        return PgInbox.allArgsBuilder()
                .id(null)
                .orderId(orderId)
                .status(PgInboxStatus.PENDING)
                .amount(amount)
                .storedStatusResult(null)
                .reasonCode(null)
                .createdAt(now)
                .updatedAt(now)
                .paymentKey(paymentKey)
                .vendorType(vendorType)
                .allArgsBuild();
    }

    /**
     * fixed Instant 주입 오버로드 — paymentKey / vendorType null 기본값. 하위 호환.
     *
     * <p>main 호출처 0건 (test 픽스처 전용) — {@code insertPending} native INSERT 가 정상 경로.
     *
     * @param orderId 주문 ID
     * @param amount  결제 금액
     * @param now     현재 Instant (clock.instant() 전달)
     */
    public static PgInbox create(String orderId, Long amount, Instant now) {
        return PgInbox.allArgsBuilder()
                .id(null)
                .orderId(orderId)
                .status(PgInboxStatus.PENDING)
                .amount(amount)
                .storedStatusResult(null)
                .reasonCode(null)
                .createdAt(now)
                .updatedAt(now)
                .paymentKey(null)
                .vendorType(null)
                .allArgsBuild();
    }

    /**
     * 보정 경로 전용 — PENDING 우회, 바로 IN_PROGRESS 신설.
     * {@code DuplicateApprovalHandler.handleDbAbsent*} 호출 한정.
     *
     * <p>main 호출처 0건 (test 픽스처 전용) — {@code insertPending} native INSERT 가 정상 경로.
     *
     * @param orderId 주문 ID
     * @param amount  결제 금액
     */
    public static PgInbox createDirectInProgress(String orderId, Long amount) {
        Instant now = Instant.now();
        return PgInbox.allArgsBuilder()
                .id(null)
                .orderId(orderId)
                .status(PgInboxStatus.IN_PROGRESS)
                .amount(amount)
                .storedStatusResult(null)
                .reasonCode(null)
                .createdAt(now)
                .updatedAt(now)
                .paymentKey(null)
                .vendorType(null)
                .allArgsBuild();
    }

    /**
     * 보정 경로 전용 — PENDING 우회, 바로 terminal 상태(APPROVED / QUARANTINED) 신설.
     * {@code DuplicateApprovalHandler.handleDbAbsent*} 호출 한정.
     *
     * <p>main 호출처 0건 (test 픽스처 전용) — {@code insertPending} native INSERT 가 정상 경로.
     * 도메인 가드 {@code isTerminal()} 은 test 픽스처 이중화 목적 — main 보호는 어댑터
     * {@code PgInboxRepositoryImpl.transitDirectToTerminal} 의 가드가 담당한다.
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
        return PgInbox.allArgsBuilder()
                .id(null)
                .orderId(orderId)
                .status(terminalStatus)
                .amount(amount)
                .storedStatusResult(storedStatusResult)
                .reasonCode(null)
                .createdAt(now)
                .updatedAt(now)
                .paymentKey(null)
                .vendorType(null)
                .allArgsBuild();
    }

    /**
     * DB 복원 전용 7-arg 오버로드 — id null, paymentKey / vendorType null 기본값.
     * {@link com.hyoguoo.paymentplatform.pg.infrastructure.repository.PgInboxRepositoryImpl#transitDirectToTerminal}
     * 에서 terminal 상태 도메인 객체 생성 시 사용 (reasonCode 포함).
     */
    public static PgInbox of(
            String orderId,
            PgInboxStatus status,
            Long amount,
            String storedStatusResult,
            String reasonCode,
            Instant createdAt,
            Instant updatedAt) {
        return PgInbox.allArgsBuilder()
                .id(null)
                .orderId(orderId)
                .status(status)
                .amount(amount)
                .storedStatusResult(storedStatusResult)
                .reasonCode(reasonCode)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .paymentKey(null)
                .vendorType(null)
                .allArgsBuild();
    }

    /**
     * DB 복원 9-arg 오버로드 — id null, paymentKey / vendorType 명시.
     */
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
        return PgInbox.allArgsBuilder()
                .id(null)
                .orderId(orderId)
                .status(status)
                .amount(amount)
                .storedStatusResult(storedStatusResult)
                .reasonCode(reasonCode)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .paymentKey(paymentKey)
                .vendorType(vendorType)
                .allArgsBuild();
    }

    /**
     * JPA 어댑터 전용 — DB row pk 포함 재구성.
     * {@link com.hyoguoo.paymentplatform.pg.infrastructure.entity.PgInboxEntity#toDomain()} 에서만 사용.
     */
    public static PgInbox ofWithId(
            Long id,
            String orderId,
            PgInboxStatus status,
            Long amount,
            String storedStatusResult,
            String reasonCode,
            Instant createdAt,
            Instant updatedAt,
            String paymentKey,
            String vendorType) {
        return PgInbox.allArgsBuilder()
                .id(id)
                .orderId(orderId)
                .status(status)
                .amount(amount)
                .storedStatusResult(storedStatusResult)
                .reasonCode(reasonCode)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .paymentKey(paymentKey)
                .vendorType(vendorType)
                .allArgsBuild();
    }

    /**
     * PENDING → IN_PROGRESS 도메인 전이.
     * SQL CAS 전에 도메인 객체가 사전 검증하는 역할을 한다 (race window 최종 가드는 SQL CAS).
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
     * @throws IllegalStateException 이미 terminal 상태에서 호출 시
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
     * @throws IllegalStateException 이미 terminal 상태에서 호출 시
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
