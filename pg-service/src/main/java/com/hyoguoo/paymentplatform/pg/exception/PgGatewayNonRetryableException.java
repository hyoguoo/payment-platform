package com.hyoguoo.paymentplatform.pg.exception;

/**
 * pg-service 내부 PG 게이트웨이 재시도 불가 예외.
 * payment-service의 PaymentGatewayNonRetryableException 의존을 끊고 pg-service 독립 예외로 선언.
 */
public class PgGatewayNonRetryableException extends RuntimeException {

    private PgGatewayNonRetryableException(String message) {
        super(message);
    }

    public static PgGatewayNonRetryableException of(String message) {
        return new PgGatewayNonRetryableException(message);
    }
}
