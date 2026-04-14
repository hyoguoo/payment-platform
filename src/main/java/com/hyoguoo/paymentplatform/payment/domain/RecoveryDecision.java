package com.hyoguoo.paymentplatform.payment.domain;

import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentStatusResult;
import com.hyoguoo.paymentplatform.payment.domain.dto.enums.PaymentStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.RecoveryReason;
import com.hyoguoo.paymentplatform.payment.exception.PaymentGatewayStatusUnmappedException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentGatewayNonRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentGatewayRetryableException;
import java.util.Set;

/**
 * 복구 사이클에서 내릴 결정을 표현하는 순수 도메인 값 객체.
 * Spring 의존 없음.
 * <p>
 * 종결 상태 판별은 {@link PaymentEventStatus#isTerminal()}을 SSOT로 사용한다.
 * </p>
 */
public record RecoveryDecision(Type type, RecoveryReason reason) {

    private static final Set<PaymentStatus> PG_TERMINAL_FAIL_STATUSES = Set.of(
            PaymentStatus.CANCELED,
            PaymentStatus.ABORTED,
            PaymentStatus.EXPIRED,
            PaymentStatus.PARTIAL_CANCELED
    );

    private static final Set<PaymentStatus> PG_IN_PROGRESS_STATUSES = Set.of(
            PaymentStatus.IN_PROGRESS,
            PaymentStatus.WAITING_FOR_DEPOSIT
    );

    public enum Type {
        REJECT_REENTRY,
        COMPLETE_SUCCESS,
        GUARD_MISSING_APPROVED_AT,
        COMPLETE_FAILURE,
        ATTEMPT_CONFIRM,
        RETRY_LATER,
        QUARANTINE,
    }

    /**
     * 정상 응답 경로: PG 상태 조회 결과로 복구 결정을 내린다.
     *
     * @param event      로컬 결제 이벤트
     * @param result     PG 상태 조회 결과
     * @param retryCount 현재 재시도 횟수
     * @param maxRetries 최대 재시도 허용 횟수
     */
    public static RecoveryDecision from(
            PaymentEvent event,
            PaymentStatusResult result,
            int retryCount,
            int maxRetries
    ) {
        if (isLocalTerminal(event)) {
            return new RecoveryDecision(Type.REJECT_REENTRY, null);
        }

        PaymentStatus pgStatus = result.status();

        if (pgStatus == PaymentStatus.DONE) {
            if (result.approvedAt() != null) {
                return new RecoveryDecision(Type.COMPLETE_SUCCESS, null);
            }
            // PG DONE이지만 approvedAt null — 한도 소진 시 격리, 미소진 시 다음 틱 재시도
            return retryCount < maxRetries
                    ? new RecoveryDecision(Type.GUARD_MISSING_APPROVED_AT, null)
                    : new RecoveryDecision(Type.QUARANTINE, RecoveryReason.GUARD_MISSING_APPROVED_AT);
        }

        if (PG_TERMINAL_FAIL_STATUSES.contains(pgStatus)) {
            return new RecoveryDecision(Type.COMPLETE_FAILURE, RecoveryReason.PG_TERMINAL_FAIL);
        }

        if (PG_IN_PROGRESS_STATUSES.contains(pgStatus)) {
            if (retryCount == 0) {
                // 첫 시도에서 PG가 IN_PROGRESS → 서버 승인 API를 아직 호출하지 않은 상태.
                // confirm을 시도해야 PG가 DONE으로 전이한다.
                return new RecoveryDecision(Type.ATTEMPT_CONFIRM, null);
            }
            return retryCount < maxRetries
                    ? new RecoveryDecision(Type.RETRY_LATER, RecoveryReason.PG_IN_PROGRESS)
                    : new RecoveryDecision(Type.QUARANTINE, RecoveryReason.PG_IN_PROGRESS);
        }

        return retryCount < maxRetries
                ? new RecoveryDecision(Type.RETRY_LATER, RecoveryReason.GATEWAY_STATUS_UNKNOWN)
                : new RecoveryDecision(Type.QUARANTINE, RecoveryReason.GATEWAY_STATUS_UNKNOWN);
    }

    /**
     * 예외 경로: PG 상태 조회 또는 확인 중 발생한 예외로 복구 결정을 내린다.
     * <p>
     * 호출 계약: exception은 반드시 아래 세 타입 중 하나여야 한다.
     * OutboxProcessingService의 catch 블록이 이 계약을 강제한다.
     * <ul>
     *   <li>{@link PaymentGatewayNonRetryableException} — PG에 결제 기록이 없음(getStatus 실패).
     *       retryCount와 무관하게 confirm을 시도하는 것이 정당함(PG 미기록 상태이므로 새 결제 시도 안전).</li>
     *   <li>{@link PaymentGatewayRetryableException} — PG 일시 오류, 재시도 여지 있음.</li>
     *   <li>{@link PaymentGatewayStatusUnmappedException} — PG 응답 상태가 매핑 불가.</li>
     * </ul>
     * </p>
     *
     * @param event      로컬 결제 이벤트
     * @param exception  발생한 예외 (위 세 타입 중 하나)
     * @param retryCount 현재 재시도 횟수
     * @param maxRetries 최대 재시도 허용 횟수
     */
    public static RecoveryDecision fromException(
            PaymentEvent event,
            Exception exception,
            int retryCount,
            int maxRetries
    ) {
        if (exception instanceof PaymentGatewayNonRetryableException) {
            // PG에 결제 기록 없음 → retryCount 무관 ATTEMPT_CONFIRM
            // (NonRetryable = PG 미기록 상태, 재시도해도 중복 결제 아님)
            return new RecoveryDecision(Type.ATTEMPT_CONFIRM, null);
        }

        RecoveryReason reason = resolveExceptionReason(exception);

        return retryCount < maxRetries
                ? new RecoveryDecision(Type.RETRY_LATER, reason)
                : new RecoveryDecision(Type.QUARANTINE, reason);
    }

    private static boolean isLocalTerminal(PaymentEvent event) {
        return event.getStatus().isTerminal();
    }

    private static RecoveryReason resolveExceptionReason(Exception exception) {
        if (exception instanceof PaymentGatewayStatusUnmappedException) {
            return RecoveryReason.UNMAPPED;
        }
        if (exception instanceof PaymentGatewayRetryableException) {
            return RecoveryReason.GATEWAY_STATUS_UNKNOWN;
        }
        return RecoveryReason.GATEWAY_STATUS_UNKNOWN;
    }
}
