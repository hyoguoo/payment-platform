package com.hyoguoo.paymentplatform.pg.application.dto;

import com.hyoguoo.paymentplatform.pg.domain.enums.PgConfirmResultStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * pg-service 내부 PG 승인 결과 DTO.
 * payment-service의 PaymentConfirmResult 의존을 끊고 pg-service 독립 DTO로 선언.
 */
public record PgConfirmResult(
        PgConfirmResultStatus status,
        String paymentKey,
        String orderId,
        BigDecimal amount,
        LocalDateTime approvedAt,
        PgFailureInfo failure
) {

    public boolean isSuccess() {
        return status == PgConfirmResultStatus.SUCCESS;
    }

    public boolean isRetryable() {
        return status == PgConfirmResultStatus.RETRYABLE_FAILURE;
    }
}
