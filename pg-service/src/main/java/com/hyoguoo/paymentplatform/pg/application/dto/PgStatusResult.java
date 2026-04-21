package com.hyoguoo.paymentplatform.pg.application.dto;

import com.hyoguoo.paymentplatform.pg.domain.enums.PgPaymentStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * pg-service 내부 PG 상태 조회 결과 DTO.
 * payment-service의 PaymentStatusResult 의존을 끊고 pg-service 독립 DTO로 선언.
 */
public record PgStatusResult(
        String paymentKey,
        String orderId,
        PgPaymentStatus status,
        BigDecimal amount,
        LocalDateTime approvedAt,
        PgFailureInfo failure
) {

}
