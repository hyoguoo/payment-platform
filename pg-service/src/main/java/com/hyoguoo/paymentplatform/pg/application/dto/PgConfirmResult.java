package com.hyoguoo.paymentplatform.pg.application.dto;

import com.hyoguoo.paymentplatform.pg.domain.enums.PgConfirmResultStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * pg-service 내부 PG 승인 결과 DTO.
 * payment-service의 PaymentConfirmResult 의존을 끊고 pg-service 독립 DTO로 선언.
 *
 * <p>{@code approvedAtRaw} 는 벤더 응답의 원본 ISO-8601 문자열을 그대로 보존한다 —
 * ConfirmedEventPayload.approved() 에 그대로 전달해 직렬화 경계에서 형식 손실을 막는다.
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

    public boolean isSuccess() {
        return status == PgConfirmResultStatus.SUCCESS;
    }

    public boolean isRetryable() {
        return status == PgConfirmResultStatus.RETRYABLE_FAILURE;
    }
}
