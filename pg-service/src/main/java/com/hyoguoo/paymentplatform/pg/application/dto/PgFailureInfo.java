package com.hyoguoo.paymentplatform.pg.application.dto;

/**
 * pg-service 내부 PG 실패 정보 DTO.
 * payment-service의 PaymentFailureInfo 의존을 끊고 pg-service 독립 DTO로 선언.
 */
public record PgFailureInfo(
        String code,
        String message,
        boolean isRetryable
) {

}
