package com.hyoguoo.paymentplatform.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentFailureInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentStatusResult;
import com.hyoguoo.paymentplatform.payment.domain.dto.enums.PaymentStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.RecoveryReason;
import com.hyoguoo.paymentplatform.payment.exception.PaymentGatewayStatusUnmappedException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossNonRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("RecoveryDecision 팩토리 메서드 테스트")
class RecoveryDecisionTest {

    private static final int RETRY_COUNT_UNDER = 1;
    private static final int RETRY_COUNT_AT_LIMIT = 3;
    private static final int MAX_RETRIES = 3;

    // -------------------------------------------------------------------------
    // 헬퍼 메서드
    // -------------------------------------------------------------------------

    private static PaymentEvent buildEvent(PaymentEventStatus status) {
        return PaymentEvent.allArgsBuilder()
                .buyerId(1L)
                .sellerId(2L)
                .orderName("테스트 주문")
                .orderId("order-001")
                .paymentKey("pay-key-001")
                .status(status)
                .retryCount(0)
                .paymentOrderList(List.of())
                .lastStatusChangedAt(LocalDateTime.now())
                .allArgsBuild();
    }

    private static PaymentStatusResult buildResult(PaymentStatus pgStatus, LocalDateTime approvedAt) {
        return new PaymentStatusResult(
                "pay-key-001",
                "order-001",
                pgStatus,
                BigDecimal.valueOf(10000),
                approvedAt,
                new PaymentFailureInfo(null, null, false)
        );
    }

    private static PaymentStatusResult buildResult(PaymentStatus pgStatus) {
        return buildResult(pgStatus, null);
    }

    // -------------------------------------------------------------------------
    // from() — 로컬 종결 상태 → REJECT_REENTRY
    // -------------------------------------------------------------------------

    @DisplayName("로컬 종결 상태이면 REJECT_REENTRY를 반환한다")
    @ParameterizedTest
    @EnumSource(names = {"DONE", "FAILED", "CANCELED", "PARTIAL_CANCELED", "EXPIRED", "QUARANTINED"})
    void from_LocalTerminal_RejectsReentry(PaymentEventStatus terminalStatus) {
        PaymentEvent event = buildEvent(terminalStatus);
        PaymentStatusResult anyResult = buildResult(PaymentStatus.IN_PROGRESS);

        RecoveryDecision decision = RecoveryDecision.from(event, anyResult, 0, MAX_RETRIES);

        assertThat(decision.type()).isEqualTo(RecoveryDecision.Type.REJECT_REENTRY);
    }

    // -------------------------------------------------------------------------
    // from() — PG DONE + approvedAt 존재 → COMPLETE_SUCCESS
    // -------------------------------------------------------------------------

    @DisplayName("PG DONE이고 approvedAt이 존재하면 COMPLETE_SUCCESS를 반환한다")
    @Test
    void from_PgDoneWithApprovedAt_CompleteSuccess() {
        PaymentEvent event = buildEvent(PaymentEventStatus.IN_PROGRESS);
        PaymentStatusResult result = buildResult(PaymentStatus.DONE, LocalDateTime.now());

        RecoveryDecision decision = RecoveryDecision.from(event, result, 0, MAX_RETRIES);

        assertThat(decision.type()).isEqualTo(RecoveryDecision.Type.COMPLETE_SUCCESS);
    }

    // -------------------------------------------------------------------------
    // from() — PG DONE + approvedAt null → GUARD_MISSING_APPROVED_AT
    // -------------------------------------------------------------------------

    @DisplayName("PG DONE이지만 approvedAt이 null이면 GUARD_MISSING_APPROVED_AT를 반환한다")
    @Test
    void from_PgDoneWithNullApprovedAt_GuardMissingApprovedAt() {
        PaymentEvent event = buildEvent(PaymentEventStatus.IN_PROGRESS);
        PaymentStatusResult result = buildResult(PaymentStatus.DONE, null);

        RecoveryDecision decision = RecoveryDecision.from(event, result, 0, MAX_RETRIES);

        assertThat(decision.type()).isEqualTo(RecoveryDecision.Type.GUARD_MISSING_APPROVED_AT);
    }

    // -------------------------------------------------------------------------
    // from() — PG 종결 실패 상태 → COMPLETE_FAILURE
    // -------------------------------------------------------------------------

    @DisplayName("PG가 종결 실패 상태이면 COMPLETE_FAILURE와 PG_TERMINAL_FAIL reason을 반환한다")
    @ParameterizedTest
    @EnumSource(names = {"CANCELED", "ABORTED", "EXPIRED", "PARTIAL_CANCELED"})
    void from_PgTerminalFail_CompleteFailure(PaymentStatus terminalStatus) {
        PaymentEvent event = buildEvent(PaymentEventStatus.IN_PROGRESS);
        PaymentStatusResult result = buildResult(terminalStatus);

        RecoveryDecision decision = RecoveryDecision.from(event, result, 0, MAX_RETRIES);

        assertThat(decision.type()).isEqualTo(RecoveryDecision.Type.COMPLETE_FAILURE);
        assertThat(decision.reason()).isEqualTo(RecoveryReason.PG_TERMINAL_FAIL);
    }

    // -------------------------------------------------------------------------
    // from() — PG IN_PROGRESS + 한도 미소진 → RETRY_LATER
    // -------------------------------------------------------------------------

    @DisplayName("PG가 IN_PROGRESS이고 재시도 한도 미소진이면 RETRY_LATER와 PG_IN_PROGRESS reason을 반환한다")
    @Test
    void from_PgInProgress_UnderLimit_RetryLater() {
        PaymentEvent event = buildEvent(PaymentEventStatus.IN_PROGRESS);
        PaymentStatusResult result = buildResult(PaymentStatus.IN_PROGRESS);

        RecoveryDecision decision = RecoveryDecision.from(event, result, RETRY_COUNT_UNDER, MAX_RETRIES);

        assertThat(decision.type()).isEqualTo(RecoveryDecision.Type.RETRY_LATER);
        assertThat(decision.reason()).isEqualTo(RecoveryReason.PG_IN_PROGRESS);
    }

    // -------------------------------------------------------------------------
    // from() — PG IN_PROGRESS + 한도 소진 → QUARANTINE
    // -------------------------------------------------------------------------

    @DisplayName("PG가 IN_PROGRESS이고 재시도 한도 소진이면 QUARANTINE과 PG_IN_PROGRESS reason을 반환한다")
    @Test
    void from_PgInProgress_AtLimit_Quarantine() {
        PaymentEvent event = buildEvent(PaymentEventStatus.IN_PROGRESS);
        PaymentStatusResult result = buildResult(PaymentStatus.IN_PROGRESS);

        RecoveryDecision decision = RecoveryDecision.from(event, result, RETRY_COUNT_AT_LIMIT, MAX_RETRIES);

        assertThat(decision.type()).isEqualTo(RecoveryDecision.Type.QUARANTINE);
        assertThat(decision.reason()).isEqualTo(RecoveryReason.PG_IN_PROGRESS);
    }

    // -------------------------------------------------------------------------
    // fromException() — retryable 예외 + 한도 미소진 → RETRY_LATER
    // -------------------------------------------------------------------------

    @DisplayName("retryable 예외이고 재시도 한도 미소진이면 RETRY_LATER와 GATEWAY_STATUS_UNKNOWN reason을 반환한다")
    @Test
    void fromException_RetryableException_UnderLimit_RetryLater() {
        PaymentEvent event = buildEvent(PaymentEventStatus.IN_PROGRESS);
        Exception retryableEx = PaymentTossRetryableException.of(PaymentErrorCode.TOSS_RETRYABLE_ERROR);

        RecoveryDecision decision = RecoveryDecision.fromException(event, retryableEx, RETRY_COUNT_UNDER, MAX_RETRIES);

        assertThat(decision.type()).isEqualTo(RecoveryDecision.Type.RETRY_LATER);
        assertThat(decision.reason()).isEqualTo(RecoveryReason.GATEWAY_STATUS_UNKNOWN);
    }

    // -------------------------------------------------------------------------
    // fromException() — retryable 예외 + 한도 소진 → QUARANTINE
    // -------------------------------------------------------------------------

    @DisplayName("retryable 예외이고 재시도 한도 소진이면 QUARANTINE과 GATEWAY_STATUS_UNKNOWN reason을 반환한다")
    @Test
    void fromException_RetryableException_AtLimit_Quarantine() {
        PaymentEvent event = buildEvent(PaymentEventStatus.IN_PROGRESS);
        Exception retryableEx = PaymentTossRetryableException.of(PaymentErrorCode.TOSS_RETRYABLE_ERROR);

        RecoveryDecision decision = RecoveryDecision.fromException(event, retryableEx, RETRY_COUNT_AT_LIMIT, MAX_RETRIES);

        assertThat(decision.type()).isEqualTo(RecoveryDecision.Type.QUARANTINE);
        assertThat(decision.reason()).isEqualTo(RecoveryReason.GATEWAY_STATUS_UNKNOWN);
    }

    // -------------------------------------------------------------------------
    // fromException() — unmapped 예외 + 한도 미소진 → RETRY_LATER
    // -------------------------------------------------------------------------

    @DisplayName("unmapped 예외이고 재시도 한도 미소진이면 RETRY_LATER와 UNMAPPED reason을 반환한다")
    @Test
    void fromException_UnmappedException_UnderLimit_RetryLater() {
        PaymentEvent event = buildEvent(PaymentEventStatus.IN_PROGRESS);
        Exception unmappedEx = PaymentGatewayStatusUnmappedException.of("UNKNOWN_PG_STATUS");

        RecoveryDecision decision = RecoveryDecision.fromException(event, unmappedEx, RETRY_COUNT_UNDER, MAX_RETRIES);

        assertThat(decision.type()).isEqualTo(RecoveryDecision.Type.RETRY_LATER);
        assertThat(decision.reason()).isEqualTo(RecoveryReason.UNMAPPED);
    }

    // -------------------------------------------------------------------------
    // fromException() — unmapped 예외 + 한도 소진 → QUARANTINE
    // -------------------------------------------------------------------------

    @DisplayName("unmapped 예외이고 재시도 한도 소진이면 QUARANTINE과 UNMAPPED reason을 반환한다")
    @Test
    void fromException_UnmappedException_AtLimit_Quarantine() {
        PaymentEvent event = buildEvent(PaymentEventStatus.IN_PROGRESS);
        Exception unmappedEx = PaymentGatewayStatusUnmappedException.of("UNKNOWN_PG_STATUS");

        RecoveryDecision decision = RecoveryDecision.fromException(event, unmappedEx, RETRY_COUNT_AT_LIMIT, MAX_RETRIES);

        assertThat(decision.type()).isEqualTo(RecoveryDecision.Type.QUARANTINE);
        assertThat(decision.reason()).isEqualTo(RecoveryReason.UNMAPPED);
    }

    // -------------------------------------------------------------------------
    // fromException() — non-retryable 예외 → ATTEMPT_CONFIRM
    // -------------------------------------------------------------------------

    @DisplayName("non-retryable 예외이면 ATTEMPT_CONFIRM을 반환한다")
    @Test
    void fromException_NonRetryableException_AttemptConfirm() {
        PaymentEvent event = buildEvent(PaymentEventStatus.IN_PROGRESS);
        Exception nonRetryableEx = PaymentTossNonRetryableException.of(PaymentErrorCode.TOSS_NON_RETRYABLE_ERROR);

        RecoveryDecision decision = RecoveryDecision.fromException(event, nonRetryableEx, 0, MAX_RETRIES);

        assertThat(decision.type()).isEqualTo(RecoveryDecision.Type.ATTEMPT_CONFIRM);
    }
}
