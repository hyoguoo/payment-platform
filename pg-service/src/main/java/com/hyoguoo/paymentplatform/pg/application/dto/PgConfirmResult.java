package com.hyoguoo.paymentplatform.pg.application.dto;

import com.hyoguoo.paymentplatform.pg.domain.enums.PgConfirmResultStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * pg-service 내부 PG 승인 결과 DTO.
 * payment-service의 PaymentConfirmResult 의존을 끊고 pg-service 독립 DTO로 선언.
 *
 * <p>T-A1: approvedAtRaw 필드 추가 — 벤더 응답의 원본 ISO-8601 문자열 보존.
 * ConfirmedEventPayload.approved() 에 그대로 전달해 직렬화 경계에서 형식 손실 방지.
 */
public record PgConfirmResult(
        PgConfirmResultStatus status,
        String paymentKey,
        String orderId,
        BigDecimal amount,
        LocalDateTime approvedAt,
        PgFailureInfo failure,
        String approvedAtRaw
) {

    /**
     * 기존 6-arg 생성자 — 레거시 호출처 호환용. approvedAtRaw=null.
     * @deprecated T-A1 이후에는 7-arg 생성자를 직접 사용할 것.
     */
    @Deprecated
    public PgConfirmResult(
            PgConfirmResultStatus status,
            String paymentKey,
            String orderId,
            BigDecimal amount,
            LocalDateTime approvedAt,
            PgFailureInfo failure) {
        this(status, paymentKey, orderId, amount, approvedAt, failure, null);
    }

    public boolean isSuccess() {
        return status == PgConfirmResultStatus.SUCCESS;
    }

    public boolean isRetryable() {
        return status == PgConfirmResultStatus.RETRYABLE_FAILURE;
    }
}
